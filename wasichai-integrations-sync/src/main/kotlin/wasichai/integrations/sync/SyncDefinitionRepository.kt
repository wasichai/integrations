package wasichai.integrations.sync

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

class SyncDefinitionRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata
    private val columns =
        "id, organization_id, name, a_connection_id, a_resource, b_connection_id, b_resource, direction, status, delete_policy, " +
            "schedule_seconds, reconcile_seconds, match_on::text AS match_on, mappings::text AS mappings, version, created_at, updated_at"

    suspend fun list(organizationId: UUID): List<SyncDefinition> =
        db
            .sql("SELECT $columns FROM $m.integration_sync_definitions WHERE organization_id = :org ORDER BY name, id")
            .bind("org", organizationId)
            .map { row, _ -> read(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun find(
        organizationId: UUID,
        id: UUID
    ): SyncDefinition? =
        db
            .sql("SELECT $columns FROM $m.integration_sync_definitions WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    // active definitions with a side on this connection: what an event of it may concern
    suspend fun activeOnConnection(
        organizationId: UUID,
        connectionId: UUID
    ): List<SyncDefinition> =
        db
            .sql(
                "SELECT $columns FROM $m.integration_sync_definitions WHERE organization_id = :org AND status = 'ACTIVE' " +
                    "AND (a_connection_id = :connection OR b_connection_id = :connection)"
            ).bind("org", organizationId)
            .bind("connection", connectionId)
            .map { row, _ -> read(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun insert(
        definition: SyncDefinition,
        createdBy: UUID
    ) {
        db
            .sql(
                "INSERT INTO $m.integration_sync_definitions (id, organization_id, name, a_connection_id, a_resource, b_connection_id, b_resource, " +
                    "direction, status, delete_policy, schedule_seconds, reconcile_seconds, match_on, mappings, created_by, created_at, updated_at) " +
                    "VALUES (:id, :org, :name, :aConnection, :aResource, :bConnection, :bResource, :direction, :status, :deletePolicy, :schedule, " +
                    ":reconcile, CAST(:matchOn AS jsonb), CAST(:mappings AS jsonb), :createdBy, :now, :now)"
            ).bind("id", definition.id)
            .bind("org", definition.organizationId)
            .let { bindBody(it, definition) }
            .bind("status", definition.status.name)
            .bind("createdBy", createdBy)
            .bind("now", definition.createdAt.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // optimistic lock on the version the caller read
    suspend fun update(
        definition: SyncDefinition,
        expectedVersion: Int
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_sync_definitions SET name = :name, a_connection_id = :aConnection, a_resource = :aResource, " +
                    "b_connection_id = :bConnection, b_resource = :bResource, direction = :direction, delete_policy = :deletePolicy, " +
                    "schedule_seconds = :schedule, reconcile_seconds = :reconcile, match_on = CAST(:matchOn AS jsonb), mappings = CAST(:mappings AS jsonb), " +
                    "version = version + 1, updated_at = :now WHERE organization_id = :org AND id = :id AND version = :version"
            ).let { bindBody(it, definition) }
            .bind("now", definition.updatedAt.odt())
            .bind("org", definition.organizationId)
            .bind("id", definition.id)
            .bind("version", expectedVersion)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    suspend fun setStatus(
        organizationId: UUID,
        id: UUID,
        status: DefinitionStatus,
        now: Instant
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_sync_definitions SET status = :status, version = version + 1, updated_at = :now " +
                    "WHERE organization_id = :org AND id = :id"
            ).bind("status", status.name)
            .bind("now", now.odt())
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM $m.integration_sync_definitions WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    private fun bindBody(
        spec: DatabaseClient.GenericExecuteSpec,
        d: SyncDefinition
    ): DatabaseClient.GenericExecuteSpec =
        spec
            .bind("name", d.name)
            .bind("aConnection", d.a.connectionId!!)
            .bind("aResource", d.a.resource!!)
            .bind("bConnection", d.b.connectionId!!)
            .bind("bResource", d.b.resource!!)
            .bind("direction", d.direction.name)
            .bind("deletePolicy", d.deletePolicy.name)
            .bindInt("schedule", d.scheduleSeconds)
            .bindInt("reconcile", d.reconcileSeconds)
            .bindText("matchOn", d.matchOn?.let { json.writeValueAsString(it) })
            .bind("mappings", json.writeValueAsString(d.mappings))

    private fun read(row: Readable): SyncDefinition =
        SyncDefinition(
            id = row.uuid("id")!!,
            organizationId = row.uuid("organization_id")!!,
            name = row.text("name")!!,
            a = SideRef(row.uuid("a_connection_id"), row.text("a_resource")),
            b = SideRef(row.uuid("b_connection_id"), row.text("b_resource")),
            direction = SyncDirection.valueOf(row.text("direction")!!),
            status = DefinitionStatus.valueOf(row.text("status")!!),
            deletePolicy = DeletePolicy.valueOf(row.text("delete_policy")!!),
            scheduleSeconds = row.int("schedule_seconds"),
            reconcileSeconds = row.int("reconcile_seconds"),
            matchOn = row.text("match_on")?.let { json.readValue(it, MatchOn::class.java) },
            mappings = json.readValue(row.text("mappings") ?: "[]", Array<FieldMapping>::class.java).toList(),
            version = row.int("version")!!,
            createdAt = row.instant("created_at")!!,
            updatedAt = row.instant("updated_at")!!
        )
}
