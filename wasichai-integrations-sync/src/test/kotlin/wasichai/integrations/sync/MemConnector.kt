package wasichai.integrations.sync

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
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.ProviderInfo
import wasichai.integrations.sdk.ProviderRecord
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.RecordPage
import wasichai.integrations.sdk.ResourceDescriptor
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceSchema
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.SdkVersion
import wasichai.integrations.sdk.SyncDirectionSupport
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

// a provider in memory for sync tests: records per connection, versions, a change feed by sequence.
// tests change it directly (an edit "in the provider") and count what the engine wrote.
class MemConnector(
    id: String
) : Connector,
    Authenticator,
    ResourceDiscovery,
    ResourceReader,
    ChangeFeed,
    ResourceWriter {
    class Stored(
        val id: String,
        val fields: Map<String, FieldValue>,
        val version: Long,
        val modifiedAt: Instant,
        val seq: Long,
        val deleted: Boolean = false
    )

    private val seq = AtomicLong()
    private val data = ConcurrentHashMap<String, ConcurrentHashMap<String, Stored>>()
    val writes = ConcurrentHashMap<String, AtomicLong>()

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
                    Capability.READ to Maturity.TESTED,
                    Capability.CHANGE_FEED to Maturity.TESTED,
                    Capability.WRITE to Maturity.TESTED
                ),
            syncDirections = setOf(SyncDirectionSupport.SOURCE, SyncDirectionSupport.TARGET)
        )

    private fun table(
        connection: String,
        resource: String
    ) = data.computeIfAbsent("$connection/$resource") { ConcurrentHashMap() }

    // an edit made in the provider by a person
    fun put(
        connection: String,
        resource: String,
        fields: Map<String, FieldValue>,
        id: String = UUID.randomUUID().toString()
    ): String {
        val old = table(connection, resource)[id]
        table(connection, resource)[id] = Stored(id, (old?.fields ?: emptyMap()) + fields, (old?.version ?: 0) + 1, Instant.now(), seq.incrementAndGet())
        return id
    }

    fun remove(
        connection: String,
        resource: String,
        id: String
    ) {
        val old = table(connection, resource)[id] ?: return
        table(connection, resource)[id] = Stored(id, old.fields, old.version + 1, Instant.now(), seq.incrementAndGet(), deleted = true)
    }

    // edits behind the feed's back: the record changes or goes, and no change entry says so (what a reconcile must find)
    fun silentPut(
        connection: String,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>
    ) {
        val old = table(connection, resource)[id] ?: return
        table(connection, resource)[id] = Stored(id, old.fields + fields, old.version + 1, Instant.now(), old.seq)
    }

    fun vanish(
        connection: String,
        resource: String,
        id: String
    ) {
        table(connection, resource).remove(id)
    }

    // a checkpoint the provider no longer accepts (like an expired delta token): changes() refuses any checkpoint
    @Volatile
    var refuseCheckpoints = false

    fun records(
        connection: String,
        resource: String
    ): List<Stored> = table(connection, resource).values.filterNot { it.deleted }.sortedBy { it.seq }

    fun writes(connection: String): Long = writes[connection]?.get() ?: 0

    override suspend fun accountInfo(context: ConnectorContext) = AccountInfo("mem-account-${UUID.randomUUID()}")

    override suspend fun listResources(context: ConnectorContext) = listOf(ResourceDescriptor("contacts", "Contacts"))

    override suspend fun describe(
        context: ConnectorContext,
        resource: String
    ): ResourceSchema {
        if (resource != "contacts") throw ConnectorException.NotFound("no resource $resource")
        return ResourceSchema(
            resource,
            "Contacts",
            listOf(
                FieldDescriptor("name", "Name", FieldType.TEXT, required = true),
                FieldDescriptor("email", "Email", FieldType.TEXT),
                FieldDescriptor("tier", "Tier", FieldType.CHOICE, options = listOf("gold", "silver", "GOLD", "SILVER")),
                FieldDescriptor("amount", "Amount", FieldType.NUMBER),
                FieldDescriptor("since", "Since", FieldType.DATE),
                FieldDescriptor("computed", "Computed", FieldType.TEXT, readOnly = true)
            )
        )
    }

    override suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord? = table(context.connectionId, resource)[id]?.takeUnless { it.deleted }?.let(::record)

    override suspend fun list(
        context: ConnectorContext,
        request: ReadRequest
    ): RecordPage = RecordPage(records(context.connectionId, request.resource).map(::record), null)

    override suspend fun changes(
        context: ConnectorContext,
        resource: String,
        checkpoint: String?,
        pageSize: Int
    ): ChangePage {
        if (checkpoint != null && refuseCheckpoints) throw ConnectorException.Permanent("checkpoint $checkpoint expired")
        val after = checkpoint?.toLong() ?: 0
        val page = table(context.connectionId, resource).values.filter { it.seq > after }.sortedBy { it.seq }
        val taken = page.take(pageSize)
        val changes = taken.map { if (it.deleted) Change.Deleted(it.id, it.modifiedAt) else Change.Upserted(record(it)) }
        return ChangePage(changes, (taken.lastOrNull()?.seq ?: after).toString(), page.size > pageSize)
    }

    override suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): wasichai.integrations.sdk.WriteResult {
        count(context)
        val id = put(context.connectionId, resource, fields)
        val stored = table(context.connectionId, resource)[id]!!
        return wasichai.integrations.sdk.WriteResult(id, stored.version.toString(), stored.modifiedAt, true)
    }

    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): wasichai.integrations.sdk.WriteResult {
        val current = table(context.connectionId, resource)[id]?.takeUnless { it.deleted } ?: throw ConnectorException.NotFound("no record $id")
        if (expectedVersion != null && expectedVersion != current.version.toString()) {
            throw ConnectorException.Conflict("record $id moved on", current.version.toString())
        }
        count(context)
        put(context.connectionId, resource, fields, id)
        val stored = table(context.connectionId, resource)[id]!!
        return wasichai.integrations.sdk.WriteResult(id, stored.version.toString(), stored.modifiedAt, false)
    }

    override suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    ) {
        count(context)
        remove(context.connectionId, resource, id)
    }

    private fun count(context: ConnectorContext) {
        writes.computeIfAbsent(context.connectionId) { AtomicLong() }.incrementAndGet()
    }

    private fun record(s: Stored) = ProviderRecord(s.id, s.version.toString(), s.modifiedAt, s.fields + ("computed" to FieldValue.Text("c-${s.id.take(4)}")))
}
