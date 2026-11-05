package wasichai.integrations.workflows

import tools.jackson.databind.JsonNode
import wasichai.integrations.sdk.AccountInfo
import wasichai.integrations.sdk.ActionDeclaration
import wasichai.integrations.sdk.ActionExecutor
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Authenticator
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.ConnectorManifest
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.ProviderEvent
import wasichai.integrations.sdk.ProviderInfo
import wasichai.integrations.sdk.ResourceDeclaration
import wasichai.integrations.sdk.ResourceDescriptor
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceSchema
import wasichai.integrations.sdk.ResourceUpserter
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.SdkVersion
import wasichai.integrations.sdk.WebhookHandler
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookSupport
import wasichai.integrations.sdk.WebhookVerification
import wasichai.integrations.sdk.WriteResult
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// a minimal in-memory provider for workflow tests (after the sync module's MemConnector): one described
// resource; writes are recorded per connection, so a test can count them (a step repeated would show)
open class MemConnector(
    id: String,
    upserts: Boolean = false
) : Connector,
    Authenticator,
    ResourceDiscovery,
    ResourceWriter {
    override val manifest =
        ConnectorManifest(
            id = id,
            provider = ProviderInfo("Memory $id"),
            version = "1.0.0",
            sdkVersion = SdkVersion.CURRENT,
            description = "in-memory test provider",
            authMethods = listOf(AuthMethod.ApiKey()),
            capabilities =
                mapOf(
                    Capability.AUTHENTICATION to Maturity.TESTED,
                    Capability.DISCOVERY to Maturity.TESTED,
                    Capability.WRITE to Maturity.TESTED
                ) + (if (upserts) mapOf(Capability.UPSERT to Maturity.TESTED) else emptyMap()),
            resources = listOf(ResourceDeclaration("contacts", "Contacts"), ResourceDeclaration("ghosts", "Ghosts"))
        )

    override suspend fun accountInfo(context: ConnectorContext) = AccountInfo("mem-account-${UUID.randomUUID()}")

    // per connection: (operation, resource, fields)
    private val log = ConcurrentHashMap<String, MutableList<Triple<String, String, Map<String, FieldValue>>>>()

    fun writes(connection: String): List<Triple<String, String, Map<String, FieldValue>>> = log[connection].orEmpty().toList()

    protected fun record(
        context: ConnectorContext,
        operation: String,
        resource: String,
        fields: Map<String, FieldValue>
    ) {
        log.computeIfAbsent(context.connectionId) { java.util.Collections.synchronizedList(mutableListOf()) }.add(Triple(operation, resource, fields))
    }

    override suspend fun listResources(context: ConnectorContext) = listOf(ResourceDescriptor("contacts", "Contacts"))

    // "ghosts" is declared but gone at the provider: what describe-time validation must catch
    override suspend fun describe(
        context: ConnectorContext,
        resource: String
    ): ResourceSchema {
        if (resource != "contacts") throw ConnectorException.NotFound("no resource $resource")
        return SCHEMA
    }

    override suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        // a name "fail:permanent" fails the create; "fail:<n>" fails it transiently n times, then it works
        when (val fail = (fields["name"] as? FieldValue.Text)?.value?.takeIf { it.startsWith("fail:") }?.removePrefix("fail:")) {
            "permanent" -> throw ConnectorException.Permanent("refused by the provider")
            null -> Unit
            else -> if (transientFailures.merge(context.connectionId + fail, 1, Int::plus)!! <= fail.toInt()) throw ConnectorException.Transient("try later")
        }
        record(context, "create", resource, fields)
        return WriteResult(UUID.randomUUID().toString(), "1", Instant.now(), true)
    }

    private val transientFailures = ConcurrentHashMap<String, Int>()

    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): WriteResult {
        record(context, "update", resource, fields)
        return WriteResult(id, "2", Instant.now(), false)
    }

    override suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    ) = record(context, "delete", resource, mapOf("id" to FieldValue.Text(id)))

    companion object {
        val SCHEMA =
            ResourceSchema(
                "contacts",
                "Contacts",
                listOf(
                    FieldDescriptor("name", "Name", FieldType.TEXT, required = true),
                    FieldDescriptor("email", "Email", FieldType.TEXT),
                    FieldDescriptor("tier", "Tier", FieldType.CHOICE, options = listOf("gold", "silver")),
                    FieldDescriptor("amount", "Amount", FieldType.NUMBER),
                    FieldDescriptor("since", "Since", FieldType.DATE),
                    FieldDescriptor("seenAt", "Seen at", FieldType.DATETIME),
                    FieldDescriptor("vip", "VIP", FieldType.BOOLEAN),
                    FieldDescriptor("computed", "Computed", FieldType.TEXT, readOnly = true),
                    FieldDescriptor("extId", "External id", FieldType.TEXT, externalId = true)
                )
            )
    }
}

class MemUpserter(
    id: String
) : MemConnector(id, upserts = true),
    ResourceUpserter {
    override suspend fun upsert(
        context: ConnectorContext,
        resource: String,
        externalIdField: String,
        externalId: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        record(context, "upsert", resource, fields + (externalIdField to FieldValue.Text(externalId)))
        return WriteResult(externalId, "1", Instant.now(), true)
    }
}

// a provider with actions and webhook events, no records
class MemMessenger(
    id: String
) : Connector,
    Authenticator,
    ActionExecutor,
    WebhookHandler {
    override val manifest =
        ConnectorManifest(
            id = id,
            provider = ProviderInfo("Memory $id"),
            version = "1.0.0",
            sdkVersion = SdkVersion.CURRENT,
            description = "in-memory messenger",
            authMethods = listOf(AuthMethod.ApiKey()),
            capabilities =
                mapOf(
                    Capability.AUTHENTICATION to Maturity.TESTED,
                    Capability.ACTIONS to Maturity.TESTED,
                    Capability.WEBHOOKS to Maturity.TESTED
                ),
            actions = listOf(ActionDeclaration("send_message", "Send a message")),
            webhooks = WebhookSupport("none", listOf("message.created"))
        )

    override suspend fun accountInfo(context: ConnectorContext) = AccountInfo("mem-account-${UUID.randomUUID()}")

    // per connection: the inputs it was called with
    private val calls = ConcurrentHashMap<String, MutableList<JsonNode>>()

    fun calls(connection: String): List<JsonNode> = calls[connection].orEmpty().toList()

    // an input "fail": "permanent" fails the action; the action answers its input otherwise
    override suspend fun execute(
        context: ConnectorContext,
        action: String,
        input: JsonNode
    ): JsonNode {
        if (input.path("fail").asString("") == "permanent") throw ConnectorException.Permanent("refused by the provider")
        calls.computeIfAbsent(context.connectionId) { java.util.Collections.synchronizedList(mutableListOf()) }.add(input)
        return input
    }

    override fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification = WebhookVerification.Valid

    override fun parse(request: WebhookRequest): List<ProviderEvent> = emptyList()
}
