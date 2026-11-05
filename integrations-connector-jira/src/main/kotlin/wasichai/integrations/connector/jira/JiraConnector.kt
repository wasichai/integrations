package wasichai.integrations.connector.jira

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.AccountInfo
import wasichai.integrations.sdk.ActionDeclaration
import wasichai.integrations.sdk.ActionExecutor
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Authenticator
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.ChangeFeed
import wasichai.integrations.sdk.ChangePage
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.ConnectorManifest
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.PaginationStrategy
import wasichai.integrations.sdk.ProviderInfo
import wasichai.integrations.sdk.ProviderRecord
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.ProviderResponse
import wasichai.integrations.sdk.RateLimitHint
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.RecordPage
import wasichai.integrations.sdk.ResourceDeclaration
import wasichai.integrations.sdk.ResourceDescriptor
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceSchema
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.SdkVersion
import wasichai.integrations.sdk.SyncDirectionSupport
import wasichai.integrations.sdk.TokenGrant
import wasichai.integrations.sdk.WebhookHandler
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookSupport
import wasichai.integrations.sdk.WebhookVerification
import wasichai.integrations.sdk.WriteResult
import java.time.Clock
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

// jira cloud through rest api v3: a project is the resource, its issues are the records, issue fields (custom ones
// included) are the fields; the record id is the issue id, which survives a move, the key does not.
// deletions are not in the change feed (search never returns a deleted issue): webhooks or reconciliation see them.
class JiraConnector(
    private val config: JiraConfig = JiraConfig(),
    clock: Clock = Clock.systemUTC()
) : Connector,
    Authenticator,
    ResourceDiscovery,
    ResourceReader,
    ChangeFeed,
    ResourceWriter,
    ActionExecutor,
    WebhookHandler {
    private val nodes = JsonNodeFactory.instance
    private val webhooks = JiraWebhooks(clock)

    // per connection: the site a grant reaches, the searching user's zone (jql dates), and schemas per project
    private val cloudIds = ConcurrentHashMap<String, String>()
    private val zones = ConcurrentHashMap<String, ZoneId>()
    private val schemas = ConcurrentHashMap<String, JiraIssueSchema>()

    override val manifest =
        ConnectorManifest(
            id = ID,
            provider = ProviderInfo("Jira Cloud", "https://www.atlassian.com/software/jira", "https://developer.atlassian.com/cloud/jira/platform/rest/v3/"),
            version = "0.1.0",
            sdkVersion = SdkVersion.CURRENT,
            description =
                "Jira Cloud projects and their issues (custom fields included) through REST API v3: JQL reads, an incremental feed on " +
                    "`updated` (minute precision, at least once), create/edit/delete, and transitions as an action. Rich text fields " +
                    "(Atlassian Document Format) are read and written as plain text paragraphs: writing one drops its formatting. " +
                    "Webhooks are hints, verified by HMAC (X-Hub-Signature) or the OAuth app's JWT signature.",
            authMethods =
                listOf(
                    AuthMethod.OAuth2AuthorizationCode(
                        authorizeUrl = "${config.authUrl}/authorize",
                        tokenUrl = "${config.authUrl}/oauth/token",
                        defaultScopes = config.defaultScopes,
                        // atlassian 3lo documents the client secret, no pkce, and no revocation endpoint
                        pkce = false
                    ),
                    // email + api token against JiraConfig.siteUrl
                    AuthMethod.Basic
                ),
            capabilities =
                mapOf(
                    Capability.AUTHENTICATION to Maturity.TESTED,
                    Capability.DISCOVERY to Maturity.TESTED,
                    Capability.READ to Maturity.TESTED,
                    Capability.CHANGE_FEED to Maturity.TESTED,
                    Capability.WRITE to Maturity.TESTED,
                    Capability.ACTIONS to Maturity.TESTED,
                    // our tests sign their own deliveries: atlassian publishes no signed sample to test against (ADR-0016)
                    Capability.WEBHOOKS to Maturity.IMPLEMENTED
                ),
            resources = listOf(ResourceDeclaration("project", "Project", dynamic = true)),
            actions =
                listOf(
                    ActionDeclaration(TRANSITION, "Transition an issue"),
                    ActionDeclaration(REGISTER_WEBHOOK, "Register a dynamic webhook (OAuth connections)"),
                    ActionDeclaration(REFRESH_WEBHOOKS, "Extend dynamic webhooks by 30 days"),
                    ActionDeclaration(DELETE_WEBHOOKS, "Delete dynamic webhooks")
                ),
            syncDirections = setOf(SyncDirectionSupport.SOURCE, SyncDirectionSupport.TARGET),
            pagination = setOf(PaginationStrategy.CURSOR),
            // jira publishes no fixed rate (cost based) and answers 429 with Retry-After, which the runtime honours
            rateLimit = RateLimitHint(requestsPerSecond = 10.0, burst = 10, scope = "connection"),
            webhooks =
                WebhookSupport(
                    signatureScheme =
                        "X-Hub-Signature: sha256=<hex hmac-sha256(raw body, webhook secret)>, or for OAuth dynamic webhooks " +
                            "Authorization: Bearer <HS256 jwt signed with the app's client secret>",
                    events = listOf("jira:issue_created", "jira:issue_updated", "jira:issue_deleted")
                ),
            requiredPermissions = listOf("Browse projects", "Create issues", "Edit issues", "Delete issues", "Transition issues")
        )

    // ---- authentication

    // audience and prompt are required on atlassian's authorize url
    override fun authorizationParameters(): Map<String, String> = mapOf("audience" to "api.atlassian.com", "prompt" to "consent")

    // the token response names no site: only that this connection goes through the gateway. the cloud id is found
    // from accessible-resources on use (ADR-0016)
    override fun connectionMetadata(grant: TokenGrant): Map<String, String> = mapOf(ROUTE to GATEWAY)

    override suspend fun accountInfo(context: ConnectorContext): AccountInfo {
        if (gateway(context)) {
            val site = site(context)
            return AccountInfo(site.path("id").asString(), site.path("name").asString(null), mapOf("site_url" to site.path("url").asString("")))
        }
        val me = get(context, "${siteUrl()}/rest/api/3/myself").json()
        return AccountInfo(siteUrl(), siteUrl().substringAfter("://"), mapOf("account_id" to me.path("accountId").asString("")))
    }

    // ---- discovery

    override suspend fun listResources(context: ConnectorContext): List<ResourceDescriptor> {
        val found = mutableListOf<ResourceDescriptor>()
        var startAt = 0
        do {
            val page = get(context, "${api(context)}/project/search", mapOf("startAt" to "$startAt", "maxResults" to "50")).json()
            val values = page.path("values").values()
            values.forEach { found += ResourceDescriptor(it.path("key").asString(), it.path("name").asString(it.path("key").asString())) }
            startAt += values.size
        } while (!page.path("isLast").asBoolean(true) && values.isNotEmpty())
        return found
    }

    override suspend fun describe(
        context: ConnectorContext,
        resource: String
    ): ResourceSchema = issueSchema(context, resource, fresh = true).schema

    private suspend fun issueSchema(
        context: ConnectorContext,
        project: String,
        fresh: Boolean = false
    ): JiraIssueSchema {
        val key = "${context.connectionId}/${projectKey(project)}"
        if (!fresh) schemas[key]?.let { return it }
        val schema = JiraFields.schema(get(context, "${api(context)}/project/$project").json(), get(context, "${api(context)}/field").json())
        schemas[key] = schema
        return schema
    }

    // ---- reads

    override suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord? {
        val schema = issueSchema(context, resource)
        val response = exchange(context, ProviderRequest(HttpMethod.GET, "${api(context)}/issue/${issueId(id)}", query = mapOf("fields" to "*all")))
        if (response.status == 404) return null
        val issue = checked(response).json()
        // moved to another project: not a record of this resource anymore
        if (issue
                .path("fields")
                .path("project")
                .path("key")
                .asString(null) != resource
        ) {
            return null
        }
        return record(issue, schema)
    }

    override suspend fun list(
        context: ConnectorContext,
        request: ReadRequest
    ): RecordPage {
        val schema = issueSchema(context, request.resource)
        val page = search(context, "project = ${Jql.string(request.resource)} ORDER BY id ASC", request.cursor, request.pageSize)
        return RecordPage(page.path("issues").values().map { record(it, schema) }, nextToken(page))
    }

    // the enhanced search (POST /rest/api/3/search/jql, nextPageToken) that replaced /search's startAt paging
    private suspend fun search(
        context: ConnectorContext,
        jql: String,
        token: String?,
        pageSize: Int
    ): JsonNode {
        val body =
            nodes
                .objectNode()
                .put("jql", jql)
                .put("maxResults", pageSize.coerceIn(1, 100))
                .set("fields", nodes.arrayNode().add("*all"))
        token?.let { body.put("nextPageToken", it) }
        return send(context, HttpMethod.POST, "${api(context)}/search/jql", body).json()
    }

    private fun nextToken(page: JsonNode): String? = page.path("nextPageToken").asString(null)?.takeUnless { page.path("isLast").asBoolean(false) }

    // jql compares `updated` by the minute in the user's zone: a pass starts a minute before the newest edit
    // seen, so the same issue can come twice; the sync engine drops repeats by content hash
    override suspend fun changes(
        context: ConnectorContext,
        resource: String,
        checkpoint: String?,
        pageSize: Int
    ): ChangePage {
        val schema = issueSchema(context, resource)
        val state = JiraCheckpoint.parse(checkpoint)
        val where = state.since?.let { " AND updated >= ${Jql.string(JiraTimes.jqlLowerBound(it, zone(context)))}" } ?: ""
        val page = search(context, "project = ${Jql.string(resource)}$where ORDER BY updated ASC, id ASC", state.token, pageSize)
        val records = page.path("issues").values().map { record(it, schema) }
        val maxSeen = (records.mapNotNull { it.modifiedAt } + listOfNotNull(state.maxSeen)).maxOrNull()
        val token = nextToken(page)
        val next = if (token != null) JiraCheckpoint(state.since, token, maxSeen) else JiraCheckpoint(maxSeen ?: state.since, null, null)
        return ChangePage(records.map { Change.Upserted(it) }, next.render(), token != null)
    }

    // ---- writes

    override suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        val body = JiraFields.write(fields, issueSchema(context, resource))
        body.set("project", nodes.objectNode().put("key", resource))
        val created = send(context, HttpMethod.POST, "${api(context)}/issue", nodes.objectNode().set("fields", body)).json()
        val id = created.path("id").asString("")
        if (id.isEmpty()) throw ConnectorException.Permanent("create answered without an id")
        return written(context, id, created = true)
    }

    // jira has no conditional edit: with an expected version the issue is read first and compared.
    // the gap between that read and the edit is a known race, narrowed, not closed (as for notion, ADR-0009)
    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): WriteResult {
        val body = nodes.objectNode().set("fields", JiraFields.write(fields, issueSchema(context, resource)))
        if (expectedVersion != null) {
            val current = version(context, id)
            if (current != expectedVersion) throw ConnectorException.Conflict("jira issue $id changed since $expectedVersion", current)
        }
        send(context, HttpMethod.PUT, "${api(context)}/issue/${issueId(id)}", body)
        return written(context, id, created = false)
    }

    override suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    ) {
        checked(exchange(context, ProviderRequest(HttpMethod.DELETE, "${api(context)}/issue/${issueId(id)}")))
    }

    // an edit answers 204 and a create only ids: read `updated` back so the platform stores what it wrote
    private suspend fun written(
        context: ConnectorContext,
        id: String,
        created: Boolean
    ): WriteResult {
        val version = version(context, id)
        return WriteResult(id, version, JiraTimes.parseOrNull(version), created)
    }

    private suspend fun version(
        context: ConnectorContext,
        id: String
    ): String? =
        get(context, "${api(context)}/issue/${issueId(id)}", mapOf("fields" to "updated"))
            .json()
            .path("fields")
            .path("updated")
            .asString(null)

    // ---- actions

    override suspend fun execute(
        context: ConnectorContext,
        action: String,
        input: JsonNode
    ): JsonNode =
        when (action) {
            TRANSITION -> transition(context, input)
            REGISTER_WEBHOOK -> registerWebhook(context, input)
            REFRESH_WEBHOOKS -> send(context, HttpMethod.PUT, "${webhookApi(context)}/refresh", webhookIds(input)).json()
            DELETE_WEBHOOKS -> {
                send(context, HttpMethod.DELETE, webhookApi(context), webhookIds(input))
                nodes.objectNode().put("deleted", true)
            }
            else -> throw ConnectorException.Permanent("unknown jira action '$action'")
        }

    // input {"issueId", "transition": name or id}. the workflow decides which transitions exist from the current status
    private suspend fun transition(
        context: ConnectorContext,
        input: JsonNode
    ): JsonNode {
        val id = issueId(input.path("issueId").asString(""))
        val wanted = input.path("transition").asString("")
        if (wanted.isBlank()) throw ConnectorException.Permanent("transition is required", mapOf("field" to "transition"))
        val available = get(context, "${api(context)}/issue/$id/transitions").json().path("transitions").values()
        val match =
            available.firstOrNull { it.path("id").asString() == wanted }
                ?: available.firstOrNull { it.path("name").asString().equals(wanted, ignoreCase = true) }
                ?: throw ConnectorException.Permanent(
                    "transition not available from the issue's status",
                    mapOf("available" to available.joinToString(", ") { it.path("name").asString() })
                )
        val body = nodes.objectNode().set("transition", nodes.objectNode().put("id", match.path("id").asString()))
        send(context, HttpMethod.POST, "${api(context)}/issue/$id/transitions", body)
        return nodes
            .objectNode()
            .put("issueId", id)
            .put("transitionId", match.path("id").asString())
            .put("status", match.path("to").path("name").asString(null))
    }

    // input {"url", "jqlFilter", "events"?}. jira refuses per webhook inside a 200: those errors are not success
    private suspend fun registerWebhook(
        context: ConnectorContext,
        input: JsonNode
    ): JsonNode {
        val url = input.path("url").asString("")
        val jql = input.path("jqlFilter").asString("")
        if (!url.startsWith("https://")) throw ConnectorException.Permanent("url must be https", mapOf("field" to "url"))
        if (jql.isBlank()) throw ConnectorException.Permanent("jqlFilter is required", mapOf("field" to "jqlFilter"))
        val events =
            input
                .path("events")
                .values()
                .map { it.asString() }
                .ifEmpty { manifest.webhooks!!.events }
        val webhook = nodes.objectNode().put("jqlFilter", jql)
        webhook.set("events", nodes.arrayNode().apply { events.forEach { add(it) } })
        val body = nodes.objectNode().put("url", url).set("webhooks", nodes.arrayNode().add(webhook))
        val result =
            send(context, HttpMethod.POST, webhookApi(context), body)
                .json()
                .path("webhookRegistrationResult")
                .values()
                .firstOrNull()
        val created = result?.path("createdWebhookId")
        if (created == null || !created.isNumber) {
            val errors = result?.path("errors")?.values()?.joinToString("; ") { it.asString() } ?: "no registration result"
            throw ConnectorException.Permanent("jira refused the webhook", mapOf("errors" to errors))
        }
        return nodes.objectNode().put("webhookId", created.asLong())
    }

    private suspend fun webhookApi(context: ConnectorContext): String {
        // dynamic webhooks belong to connect and oauth 2.0 apps; an api token has none
        if (!gateway(context)) throw ConnectorException.Permanent("dynamic webhooks need an OAuth 2.0 connection")
        return "${api(context)}/webhook"
    }

    private fun webhookIds(input: JsonNode): ObjectNode {
        val ids = input.path("webhookIds").values().filter { it.isIntegralNumber }
        if (ids.isEmpty()) throw ConnectorException.Permanent("webhookIds is required", mapOf("field" to "webhookIds"))
        return nodes.objectNode().set("webhookIds", nodes.arrayNode().apply { ids.forEach { add(it.asLong()) } })
    }

    // ---- webhooks

    override fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification = webhooks.verify(request, secret)

    override fun parse(request: WebhookRequest) = webhooks.parse(request)

    // ---- plumbing

    private fun record(
        issue: JsonNode,
        schema: JiraIssueSchema
    ): ProviderRecord {
        val updated = issue.path("fields").path("updated").asString(null)
        return ProviderRecord(issue.path("id").asString(), updated, JiraTimes.parseOrNull(updated), JiraFields.read(issue, schema), issue)
    }

    private fun gateway(context: ConnectorContext) = context.metadata[ROUTE] == GATEWAY

    private suspend fun api(context: ConnectorContext): String =
        if (gateway(context)) {
            val id = cloudIds[context.connectionId] ?: site(context).path("id").asString().also { cloudIds[context.connectionId] = it }
            "${config.gatewayUrl}/ex/jira/$id/rest/api/3"
        } else {
            "${siteUrl()}/rest/api/3"
        }

    // the jira site of an oauth grant, from accessible-resources: the pinned one, or the only one. never a guess
    private suspend fun site(context: ConnectorContext): JsonNode {
        val sites =
            get(context, "${config.gatewayUrl}/oauth/token/accessible-resources")
                .json()
                .values()
                .filter { site -> JiraConfig.CLOUD_ID.matches(site.path("id").asString("")) && site.path("scopes").values().any { "jira" in it.asString() } }
        config.cloudId?.let { pinned ->
            return sites.firstOrNull { it.path("id").asString() == pinned }
                ?: throw ConnectorException.PermissionDenied("the grant does not reach the pinned Jira site")
        }
        return when (sites.size) {
            1 -> sites.single()
            0 -> throw ConnectorException.PermissionDenied("the grant reaches no Jira site")
            else -> throw ConnectorException.Permanent("the grant reaches ${sites.size} Jira sites; pin one with JiraConfig.cloudId")
        }
    }

    private fun siteUrl(): String = config.siteUrl?.trimEnd('/') ?: throw ConnectorException.Permanent("an api token connection needs JiraConfig.siteUrl")

    // jql dates are read in the searching user's zone (GET myself); utc when it names none
    private suspend fun zone(context: ConnectorContext): ZoneId =
        zones[context.connectionId] ?: run {
            val name = get(context, "${api(context)}/myself").json().path("timeZone").asString(null)
            (name?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC).also { zones[context.connectionId] = it }
        }

    private suspend fun get(
        context: ConnectorContext,
        url: String,
        query: Map<String, String> = emptyMap()
    ): ProviderResponse = checked(exchange(context, ProviderRequest(HttpMethod.GET, url, query = query)))

    private suspend fun send(
        context: ConnectorContext,
        method: HttpMethod,
        url: String,
        body: JsonNode
    ): ProviderResponse = checked(exchange(context, ProviderRequest.json(method, url, body)))

    private suspend fun exchange(
        context: ConnectorContext,
        request: ProviderRequest
    ): ProviderResponse = context.http.exchange(request.copy(headers = request.headers + ("Accept" to "application/json")))

    private fun checked(response: ProviderResponse): ProviderResponse {
        if (!response.successful) throw JiraErrors.classify(response)
        return response
    }

    // ids reach urls: issue ids are numbers, project keys letters, digits and underscores
    private fun issueId(id: String): String {
        if (!ISSUE_ID.matches(id)) throw ConnectorException.Permanent("not a jira issue id: '$id'")
        return id
    }

    private fun projectKey(key: String): String {
        if (!PROJECT_KEY.matches(key)) throw ConnectorException.Permanent("not a jira project key: '$key'")
        return key
    }

    companion object {
        const val ID = "jira"
        const val ROUTE = "route"
        const val GATEWAY = "gateway"

        const val TRANSITION = "transition_issue"
        const val REGISTER_WEBHOOK = "register_webhook"
        const val REFRESH_WEBHOOKS = "refresh_webhooks"
        const val DELETE_WEBHOOKS = "delete_webhooks"

        private val ISSUE_ID = Regex("""^\d{1,18}$""")
        private val PROJECT_KEY = Regex("""^[A-Za-z][A-Za-z0-9_]{0,254}$""")
    }
}

// jql string literals: double quoted, quote and backslash escaped. ids and keys are validated before they get here
internal object Jql {
    fun string(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
