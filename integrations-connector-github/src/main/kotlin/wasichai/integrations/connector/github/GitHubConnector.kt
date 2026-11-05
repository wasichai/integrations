package wasichai.integrations.connector.github

import tools.jackson.databind.JsonNode
import wasichai.integrations.sdk.AccountInfo
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
import wasichai.integrations.sdk.WebhookHandler
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookSupport
import wasichai.integrations.sdk.WebhookVerification
import wasichai.integrations.sdk.WriteResult
import java.time.temporal.ChronoUnit

// github (or github enterprise server) through rest api v3: a repository ("owner/repo") is the resource, its issues are
// the records, the issue number is the record id. pull requests, which the issues api also returns, are left out.
// deletions and transfers are not in the change feed: webhooks or reconciliation see them.
class GitHubConnector(
    private val config: GitHubConfig = GitHubConfig()
) : Connector,
    Authenticator,
    ResourceDiscovery,
    ResourceReader,
    ChangeFeed,
    ResourceWriter,
    WebhookHandler {
    override val manifest =
        ConnectorManifest(
            id = ID,
            provider = ProviderInfo("GitHub", "https://github.com", "https://docs.github.com/en/rest"),
            version = "0.1.0",
            sdkVersion = SdkVersion.CURRENT,
            description =
                "GitHub or GitHub Enterprise Server repositories and their issues through REST API v3 (pull requests left out): reads " +
                    "paged by Link header, an incremental feed on `updated_at` (at least once), create and edit (title, body, state, " +
                    "labels, assignees, milestone). The REST API cannot delete an issue: close it instead. Webhooks are hints, verified " +
                    "by X-Hub-Signature-256. Connect with an OAuth app or a GitHub App's user token, or a personal access token; " +
                    "GitHub App installation tokens are not supported.",
            authMethods =
                listOf(
                    AuthMethod.OAuth2AuthorizationCode(
                        authorizeUrl = "${config.web}/login/oauth/authorize",
                        tokenUrl = "${config.web}/login/oauth/access_token",
                        defaultScopes = config.defaultScopes,
                        // github documents pkce (S256) for oauth apps and github apps; a server without it ignores the
                        // extra parameters. github's revocation is not rfc 7009 (DELETE /applications/{id}/token): none
                        pkce = true
                    ),
                    // a personal access token, classic or fine-grained
                    AuthMethod.ApiKey(headerName = "Authorization", prefix = "Bearer ")
                ),
            capabilities =
                mapOf(
                    Capability.AUTHENTICATION to Maturity.TESTED,
                    Capability.DISCOVERY to Maturity.TESTED,
                    Capability.READ to Maturity.TESTED,
                    Capability.CHANGE_FEED to Maturity.TESTED,
                    Capability.WRITE to Maturity.TESTED,
                    // github's docs publish a secret, a payload and its signature: the tests check against them
                    Capability.WEBHOOKS to Maturity.TESTED
                ),
            resources = listOf(ResourceDeclaration("repository", "Repository", dynamic = true)),
            syncDirections = setOf(SyncDirectionSupport.SOURCE, SyncDirectionSupport.TARGET),
            pagination = setOf(PaginationStrategy.NEXT_URL),
            // 5,000 requests an hour per user token; 403/429 with reset or retry-after are mapped to RateLimited
            rateLimit = RateLimitHint(requestsPerSecond = 1.3, burst = 20, scope = "connection"),
            webhooks =
                WebhookSupport(
                    signatureScheme = "X-Hub-Signature-256: sha256=<hex hmac-sha256(raw body, webhook secret)>; X-GitHub-Delivery is the delivery id",
                    events = listOf("issues", "issue_comment")
                ),
            requiredPermissions = listOf("Issues: read and write", "Metadata: read")
        )

    // ---- authentication

    // the user id is stable; a login can be renamed
    override suspend fun accountInfo(context: ConnectorContext): AccountInfo {
        val user = get(context, "${config.api}/user").json()
        val login = user.path("login").asString(null)
        return AccountInfo(user.path("id").asString(), login, login?.let { mapOf("login" to it) }.orEmpty())
    }

    // ---- discovery

    // every repository the token reaches with issues enabled, paged by Link
    override suspend fun listResources(context: ConnectorContext): List<ResourceDescriptor> {
        val found = mutableListOf<ResourceDescriptor>()
        var response = get(context, "${config.api}/user/repos", mapOf("per_page" to "100", "sort" to "full_name"))
        while (true) {
            response
                .json()
                .values()
                .filter { it.path("has_issues").asBoolean(true) }
                .forEach { repo ->
                    val name = repo.path("full_name").asString()
                    found += ResourceDescriptor(name, name, repo.path("description").asString(null))
                }
            val next = next(response) ?: return found
            response = get(context, next)
        }
    }

    override suspend fun describe(
        context: ConnectorContext,
        resource: String
    ): ResourceSchema {
        val repo = repository(context, resource)
        return GitHubIssues.schema(resource, repo.path("full_name").asString(resource))
    }

    private suspend fun repository(
        context: ConnectorContext,
        resource: String
    ): JsonNode {
        val repo = get(context, repoUrl(resource)).json()
        if (!repo.path("has_issues").asBoolean(true)) throw ConnectorException.Permanent("issues are disabled in $resource")
        return repo
    }

    // ---- reads

    override suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord? {
        val response = exchange(context, ProviderRequest(HttpMethod.GET, issueUrl(resource, id)))
        return when (response.status) {
            // transferred to another repository: not a record of this resource anymore
            301 -> null
            // deleted (410) or absent (404), unless the repository itself is out of reach: lost access is an error,
            // never a mass deletion
            404, 410 -> {
                repository(context, resource)
                null
            }
            else -> checked(context, response).json().takeUnless(GitHubIssues::isPullRequest)?.let(::record)
        }
    }

    // oldest created first: new issues land on the last page, so page numbers do not shift under a full read
    override suspend fun list(
        context: ConnectorContext,
        request: ReadRequest
    ): RecordPage {
        val response =
            request.cursor?.let { cursor ->
                if (!config.isApiUrl(cursor)) throw ConnectorException.Permanent("GitHub cursor is not a url of the configured api")
                get(context, cursor)
            } ?: get(
                context,
                "${repoUrl(request.resource)}/issues",
                mapOf("state" to "all", "sort" to "created", "direction" to "asc", "per_page" to "${request.pageSize.coerceAtMost(100)}")
            )
        val records =
            response
                .json()
                .values()
                .filterNot(GitHubIssues::isPullRequest)
                .map(::record)
        return RecordPage(records, next(response))
    }

    // every call is a fresh query: the oldest-updated issues with updated_at >= since. the next since is the newest
    // updated_at seen minus one second, so the boundary second is read again (at least once; the engine drops repeats by
    // content hash) and an issue edited meanwhile is found by a later query instead of shifting github's pages.
    // only when a whole page sits within one second can since not move: then the pass pages by number (ADR-0019)
    override suspend fun changes(
        context: ConnectorContext,
        resource: String,
        checkpoint: String?,
        pageSize: Int
    ): ChangePage {
        val state = GitHubCheckpoint.parse(checkpoint)
        val query =
            mutableMapOf("state" to "all", "sort" to "updated", "direction" to "asc", "per_page" to "${pageSize.coerceIn(1, 100)}")
        state.since?.let { query["since"] = it.truncatedTo(ChronoUnit.SECONDS).toString() }
        if (state.page > 1) query["page"] = "${state.page}"
        val response = get(context, "${repoUrl(resource)}/issues", query)
        val items = response.json().values()
        // pull requests count for progress, they are just not records
        val newest = items.mapNotNull(GitHubIssues::updatedAt).maxOrNull()
        val changes = items.filterNot(GitHubIssues::isPullRequest).map { Change.Upserted(record(it)) }
        val candidate = newest?.minusSeconds(1)?.truncatedTo(ChronoUnit.SECONDS)
        val moved = candidate != null && (state.since == null || candidate.isAfter(state.since))
        val more = next(response) != null
        val nextState =
            when {
                more && moved -> GitHubCheckpoint(candidate)
                more -> GitHubCheckpoint(state.since, state.page + 1)
                else -> GitHubCheckpoint(if (moved) candidate else state.since)
            }
        return ChangePage(changes, nextState.render(), more)
    }

    // ---- writes

    // state can only be set by an edit: a create that asks for it is followed by one
    override suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        val stateFields = fields.filterKeys { it in STATE_FIELDS }
        val body = GitHubIssues.write(fields - STATE_FIELDS)
        // validated up front: a refused state must not leave a half-made issue behind
        val stateBody = GitHubIssues.write(stateFields)
        val created = send(context, HttpMethod.POST, "${repoUrl(resource)}/issues", body).json()
        val number = created.path("number").asString("")
        if (number.isEmpty()) throw ConnectorException.Permanent("create answered without a number")
        if (stateFields.isEmpty()) return written(created, created = true)
        val edited =
            try {
                send(context, HttpMethod.PATCH, issueUrl(resource, number), stateBody).json()
            } catch (e: ConnectorException) {
                // the issue exists: say so, never let a retry make a second one silently
                throw ConnectorException.Permanent("github issue #$number was created, setting its state failed: ${e.message}", mapOf("createdId" to number))
            }
        return written(edited, created = true)
    }

    // github has no conditional edit: with an expected version the issue is read first and compared on updated_at,
    // which has second precision. the gap is a known race, narrowed, not closed (as for notion and jira, ADR-0009)
    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): WriteResult {
        val body = GitHubIssues.write(fields)
        if (expectedVersion != null) {
            val current = get(context, issueUrl(resource, id)).json()
            if (GitHubIssues.isPullRequest(current)) throw ConnectorException.NotFound("#$id of $resource is a pull request")
            val version = current.path("updated_at").asString(null)
            if (version != expectedVersion) throw ConnectorException.Conflict("github issue #$id changed since $expectedVersion", version)
        }
        return written(send(context, HttpMethod.PATCH, issueUrl(resource, id), body).json(), created = false)
    }

    // the rest api has no issue delete (graphql's deleteIssue needs a repository admin): refused, never faked by closing
    override suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    ) {
        issueUrl(resource, id)
        throw ConnectorException.Permanent("GitHub's REST API cannot delete an issue; close it instead")
    }

    // a create or edit answers the whole issue: its updated_at is the version written
    private fun written(
        issue: JsonNode,
        created: Boolean
    ) = WriteResult(issue.path("number").asString(), issue.path("updated_at").asString(null), GitHubIssues.updatedAt(issue), created)

    // ---- webhooks

    override fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification = GitHubWebhooks.verify(request, secret)

    override fun parse(request: WebhookRequest) = GitHubWebhooks.parse(request)

    // ---- plumbing

    private fun record(issue: JsonNode) =
        ProviderRecord(issue.path("number").asString(), issue.path("updated_at").asString(null), GitHubIssues.updatedAt(issue), GitHubIssues.read(issue), issue)

    // a next link that leaves the configured api is not followed: the platform would send it the token
    private fun next(response: ProviderResponse): String? =
        GitHubLinks.next(response.header("Link"))?.also {
            if (!config.isApiUrl(it)) throw ConnectorException.Permanent("GitHub sent a next link outside the configured api")
        }

    private suspend fun get(
        context: ConnectorContext,
        url: String,
        query: Map<String, String> = emptyMap()
    ): ProviderResponse = checked(context, exchange(context, ProviderRequest(HttpMethod.GET, url, query = query)))

    private suspend fun send(
        context: ConnectorContext,
        method: HttpMethod,
        url: String,
        body: JsonNode
    ): ProviderResponse = checked(context, exchange(context, ProviderRequest.json(method, url, body)))

    private suspend fun exchange(
        context: ConnectorContext,
        request: ProviderRequest
    ): ProviderResponse = context.http.exchange(request.copy(headers = request.headers + HEADERS))

    private fun checked(
        context: ConnectorContext,
        response: ProviderResponse
    ): ProviderResponse {
        if (!response.successful) throw GitHubErrors.classify(response, context.clock.instant())
        return response
    }

    // names and numbers reach urls: validated, never encoded into something else
    private fun repoUrl(resource: String): String {
        if (!REPOSITORY.matches(resource) || resource.substringAfter('/') in setOf(".", "..")) {
            throw ConnectorException.Permanent("not a github repository (owner/name): '$resource'")
        }
        return "${config.api}/repos/$resource"
    }

    private fun issueUrl(
        resource: String,
        id: String
    ): String {
        if (!NUMBER.matches(id)) throw ConnectorException.Permanent("not a github issue number: '$id'")
        return "${repoUrl(resource)}/issues/$id"
    }

    companion object {
        const val ID = "github"

        // the rest api version the payloads were read against
        const val API_VERSION = "2022-11-28"

        private val HEADERS =
            mapOf(
                "Accept" to "application/vnd.github+json",
                "X-GitHub-Api-Version" to API_VERSION,
                // github refuses requests without a user agent
                "User-Agent" to "wasichai-integrations"
            )
        private val STATE_FIELDS = setOf("state", "state_reason")
        private val REPOSITORY = Regex("""^[A-Za-z0-9][A-Za-z0-9_-]{0,38}/[A-Za-z0-9._-]{1,100}$""")
        private val NUMBER = Regex("""^[1-9]\d{0,9}$""")
    }
}
