package wasichai.integrations.connector.slack

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
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
import java.time.Clock
import java.time.Duration

// slack through the web api with a bot token: a channel the bot is in is the resource, the channel's history (top-level
// messages, and replies also sent to the channel) are the records, the message ts is the record id.
// polling sees new messages only: edits and deletions of older ones arrive through the events api or reconciliation
class SlackConnector(
    private val config: SlackConfig = SlackConfig(),
    clock: Clock = Clock.systemUTC()
) : Connector,
    Authenticator,
    ResourceDiscovery,
    ResourceReader,
    ChangeFeed,
    ResourceWriter,
    WebhookHandler {
    private val nodes = JsonNodeFactory.instance
    private val webhooks = SlackWebhooks(clock)

    override val manifest =
        ConnectorManifest(
            id = ID,
            provider = ProviderInfo("Slack", "https://slack.com", "https://docs.slack.dev/reference/methods"),
            version = "0.1.0",
            sdkVersion = SdkVersion.CURRENT,
            description =
                "Slack channels the app's bot is in, and their messages, through the Web API with a bot token: reads paged by " +
                    "cursor, an incremental feed of new messages by `oldest` (at least once; edits and deletions of older messages " +
                    "come through the Events API), post, edit and delete the bot's own messages as text. Events API deliveries are " +
                    "hints, verified by X-Slack-Signature. Connect with OAuth v2 (token rotation supported) or a bot token.",
            authMethods =
                listOf(
                    AuthMethod.OAuth2AuthorizationCode(
                        authorizeUrl = "${config.web}/oauth/v2/authorize",
                        tokenUrl = "${config.api}/oauth.v2.access",
                        defaultScopes = config.defaultScopes,
                        // slack's v2 flow authenticates the app by its client secret; auth.revoke is not rfc 7009
                        pkce = false,
                        scopeSeparator = ","
                    ),
                    // a bot token (xoxb-) from the app's install page, for one workspace
                    AuthMethod.ApiKey(headerName = "Authorization", prefix = "Bearer ")
                ),
            capabilities =
                mapOf(
                    Capability.AUTHENTICATION to Maturity.TESTED,
                    Capability.DISCOVERY to Maturity.TESTED,
                    Capability.READ to Maturity.TESTED,
                    Capability.CHANGE_FEED to Maturity.TESTED,
                    Capability.WRITE to Maturity.TESTED,
                    // slack's docs publish a signing secret, timestamp, body and signature: the tests check against them
                    Capability.WEBHOOKS to Maturity.TESTED
                ),
            resources = listOf(ResourceDeclaration("channel", "Channel", dynamic = true)),
            syncDirections = setOf(SyncDirectionSupport.SOURCE, SyncDirectionSupport.TARGET),
            pagination = setOf(PaginationStrategy.CURSOR),
            // per method and workspace: conversations.list tier 2 (20+/min), history tier 3 (50+/min), posting about one
            // message a second per channel. 429 with retry-after is mapped to RateLimited
            rateLimit = RateLimitHint(requestsPerSecond = 0.8, burst = 5, scope = "connection"),
            webhooks =
                WebhookSupport(
                    signatureScheme =
                        "X-Slack-Signature: v0=<hex hmac-sha256(\"v0:\" + X-Slack-Request-Timestamp + \":\" + raw body, signing secret)>, " +
                            "timestamp within five minutes; event_id is the delivery id",
                    events = listOf("message.channels", "message.groups")
                ),
            requiredPermissions = config.defaultScopes
        )

    // ---- authentication

    // the workspace is the account: team id, prefixed by the enterprise id on an enterprise grid
    override suspend fun accountInfo(context: ConnectorContext): AccountInfo {
        val auth = call(context, HttpMethod.GET, "auth.test")
        val team = auth.path("team_id").asString(null)?.takeIf { it.isNotEmpty() }
        val enterprise = auth.path("enterprise_id").asString(null)?.takeIf { it.isNotEmpty() }
        val id =
            listOfNotNull(enterprise, team).joinToString(":").ifEmpty {
                throw ConnectorException.Permanent("auth.test answered without a team or enterprise id")
            }
        val metadata = mutableMapOf<String, String>()
        team?.let { metadata["team_id"] = it }
        enterprise?.let { metadata["enterprise_id"] = it }
        auth.path("url").asString(null)?.let { metadata["url"] = it }
        auth.path("user_id").asString(null)?.let { metadata["bot_user_id"] = it }
        return AccountInfo(id, auth.path("team").asString(null), metadata)
    }

    // ---- discovery

    // public and private channels the bot is a member of: history is readable in those only
    override suspend fun listResources(context: ConnectorContext): List<ResourceDescriptor> {
        val found = mutableListOf<ResourceDescriptor>()
        var cursor: String? = null
        do {
            val query = mutableMapOf("types" to "public_channel,private_channel", "exclude_archived" to "true", "limit" to "200")
            cursor?.let { query["cursor"] = it }
            val page = call(context, HttpMethod.GET, "conversations.list", query)
            page
                .path("channels")
                .values()
                .filter { it.path("is_member").asBoolean(false) }
                .forEach { channel ->
                    val purpose =
                        channel
                            .path("purpose")
                            .path("value")
                            .asString(null)
                            ?.takeIf { it.isNotEmpty() }
                    found += ResourceDescriptor(channel.path("id").asString(), "#" + channel.path("name").asString(), purpose)
                }
            cursor = next(page)
        } while (cursor != null)
        return found
    }

    override suspend fun describe(
        context: ConnectorContext,
        resource: String
    ): ResourceSchema {
        val channel = call(context, HttpMethod.GET, "conversations.info", mapOf("channel" to channel(resource))).path("channel")
        return SlackMessages.schema(resource, channel.path("name").asString(null)?.let { "#$it" } ?: resource)
    }

    // ---- reads

    // slack's documented single-message read: the history between ts and ts, inclusive. an unknown channel or a
    // channel out of reach fails (NotFound, PermissionDenied): lost access is an error, never a mass deletion
    override suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord? = message(context, resource, id)?.let(::record)

    override suspend fun list(
        context: ConnectorContext,
        request: ReadRequest
    ): RecordPage {
        val query = mutableMapOf("channel" to channel(request.resource), "limit" to "${request.pageSize.coerceAtMost(MAX_LIMIT)}")
        request.cursor?.let { query["cursor"] = it }
        val page = call(context, HttpMethod.GET, "conversations.history", query)
        val records =
            page
                .path("messages")
                .values()
                .filterNot(SlackMessages::isTombstone)
                .map(::record)
        return RecordPage(records, next(page))
    }

    // a pass reads the history newer than `oldest` (inclusive), newest first, by slack's cursor; high keeps the newest
    // ts seen. at the end the next pass starts OVERLAP before it: slack promises no order of visibility, the repeats
    // are dropped by content hash (at least once). a message posted during a pass is newer than high: next pass.
    // a tombstone (deleted parent with replies) is a deletion; other edits and deletions of older messages are not
    // visible to polling at all (ADR-0020)
    override suspend fun changes(
        context: ConnectorContext,
        resource: String,
        checkpoint: String?,
        pageSize: Int
    ): ChangePage {
        val state = SlackCheckpoint.parse(checkpoint)
        val query = mutableMapOf("channel" to channel(resource), "limit" to "${pageSize.coerceIn(1, MAX_LIMIT)}")
        state.oldest?.let {
            query["oldest"] = it
            query["inclusive"] = "true"
        }
        state.cursor?.let { query["cursor"] = it }
        val page = call(context, HttpMethod.GET, "conversations.history", query)
        val messages = page.path("messages").values()
        val high = messages.mapNotNull { it.path("ts").asString(null)?.takeIf(SlackTs::isTs) }.fold(state.high, SlackTs::max)
        val changes =
            messages.mapNotNull { message ->
                val ts = message.path("ts").asString(null) ?: return@mapNotNull null
                if (SlackMessages.isTombstone(message)) Change.Deleted(ts, null) else Change.Upserted(record(message))
            }
        val cursor = next(page)
        val nextState =
            if (cursor != null) {
                SlackCheckpoint(state.oldest, cursor, high)
            } else {
                // never backwards: oldest only moves on
                SlackCheckpoint(SlackTs.max(state.oldest, high?.let { SlackTs.minus(it, OVERLAP) }))
            }
        return ChangePage(changes, nextState.render(), cursor != null)
    }

    // ---- writes

    // posted as the bot, top level of the channel
    override suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        val body = nodes.objectNode().put("channel", channel(resource)).put("text", SlackMessages.text(fields))
        val posted = call(context, HttpMethod.POST, "chat.postMessage", body = body)
        val ts = posted.path("ts").asString(null)?.takeIf(SlackTs::isTs) ?: throw ConnectorException.Permanent("chat.postMessage answered without a ts")
        return WriteResult(ts, ts, SlackTs.instant(ts), created = true)
    }

    // slack has no conditional edit: with an expected version the message is read first and compared on edited.ts
    // (its ts when never edited). the gap is a known race, narrowed, not closed (as for github and jira, ADR-0009).
    // chat.update does not answer edited.ts: the message is read back so the platform stores what it wrote
    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): WriteResult {
        val text = SlackMessages.text(fields)
        if (expectedVersion != null) {
            val current = message(context, resource, id) ?: throw ConnectorException.NotFound("slack message $id is gone")
            val version = SlackMessages.version(current)
            if (version != expectedVersion) throw ConnectorException.Conflict("slack message $id changed since $expectedVersion", version)
        }
        call(
            context,
            HttpMethod.POST,
            "chat.update",
            body =
                nodes
                    .objectNode()
                    .put("channel", channel(resource))
                    .put("ts", ts(id))
                    .put("text", text)
        )
        val after = message(context, resource, id)
        return WriteResult(id, after?.let(SlackMessages::version), after?.let(SlackMessages::modifiedAt), created = false)
    }

    // a bot token deletes the bot's own messages only (cant_delete_message otherwise, PermissionDenied)
    override suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    ) {
        call(context, HttpMethod.POST, "chat.delete", body = nodes.objectNode().put("channel", channel(resource)).put("ts", ts(id)))
    }

    // ---- webhooks

    override fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification = webhooks.verify(request, secret)

    override fun parse(request: WebhookRequest) = webhooks.parse(request)

    // ---- plumbing

    private suspend fun message(
        context: ConnectorContext,
        resource: String,
        id: String
    ): JsonNode? {
        val query = mapOf("channel" to channel(resource), "latest" to ts(id), "oldest" to id, "inclusive" to "true", "limit" to "1")
        return call(context, HttpMethod.GET, "conversations.history", query)
            .path("messages")
            .values()
            .firstOrNull { it.path("ts").asString(null) == id && !SlackMessages.isTombstone(it) }
    }

    private fun record(message: JsonNode) =
        ProviderRecord(message.path("ts").asString(), SlackMessages.version(message), SlackMessages.modifiedAt(message), SlackMessages.read(message), message)

    // an empty next_cursor is the end
    private fun next(page: JsonNode): String? =
        page
            .path("response_metadata")
            .path("next_cursor")
            .asString(null)
            ?.takeIf { it.isNotEmpty() }

    // reads are GET with query arguments, writes POST json; both carry the token in the Authorization header
    private suspend fun call(
        context: ConnectorContext,
        method: HttpMethod,
        name: String,
        query: Map<String, String> = emptyMap(),
        body: JsonNode? = null
    ): JsonNode {
        val request =
            ProviderRequest(
                method,
                "${config.api}/$name",
                query = query,
                body = body?.let { SlackMessages.json.writeValueAsBytes(it) },
                // without the charset slack answers with a missing_charset warning
                contentType = body?.let { "application/json; charset=utf-8" }
            )
        val response = context.http.exchange(request)
        if (!response.successful) throw SlackErrors.http(response)
        val json = runCatching { response.json() }.getOrNull() ?: throw ConnectorException.Transient("slack answered ${response.status} without json")
        if (!json.path("ok").asBoolean(false)) throw SlackErrors.api(json, response)
        return json
    }

    // ids reach queries and bodies: validated, never passed through as something else
    private fun channel(resource: String): String {
        if (!CHANNEL.matches(resource)) throw ConnectorException.Permanent("not a slack channel id: '$resource'")
        return resource
    }

    private fun ts(id: String): String {
        if (!SlackTs.isTs(id)) throw ConnectorException.Permanent("not a slack message ts: '$id'")
        return id
    }

    companion object {
        const val ID = "slack"

        // slack accepts up to 999 a page
        private const val MAX_LIMIT = 999
        private val OVERLAP: Duration = Duration.ofSeconds(10)
        private val CHANNEL = Regex("""^[CG][A-Z0-9]{2,20}$""")
    }
}
