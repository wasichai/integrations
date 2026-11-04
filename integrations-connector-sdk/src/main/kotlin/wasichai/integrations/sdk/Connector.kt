package wasichai.integrations.sdk

import tools.jackson.databind.JsonNode
import java.time.Clock

// a connector is its manifest plus the optional capability interfaces it implements
interface Connector {
    val manifest: ConnectorManifest
}

// what the platform hands a connector for one call on one connection
interface ConnectorContext {
    val connectionId: String
    val http: ProviderHttp

    // non-secret connection metadata the connector kept at connect time (an instance url, a workspace id)
    val metadata: Map<String, String>
    val clock: Clock
}

interface Authenticator {
    // after the flow: who is this account. called again by "test connection"
    suspend fun accountInfo(context: ConnectorContext): AccountInfo

    // extra non-secret metadata from the token response (an instance url). never return a secret here
    fun connectionMetadata(grant: TokenGrant): Map<String, String> = emptyMap()

    // extra query parameters on the authorize url (a provider-specific prompt or owner parameter)
    fun authorizationParameters(): Map<String, String> = emptyMap()
}

interface ResourceDiscovery {
    suspend fun listResources(context: ConnectorContext): List<ResourceDescriptor>

    suspend fun describe(
        context: ConnectorContext,
        resource: String
    ): ResourceSchema
}

interface ResourceReader {
    suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord?

    suspend fun list(
        context: ConnectorContext,
        request: ReadRequest
    ): RecordPage
}

// incremental reads: what changed since the checkpoint the connector itself returned last time
interface ChangeFeed {
    suspend fun changes(
        context: ConnectorContext,
        resource: String,
        checkpoint: String?,
        pageSize: Int = 100
    ): ChangePage
}

interface ResourceWriter {
    suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): WriteResult

    // expectedVersion: fail with Conflict when the record moved on, where the provider can check it
    suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String? = null
    ): WriteResult

    suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    )
}

// idempotent create-or-update keyed by a provider external id field
interface ResourceUpserter {
    suspend fun upsert(
        context: ConnectorContext,
        resource: String,
        externalIdField: String,
        externalId: String,
        fields: Map<String, FieldValue>
    ): WriteResult
}

interface ActionExecutor {
    suspend fun execute(
        context: ConnectorContext,
        action: String,
        input: JsonNode
    ): JsonNode
}
