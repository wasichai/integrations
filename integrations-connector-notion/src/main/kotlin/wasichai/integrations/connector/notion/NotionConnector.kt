package wasichai.integrations.connector.notion

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.AccountInfo
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Authenticator
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.ChangeFeed
import wasichai.integrations.sdk.ChangePage
import wasichai.integrations.sdk.ClientAuthentication
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
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// notion: a data source is the resource, its pages are the records, page properties are the fields.
// block content (the page body) is not synchronised: the manifest does not claim it.
class NotionConnector(
    private val config: NotionConfig = NotionConfig()
) : Connector,
    Authenticator,
    ResourceDiscovery,
    ResourceReader,
    ChangeFeed,
    ResourceWriter,
    WebhookHandler {
    private val nodes = JsonNodeFactory.instance
    private val webhooks = NotionWebhooks()

    // describe answers per connection and data source; a schema edit in notion shows after a restart or eviction
    private val schemas = ConcurrentHashMap<String, ResourceSchema>()

    override val manifest =
        ConnectorManifest(
            id = "notion",
            provider = ProviderInfo("Notion", "https://www.notion.so", "https://developers.notion.com/reference/intro"),
            version = "0.1.0",
            sdkVersion = SdkVersion.CURRENT,
            description = "Notion data sources and their pages (properties only, no page body), with webhooks as change hints.",
            authMethods =
                listOf(
                    AuthMethod.OAuth2AuthorizationCode(
                        authorizeUrl = "${config.api}/oauth/authorize",
                        tokenUrl = "${config.api}/oauth/token",
                        // notion documents no pkce for public integrations; the client secret is required
                        pkce = false,
                        clientAuthentication = ClientAuthentication.BASIC
                    ),
                    // an internal integration token, for self-hosted single-workspace setups
                    AuthMethod.ApiKey()
                ),
            capabilities =
                mapOf(
                    Capability.AUTHENTICATION to Maturity.TESTED,
                    Capability.DISCOVERY to Maturity.TESTED,
                    Capability.READ to Maturity.TESTED,
                    Capability.CHANGE_FEED to Maturity.TESTED,
                    Capability.WRITE to Maturity.TESTED,
                    Capability.WEBHOOKS to Maturity.TESTED
                ),
            resources = listOf(ResourceDeclaration("data_source", "Data source", dynamic = true)),
            syncDirections = setOf(SyncDirectionSupport.SOURCE, SyncDirectionSupport.TARGET),
            pagination = setOf(PaginationStrategy.CURSOR),
            // documented average of three requests per second per integration, bursts allowed
            rateLimit = RateLimitHint(requestsPerSecond = 3.0, burst = 6, scope = "connection"),
            webhooks =
                WebhookSupport(
                    signatureScheme = "hmac-sha256 over the raw body, X-Notion-Signature: sha256=<hex>",
                    events = listOf("page.created", "page.properties_updated", "page.content_updated", "page.deleted", "page.undeleted")
                ),
            requiredPermissions = listOf("read content", "update content", "insert content")
        )

    override fun authorizationParameters(): Map<String, String> = mapOf("owner" to "user")

    // the token response names the workspace: that is the account. ids only, no secret
    override fun connectionMetadata(grant: TokenGrant): Map<String, String> =
        listOf("workspace_id", "workspace_name", "bot_id")
            .mapNotNull { key -> grant.raw[key]?.toString()?.let { key to it } }
            .toMap()

    override suspend fun accountInfo(context: ConnectorContext): AccountInfo {
        val me = call(context, ProviderRequest(HttpMethod.GET, "${config.api}/users/me")).json()
        val workspace = context.metadata["workspace_id"] ?: me.path("bot").path("workspace_id").textOrNull()
        val accountId = workspace ?: me.path("id").asString()
        val name = context.metadata["workspace_name"] ?: me.path("bot").path("workspace_name").textOrNull() ?: me.path("name").textOrNull()
        return AccountInfo(accountId, name, mapOf("bot_id" to me.path("id").asString()))
    }

    override suspend fun listResources(context: ConnectorContext): List<ResourceDescriptor> {
        val found = mutableListOf<ResourceDescriptor>()
        var cursor: String? = null
        do {
            val body =
                nodes
                    .objectNode()
                    .put("page_size", 100)
                    .set("filter", nodes.objectNode().put("property", "object").put("value", "data_source"))
            cursor?.let { body.put("start_cursor", it) }
            val page = call(context, post("${config.api}/search", body)).json()
            page.path("results").items().forEach { source ->
                found += ResourceDescriptor(source.path("id").asString(), plainText(source.path("title")).ifBlank { "Untitled" })
            }
            cursor = page.path("next_cursor").textOrNull()
        } while (page.path("has_more").asBoolean() && cursor != null)
        return found
    }

    override suspend fun describe(
        context: ConnectorContext,
        resource: String
    ): ResourceSchema {
        val source = call(context, ProviderRequest(HttpMethod.GET, "${config.api}/data_sources/${id(resource)}")).json()
        val fields =
            source
                .path("properties")
                .properties()
                .map { (name, property) -> NotionProperties.descriptor(name, property) }
                .sortedBy { it.name }
        val schema = ResourceSchema(resource, plainText(source.path("title")).ifBlank { "Untitled" }, fields)
        schemas[key(context, resource)] = schema
        return schema
    }

    override suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord? {
        val response = context.http.exchange(ProviderRequest(HttpMethod.GET, "${config.api}/pages/${id(id)}", headers()))
        if (response.status == 404) return null
        val page = checked(response).json()
        // a trashed page is gone for the sync: notion keeps it, the record does not exist anymore
        if (page.path("in_trash").asBoolean() || page.path("archived").asBoolean()) return null
        return record(page)
    }

    override suspend fun list(
        context: ConnectorContext,
        request: ReadRequest
    ): RecordPage {
        val body = nodes.objectNode().put("page_size", request.pageSize.coerceAtMost(100))
        request.cursor?.let { body.put("start_cursor", it) }
        val page = call(context, post("${config.api}/data_sources/${id(request.resource)}/query", body)).json()
        return RecordPage(page.path("results").items().map(::record), page.path("next_cursor").textOrNull()?.takeIf { page.path("has_more").asBoolean() })
    }

    // last_edited_time is rounded to the minute, so a pass asks "on or after" the last minute seen and the
    // same page can come twice; the sync engine drops repeats by content hash. trashed pages leave the query:
    // deletions arrive through webhooks or reconciliation, never through this feed.
    override suspend fun changes(
        context: ConnectorContext,
        resource: String,
        checkpoint: String?,
        pageSize: Int
    ): ChangePage {
        val state = NotionCheckpoint.parse(checkpoint)
        val body =
            nodes
                .objectNode()
                .put("page_size", pageSize.coerceIn(1, 100))
                .set(
                    "sorts",
                    nodes.arrayNode().add(nodes.objectNode().put("timestamp", "last_edited_time").put("direction", "ascending"))
                )
        state.since?.let { since ->
            body.set(
                "filter",
                nodes
                    .objectNode()
                    .put("timestamp", "last_edited_time")
                    .set("last_edited_time", nodes.objectNode().put("on_or_after", since.toString()))
            )
        }
        state.cursor?.let { body.put("start_cursor", it) }
        val page = call(context, post("${config.api}/data_sources/${id(resource)}/query", body)).json()
        val records = page.path("results").items().map(::record)
        val maxSeen = (records.mapNotNull { it.modifiedAt } + listOfNotNull(state.maxSeen)).maxOrNull()
        val hasMore = page.path("has_more").asBoolean() && page.path("next_cursor").textOrNull() != null
        val next =
            if (hasMore) {
                NotionCheckpoint(state.since, page.path("next_cursor").asString(), maxSeen)
            } else {
                // pass finished: the next one starts at the newest minute seen
                NotionCheckpoint(maxSeen ?: state.since, null, null)
            }
        return ChangePage(records.map { Change.Upserted(it) }, next.render(), hasMore)
    }

    override suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        val body =
            nodes
                .objectNode()
                .set("parent", nodes.objectNode().put("type", "data_source_id").put("data_source_id", id(resource)))
                .set("properties", properties(context, resource, fields))
        val page = call(context, post("${config.api}/pages", body)).json()
        return result(page, created = true)
    }

    // notion has no conditional write: with an expected version the page is read first and compared.
    // the gap between that read and the write is a known race, narrowed, not closed (ADR-0009).
    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): WriteResult {
        if (expectedVersion != null) {
            val current = read(context, resource, id) ?: throw ConnectorException.NotFound("notion page $id is gone")
            if (current.version != expectedVersion) {
                throw ConnectorException.Conflict("notion page $id changed since $expectedVersion", current.version)
            }
        }
        val body = nodes.objectNode().set("properties", properties(context, resource, fields))
        val page = call(context, ProviderRequest.json(HttpMethod.PATCH, "${config.api}/pages/${id(id)}", body, headers())).json()
        return result(page, created = false)
    }

    // notion deletes by moving to the trash
    override suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    ) {
        val body = nodes.objectNode().put("in_trash", true)
        call(context, ProviderRequest.json(HttpMethod.PATCH, "${config.api}/pages/${id(id)}", body, headers()))
    }

    override fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification = webhooks.verify(request, secret)

    override fun parse(request: WebhookRequest) = webhooks.parse(request)

    private suspend fun properties(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): ObjectNode {
        val schema = schemas[key(context, resource)] ?: describe(context, resource)
        val out = nodes.objectNode()
        fields.forEach { (name, value) ->
            val field = schema.field(name) ?: throw ConnectorException.Permanent("notion property '$name' does not exist", mapOf("field" to name))
            if (field.readOnly) throw ConnectorException.Permanent("notion property '$name' is read-only", mapOf("field" to name))
            out.set(name, NotionProperties.write(field.nativeType!!, value))
        }
        return out
    }

    private fun record(page: JsonNode): ProviderRecord {
        val edited = page.path("last_edited_time").textOrNull()
        val fields = page.path("properties").properties().associate { (name, property) -> name to NotionProperties.read(property) }
        return ProviderRecord(page.path("id").asString(), edited, edited?.let(Instant::parse), fields, page)
    }

    private fun result(
        page: JsonNode,
        created: Boolean
    ): WriteResult {
        val edited = page.path("last_edited_time").textOrNull()
        return WriteResult(page.path("id").asString(), edited, edited?.let(Instant::parse), created)
    }

    private suspend fun call(
        context: ConnectorContext,
        request: ProviderRequest
    ): ProviderResponse = checked(context.http.exchange(request.copy(headers = request.headers + headers())))

    private fun checked(response: ProviderResponse): ProviderResponse {
        if (!response.successful) throw NotionErrors.classify(response)
        return response
    }

    private fun post(
        url: String,
        body: JsonNode
    ) = ProviderRequest.json(HttpMethod.POST, url, body, headers())

    private fun headers() = mapOf("Notion-Version" to config.notionVersion)

    private fun key(
        context: ConnectorContext,
        resource: String
    ) = "${context.connectionId}/$resource"

    private fun plainText(richText: JsonNode): String = richText.items().joinToString("") { it.path("plain_text").asString() }

    // ids reach urls: notion ids are uuids with or without dashes, nothing else is accepted
    private fun id(value: String): String {
        if (!ID.matches(value)) throw ConnectorException.Permanent("not a notion id: '$value'")
        return value
    }

    companion object {
        private val ID = Regex("^[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}$")
    }
}

internal fun JsonNode.textOrNull(): String? = if (isNull || isMissingNode) null else asString()

// array elements as a list; anything else has none
internal fun JsonNode.items(): List<JsonNode> = if (isArray) (0 until size()).map { get(it) } else emptyList()
