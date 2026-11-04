package wasichai.integrations.sync

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

enum class RunTrigger {
    MANUAL,
    SCHEDULE,
    EVENT,
    INITIAL,
    RECONCILE
}

enum class RunStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED
}

enum class ConflictKind {
    FIELDS,
    DELETED
}

enum class ConflictStatus {
    OPEN,
    RESOLVED
}

// what a run did, counted as it goes
class RunCounts {
    var read = 0
    var created = 0
    var updated = 0
    var skipped = 0
    var conflicts = 0
    var failed = 0
}

data class SyncRunView(
    val id: UUID,
    val definitionId: UUID,
    val trigger: RunTrigger,
    val status: RunStatus,
    val read: Int,
    val created: Int,
    val updated: Int,
    val skipped: Int,
    val conflicts: Int,
    val failed: Int,
    val error: String?,
    val queuedAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?
)

data class ConflictView(
    val id: UUID,
    val definitionId: UUID,
    val linkId: UUID,
    val kind: ConflictKind,
    val fields: JsonNode,
    val status: ConflictStatus,
    val resolution: JsonNode?,
    val detectedAt: Instant,
    val resolvedAt: Instant?
)

class SyncRunRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata
    private val runColumns =
        "id, definition_id, trigger, status, read_count, created_count, updated_count, skipped_count, conflict_count, failed_count, error, " +
            "queued_at, started_at, finished_at"
    private val conflictColumns =
        "id, organization_id, definition_id, link_id, kind, fields::text AS fields, status, resolution::text AS resolution, created_at, resolved_at"

    suspend fun queue(
        organizationId: UUID,
        definitionId: UUID,
        trigger: RunTrigger,
        now: Instant
    ): UUID {
        val id = UUID.randomUUID()
        db
            .sql(
                "INSERT INTO $m.integration_sync_runs (id, organization_id, definition_id, trigger, status, queued_at) " +
                    "VALUES (:id, :org, :definition, :trigger, 'QUEUED', :now)"
            ).bind("id", id)
            .bind("org", organizationId)
            .bind("definition", definitionId)
            .bind("trigger", trigger.name)
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return id
    }

    suspend fun drop(id: UUID) {
        db
            .sql("DELETE FROM $m.integration_sync_runs WHERE id = :id AND status = 'QUEUED'")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun start(
        id: UUID,
        now: Instant
    ) {
        db
            .sql("UPDATE $m.integration_sync_runs SET status = 'RUNNING', started_at = :now, error = NULL WHERE id = :id")
            .bind("now", now.odt())
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun finish(
        id: UUID,
        status: RunStatus,
        counts: RunCounts,
        error: String?,
        now: Instant
    ) {
        db
            .sql(
                "UPDATE $m.integration_sync_runs SET status = :status, read_count = :read, created_count = :created, updated_count = :updated, " +
                    "skipped_count = :skipped, conflict_count = :conflicts, failed_count = :failed, error = :error, finished_at = :now WHERE id = :id"
            ).bind("status", status.name)
            .bind("read", counts.read)
            .bind("created", counts.created)
            .bind("updated", counts.updated)
            .bind("skipped", counts.skipped)
            .bind("conflicts", counts.conflicts)
            .bind("failed", counts.failed)
            .bindText("error", error?.take(2000))
            .bind("now", now.odt())
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun runs(
        organizationId: UUID,
        definitionId: UUID,
        limit: Int
    ): List<SyncRunView> =
        db
            .sql(
                "SELECT $runColumns FROM $m.integration_sync_runs WHERE organization_id = :org AND definition_id = :definition " +
                    "ORDER BY queued_at DESC, id LIMIT :limit"
            ).bind("org", organizationId)
            .bind("definition", definitionId)
            .bind("limit", limit)
            .map { row, _ -> readRun(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun openConflict(
        organizationId: UUID,
        definitionId: UUID,
        linkId: UUID,
        kind: ConflictKind,
        fields: List<ConflictField>,
        now: Instant
    ) {
        val array = JsonNodeFactory.instance.arrayNode()
        fields.forEach { f ->
            val node =
                JsonNodeFactory.instance
                    .objectNode()
                    .put("key", f.key)
                    .put("aField", f.aField)
                    .put("bField", f.bField)
            node.set("a", FieldValues.encode(f.a))
            node.set("b", FieldValues.encode(f.b))
            f.base?.let {
                val base = JsonNodeFactory.instance.objectNode()
                base.set("a", FieldValues.encode(it.a))
                base.set("b", FieldValues.encode(it.b))
                node.set("base", base)
            }
            array.add(node)
        }
        db
            .sql(
                "INSERT INTO $m.integration_sync_conflicts (organization_id, definition_id, link_id, kind, fields, created_at) " +
                    "VALUES (:org, :definition, :link, :kind, CAST(:fields AS jsonb), :now) ON CONFLICT (link_id) WHERE status = 'OPEN' DO NOTHING"
            ).bind("org", organizationId)
            .bind("definition", definitionId)
            .bind("link", linkId)
            .bind("kind", kind.name)
            .bind("fields", array.toString())
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun conflicts(
        organizationId: UUID,
        status: ConflictStatus?,
        definitionId: UUID?,
        limit: Int
    ): List<ConflictView> =
        db
            .sql(
                "SELECT $conflictColumns FROM $m.integration_sync_conflicts WHERE organization_id = :org " +
                    "AND (CAST(:status AS text) IS NULL OR status = :status) AND (CAST(:definition AS uuid) IS NULL OR definition_id = :definition) " +
                    "ORDER BY created_at DESC, id LIMIT :limit"
            ).bind("org", organizationId)
            .bindText("status", status?.name)
            .bindUuid("definition", definitionId)
            .bind("limit", limit)
            .map { row, _ -> readConflict(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun conflict(
        organizationId: UUID,
        id: UUID
    ): ConflictView? =
        db
            .sql("SELECT $conflictColumns FROM $m.integration_sync_conflicts WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> readConflict(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun resolve(
        id: UUID,
        resolution: JsonNode,
        userId: UUID,
        now: Instant
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_sync_conflicts SET status = 'RESOLVED', resolution = CAST(:resolution AS jsonb), resolved_at = :now, " +
                    "resolved_by = :user WHERE id = :id AND status = 'OPEN'"
            ).bind("resolution", resolution.toString())
            .bind("now", now.odt())
            .bind("user", userId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    private fun readRun(row: Readable) =
        SyncRunView(
            row.uuid("id")!!,
            row.uuid("definition_id")!!,
            RunTrigger.valueOf(row.text("trigger")!!),
            RunStatus.valueOf(row.text("status")!!),
            row.int("read_count")!!,
            row.int("created_count")!!,
            row.int("updated_count")!!,
            row.int("skipped_count")!!,
            row.int("conflict_count")!!,
            row.int("failed_count")!!,
            row.text("error"),
            row.instant("queued_at")!!,
            row.instant("started_at"),
            row.instant("finished_at")
        )

    private fun readConflict(row: Readable) =
        ConflictView(
            row.uuid("id")!!,
            row.uuid("definition_id")!!,
            row.uuid("link_id")!!,
            ConflictKind.valueOf(row.text("kind")!!),
            json.readTree(row.text("fields") ?: "[]"),
            ConflictStatus.valueOf(row.text("status")!!),
            row.text("resolution")?.let { json.readTree(it) },
            row.instant("created_at")!!,
            row.instant("resolved_at")
        )
}
