package wasichai.integrations

import kotlinx.coroutines.reactive.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.platform.WasichaiSchemas
import java.time.Clock
import java.time.Instant
import java.util.UUID

// a module's part of a connection's export (ADR-0029): section name -> the rows of one page, offset..offset+limit-1 in a
// stable order. rows are what is stored, column for column, minus secrets: a sealed value becomes a "set" flag
interface ExportContributor {
    suspend fun export(
        organizationId: UUID,
        connectionId: UUID,
        offset: Long,
        limit: Int
    ): Map<String, List<JsonNode>>
}

// a module's part of an erasure (ADR-0029). both run inside the erasure's one transaction, before the core deletes
// its own rows; they answer table -> rows deleted (or, for a record, changed)
interface ErasureContributor {
    // everything the module keeps that stands on the connection
    suspend fun eraseConnection(
        organizationId: UUID,
        connectionId: UUID
    ): Map<String, Long>

    // what the module keeps of one provider record of the connection
    suspend fun eraseRecord(
        organizationId: UUID,
        connectionId: UUID,
        resource: String,
        recordId: String
    ): Map<String, Long>
}

// sql helpers for contributors: :org and :connection are bound, plus the named values given
object ConnectionDataSql {
    // the statement selects one json text column named row, ordered; the page is appended
    suspend fun rows(
        db: DatabaseClient,
        json: JsonMapper,
        sql: String,
        organizationId: UUID,
        connectionId: UUID,
        offset: Long,
        limit: Int
    ): List<JsonNode> =
        db
            .sql("$sql OFFSET :offset LIMIT :limit")
            .bind("org", organizationId)
            .bind("connection", connectionId)
            .bind("offset", offset)
            .bind("limit", limit)
            .map { row, _ -> json.readTree(row.get("row", String::class.java) ?: "null") }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun update(
        db: DatabaseClient,
        sql: String,
        organizationId: UUID,
        connectionId: UUID,
        values: Map<String, Any> = emptyMap()
    ): Long =
        db
            .sql(sql)
            .bind("org", organizationId)
            .bind("connection", connectionId)
            .let { spec -> values.entries.fold(spec) { s, (k, v) -> s.bind(k, v) } }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
}

data class CredentialFlags(
    val stored: Boolean,
    // false when no configured key opens them: the flags below are then unknown (false)
    val readable: Boolean,
    val accessToken: Boolean,
    val refreshToken: Boolean,
    val apiKey: Boolean,
    val username: Boolean,
    val password: Boolean,
    val accessExpiresAt: Instant?
)

data class ConnectionExport(
    val connectionId: UUID,
    val exportedAt: Instant,
    val page: Int,
    val size: Int,
    // a section has more rows: ask for page + 1
    val hasMore: Boolean,
    val connection: ConnectionView,
    val credentials: CredentialFlags,
    val sections: Map<String, List<JsonNode>>
)

data class EraseRecordRequest(
    val resource: String? = null,
    val recordId: String? = null
)

data class ErasureView(
    val connectionId: UUID,
    // set when one record was erased
    val resource: String?,
    val recordId: String?,
    // table -> rows deleted or changed
    val erased: Map<String, Long>,
    // erasing a connection revokes its oauth token when the connector declares a revoke url (best effort)
    val revoked: Boolean?,
    val erasedAt: Instant
)

// GDPR export and erasure of a connection (ADR-0029): a manager's actions, on a connection of the caller's tenant only
class ConnectionDataService(
    private val access: IntegrationsAccess,
    private val connections: ConnectionRepository,
    private val credentials: ConnectionCredentials,
    private val connectionService: ConnectionService,
    private val exporters: List<ExportContributor>,
    private val erasers: List<ErasureContributor>,
    private val audit: AuditService,
    private val tx: Transactions,
    private val pageSize: Int,
    private val maxPageSize: Int,
    private val clock: Clock
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun export(
        id: UUID,
        page: Int?,
        size: Int?
    ): ConnectionExport {
        val user = access.manager()
        val connection = connections.find(user.organizationId, id) ?: throw NotFoundException("Connection $id not found")
        val p = page ?: 0
        val s = size ?: pageSize
        val violations = mutableListOf<FieldViolation>()
        if (p < 0) violations += FieldViolation("page", "must not be negative")
        if (s !in 1..maxPageSize) violations += FieldViolation("size", "1 to $maxPageSize")
        if (violations.isNotEmpty()) throw ValidationException("Invalid page", violations)
        // one row more than asked tells whether a section goes on
        val all =
            exporters.fold(
                linkedMapOf<String, List<JsonNode>>()
            ) { acc, e -> acc.apply { putAll(e.export(user.organizationId, id, p.toLong() * s, s + 1)) } }
        return ConnectionExport(
            id,
            clock.instant(),
            p,
            s,
            all.values.any { it.size > s },
            connectionService.view(connection),
            flags(connection),
            all.mapValues { (_, rows) -> rows.take(s) }
        )
    }

    // the connection and everything derived from it, in every module, in one transaction; then the token is revoked
    suspend fun erase(id: UUID): ErasureView {
        val user = access.manager()
        val connection = connections.find(user.organizationId, id) ?: throw NotFoundException("Connection $id not found")
        // read before the rows go: the revoke runs after the commit
        val stored = runCatching { connections.credentials(user.organizationId, id) }.getOrNull()
        val erased = linkedMapOf<String, Long>()
        tx.run {
            erasers.forEach { add(erased, it.eraseConnection(user.organizationId, id)) }
            if (!connections.delete(user.organizationId, id)) throw NotFoundException("Connection $id not found")
            erased["integration_connections"] = 1
            audit.record(
                user.organizationId,
                user.userId,
                AUDIT_OBJECT,
                id,
                AuditOperation.DELETE,
                before = mapOf("connectorId" to connection.connectorId, "name" to connection.name, "providerAccountId" to connection.providerAccountId),
                after = mapOf("erased" to erased),
                reason = "GDPR erasure of the connection and its derived data"
            )
        }
        val revoked = credentials.revoke(connection, stored)
        log.info("integrations: connection {} erased with its derived data: {}", id, erased)
        return ErasureView(id, null, null, erased, revoked, clock.instant())
    }

    suspend fun eraseRecord(
        id: UUID,
        request: EraseRecordRequest
    ): ErasureView {
        val user = access.manager()
        val violations = mutableListOf<FieldViolation>()
        val resource = request.resource?.trim().orEmpty()
        val recordId = request.recordId?.trim().orEmpty()
        if (resource.isEmpty() || resource.length > 200) violations += FieldViolation("resource", "1 to 200 characters")
        if (recordId.isEmpty() || recordId.length > 500) violations += FieldViolation("recordId", "1 to 500 characters")
        if (violations.isNotEmpty()) throw ValidationException("Invalid record", violations)
        connections.find(user.organizationId, id) ?: throw NotFoundException("Connection $id not found")
        val erased = linkedMapOf<String, Long>()
        tx.run {
            erasers.forEach { add(erased, it.eraseRecord(user.organizationId, id, resource, recordId)) }
            // the record id is the personal datum: the audit names the connection and the resource only
            audit.record(
                user.organizationId,
                user.userId,
                AUDIT_RECORD_OBJECT,
                id,
                AuditOperation.DELETE,
                before = mapOf("resource" to resource),
                after = mapOf("erased" to erased),
                reason = "GDPR erasure of one provider record"
            )
        }
        log.info("integrations: a record of {} on connection {} erased: {}", resource, id, erased)
        return ErasureView(id, resource, recordId, erased, null, clock.instant())
    }

    private suspend fun flags(connection: Connection): CredentialFlags =
        try {
            val stored = connections.credentials(connection.organizationId, connection.id)
            val s = stored?.secrets
            CredentialFlags(
                stored != null,
                stored != null,
                s?.accessToken != null,
                s?.refreshToken != null,
                s?.apiKey != null,
                s?.username != null,
                s?.password != null,
                stored?.accessExpiresAt
            )
        } catch (_: CredentialUnreadableException) {
            CredentialFlags(true, false, false, false, false, false, false, null)
        }

    private fun add(
        into: MutableMap<String, Long>,
        counts: Map<String, Long>
    ) = counts.forEach { (k, v) -> into.merge(k, v, Long::plus) }

    companion object {
        const val AUDIT_OBJECT = "integration_connection"
        const val AUDIT_RECORD_OBJECT = "integration_connection_record"
    }
}

// the core's own tables of a connection: webhook endpoints and events. usage counters are the tenant's, not a connection's
class CoreConnectionData(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) : ExportContributor,
    ErasureContributor {
    private val m = schemas.metadata

    override suspend fun export(
        organizationId: UUID,
        connectionId: UUID,
        offset: Long,
        limit: Int
    ): Map<String, List<JsonNode>> =
        mapOf(
            "webhookEndpoints" to
                rows(
                    "SELECT ((to_jsonb(t) - 'secret_sealed') || jsonb_build_object('secret_set', t.secret_sealed IS NOT NULL))::text AS row " +
                        "FROM $m.integration_webhook_endpoints t WHERE t.organization_id = :org AND t.connection_id = :connection ORDER BY t.id",
                    organizationId,
                    connectionId,
                    offset,
                    limit
                ),
            "events" to
                rows(
                    "SELECT to_jsonb(t)::text AS row FROM $m.integration_events t WHERE t.organization_id = :org AND t.connection_id = :connection " +
                        "ORDER BY t.id",
                    organizationId,
                    connectionId,
                    offset,
                    limit
                )
        )

    // the connection row itself (and its credentials, by cascade) is deleted by the service after every contributor
    override suspend fun eraseConnection(
        organizationId: UUID,
        connectionId: UUID
    ): Map<String, Long> =
        linkedMapOf(
            "integration_events" to
                ConnectionDataSql.update(
                    db,
                    "DELETE FROM $m.integration_events WHERE organization_id = :org AND connection_id = :connection",
                    organizationId,
                    connectionId
                ),
            "integration_webhook_endpoints" to
                ConnectionDataSql.update(
                    db,
                    "DELETE FROM $m.integration_webhook_endpoints WHERE organization_id = :org AND connection_id = :connection",
                    organizationId,
                    connectionId
                )
        )

    // rows, not redaction: an event keyed by the record, or whose payload carries its id as a value, is deleted whole.
    // the payload scan reads the connection's events only
    override suspend fun eraseRecord(
        organizationId: UUID,
        connectionId: UUID,
        resource: String,
        recordId: String
    ): Map<String, Long> =
        mapOf(
            "integration_events" to
                ConnectionDataSql.update(
                    db,
                    "DELETE FROM $m.integration_events WHERE organization_id = :org AND connection_id = :connection AND (" +
                        "(resource_id = :record AND (resource_type IS NULL OR resource_type = :resource)) " +
                        "OR jsonb_path_exists(payload, '$.** ? (@ == \$id)', jsonb_build_object('id', CAST(:record AS text))))",
                    organizationId,
                    connectionId,
                    mapOf("record" to recordId, "resource" to resource)
                )
        )

    private suspend fun rows(
        sql: String,
        organizationId: UUID,
        connectionId: UUID,
        offset: Long,
        limit: Int
    ) = ConnectionDataSql.rows(db, json, sql, organizationId, connectionId, offset, limit)
}
