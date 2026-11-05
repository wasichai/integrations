package wasichai.integrations.ai

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
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
import wasichai.integrations.sdk.ProviderInfo
import wasichai.integrations.sdk.ProviderRecord
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.RecordPage
import wasichai.integrations.sdk.ResourceDeclaration
import wasichai.integrations.sdk.ResourceDescriptor
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceSchema
import wasichai.integrations.sdk.ResourceUpserter
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.SdkVersion
import wasichai.integrations.sdk.WriteResult
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// in-memory providers for the AI tool tests: records per connection, and actions that count their calls
class MemRecords(
    id: String
) : Connector,
    Authenticator,
    ResourceDiscovery,
    ResourceReader,
    ResourceWriter,
    ResourceUpserter {
    override val manifest =
        ConnectorManifest(
            id = id,
            provider = ProviderInfo("Memory $id"),
            version = "1.0.0",
            sdkVersion = SdkVersion.CURRENT,
            description = "in-memory records",
            authMethods = listOf(AuthMethod.ApiKey()),
            capabilities =
                mapOf(
                    Capability.AUTHENTICATION to Maturity.TESTED,
                    Capability.DISCOVERY to Maturity.TESTED,
                    Capability.READ to Maturity.TESTED,
                    Capability.WRITE to Maturity.TESTED,
                    Capability.UPSERT to Maturity.TESTED
                ),
            resources = listOf(ResourceDeclaration("contacts", "Contacts"), ResourceDeclaration("ghosts", "Ghosts"))
        )

    // connection id -> record id -> fields
    val data = ConcurrentHashMap<String, ConcurrentHashMap<String, Map<String, FieldValue>>>()

    private fun of(context: ConnectorContext) = data.getOrPut(context.connectionId) { ConcurrentHashMap() }

    override suspend fun accountInfo(context: ConnectorContext) = AccountInfo("mem-account-${UUID.randomUUID()}")

    override suspend fun listResources(context: ConnectorContext) = listOf(ResourceDescriptor("contacts", "Contacts"))

    override suspend fun describe(
        context: ConnectorContext,
        resource: String
    ): ResourceSchema {
        if (resource != "contacts") throw ConnectorException.NotFound("no resource $resource")
        return SCHEMA
    }

    override suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord? = of(context)[id]?.let { ProviderRecord(id, "1", null, it) }

    override suspend fun list(
        context: ConnectorContext,
        request: ReadRequest
    ): RecordPage = RecordPage(of(context).entries.sortedBy { it.key }.map { ProviderRecord(it.key, "1", null, it.value) }, null)

    override suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        val id = UUID.randomUUID().toString()
        of(context)[id] = fields
        return WriteResult(id, "1", Instant.now(), true)
    }

    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): WriteResult {
        val old = of(context)[id] ?: throw ConnectorException.NotFound("no record $id")
        of(context)[id] = old + fields
        return WriteResult(id, "2", Instant.now(), false)
    }

    override suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    ) {
        of(context).remove(id)
    }

    override suspend fun upsert(
        context: ConnectorContext,
        resource: String,
        externalIdField: String,
        externalId: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        of(context)[externalId] = fields + (externalIdField to FieldValue.Text(externalId))
        return WriteResult(externalId, "1", Instant.now(), true)
    }

    companion object {
        val SCHEMA =
            ResourceSchema(
                "contacts",
                "Contacts",
                listOf(
                    FieldDescriptor("name", "Name", FieldType.TEXT, required = true),
                    FieldDescriptor("tier", "Tier", FieldType.CHOICE, options = listOf("gold", "silver")),
                    FieldDescriptor("amount", "Amount", FieldType.NUMBER),
                    FieldDescriptor("since", "Since", FieldType.DATE),
                    FieldDescriptor("computed", "Computed", FieldType.TEXT, readOnly = true),
                    FieldDescriptor("extId", "External id", FieldType.TEXT, externalId = true)
                )
            )
    }
}

// actions: send_message answers with what it sent plus a provider token (masked when stored); fail always fails
class MemMessenger(
    id: String
) : Connector,
    Authenticator,
    ActionExecutor {
    override val manifest =
        ConnectorManifest(
            id = id,
            provider = ProviderInfo("Memory $id"),
            version = "1.0.0",
            sdkVersion = SdkVersion.CURRENT,
            description = "in-memory messenger",
            authMethods = listOf(AuthMethod.ApiKey()),
            capabilities = mapOf(Capability.AUTHENTICATION to Maturity.TESTED, Capability.ACTIONS to Maturity.TESTED),
            actions = listOf(ActionDeclaration("send_message", "Send a message"), ActionDeclaration("fail", "Always fails"))
        )

    val calls = AtomicInteger()
    private val json = JsonMapper.builder().build()

    override suspend fun accountInfo(context: ConnectorContext) = AccountInfo("mem-account-${UUID.randomUUID()}")

    override suspend fun execute(
        context: ConnectorContext,
        action: String,
        input: JsonNode
    ): JsonNode {
        calls.incrementAndGet()
        if (action == "fail") throw ConnectorException.Permanent("channel_not_found")
        return json
            .createObjectNode()
            .put("ok", true)
            .put("sent", input.get("text")?.asString())
            .also { it.putObject("auth").put("accessToken", PROVIDER_TOKEN) }
    }

    companion object {
        const val PROVIDER_TOKEN = "xoxb-provider-token-SECRET"
    }
}
