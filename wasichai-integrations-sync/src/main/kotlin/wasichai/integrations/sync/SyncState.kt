package wasichai.integrations.sync

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.util.UUID

enum class LinkState {
    LINKED,
    PENDING_CREATE,
    CONFLICT,
    ORPHANED
}

data class RecordLink(
    val id: UUID,
    val organizationId: UUID,
    val definitionId: UUID,
    val aId: String?,
    val bId: String?,
    val aVersion: String?,
    val bVersion: String?,
    val aHash: String?,
    val bHash: String?,
    val state: LinkState,
    val createToken: UUID,
    val lastSyncedAt: Instant?
) {
    fun id(side: Side): String? = if (side == Side.A) aId else bId

    fun version(side: Side): String? = if (side == Side.A) aVersion else bVersion

    fun hash(side: Side): String? = if (side == Side.A) aHash else bHash
}

// links, snapshots and cursors: the engine's memory of which records are the same and what they agreed on
class SyncStateRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata
    private val columns = "id, organization_id, definition_id, a_id, b_id, a_version, b_version, a_hash, b_hash, state, create_token, last_synced_at"

    suspend fun link(
        definitionId: UUID,
        side: Side,
        recordId: String
    ): RecordLink? =
        db
            .sql("SELECT $columns FROM $m.integration_record_links WHERE definition_id = :definition AND ${col(side)}_id = :record")
            .bind("definition", definitionId)
            .bind("record", recordId)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun find(
        organizationId: UUID,
        id: UUID
    ): RecordLink? =
        db
            .sql("SELECT $columns FROM $m.integration_record_links WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun inState(
        definitionId: UUID,
        state: LinkState
    ): List<RecordLink> =
        db
            .sql("SELECT $columns FROM $m.integration_record_links WHERE definition_id = :definition AND state = :state")
            .bind("definition", definitionId)
            .bind("state", state.name)
            .map { row, _ -> read(row) }
            .all()
            .collectList()
            .awaitSingle()

    // committed before the provider is asked to create: a crash after the create leaves a trace, never a silent duplicate
    suspend fun insertPending(
        organizationId: UUID,
        definitionId: UUID,
        source: Side,
        sourceId: String,
        now: Instant
    ): RecordLink {
        val id = UUID.randomUUID()
        db
            .sql(
                "INSERT INTO $m.integration_record_links (id, organization_id, definition_id, ${col(source)}_id, state, created_at, updated_at) " +
                    "VALUES (:id, :org, :definition, :record, 'PENDING_CREATE', :now, :now)"
            ).bind("id", id)
            .bind("org", organizationId)
            .bind("definition", definitionId)
            .bind("record", sourceId)
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return find(organizationId, id)!!
    }

    suspend fun insertLinked(
        organizationId: UUID,
        definitionId: UUID,
        aId: String,
        bId: String,
        now: Instant
    ): RecordLink {
        val id = UUID.randomUUID()
        db
            .sql(
                "INSERT INTO $m.integration_record_links (id, organization_id, definition_id, a_id, b_id, state, created_at, updated_at) " +
                    "VALUES (:id, :org, :definition, :a, :b, 'LINKED', :now, :now)"
            ).bind("id", id)
            .bind("org", organizationId)
            .bind("definition", definitionId)
            .bind("a", aId)
            .bind("b", bId)
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        return find(organizationId, id)!!
    }

    // after a pair was synced: ids, versions and hashes of both sides, LINKED
    suspend fun settle(
        link: RecordLink,
        aId: String,
        bId: String,
        aVersion: String?,
        bVersion: String?,
        aHash: String?,
        bHash: String?,
        now: Instant
    ) {
        db
            .sql(
                "UPDATE $m.integration_record_links SET a_id = :a, b_id = :b, a_version = :aVersion, b_version = :bVersion, a_hash = :aHash, " +
                    "b_hash = :bHash, state = 'LINKED', last_synced_at = :now, updated_at = :now WHERE id = :id"
            ).bind("a", aId)
            .bind("b", bId)
            .bindText("aVersion", aVersion)
            .bindText("bVersion", bVersion)
            .bindText("aHash", aHash)
            .bindText("bHash", bHash)
            .bind("now", now.odt())
            .bind("id", link.id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun setState(
        linkId: UUID,
        state: LinkState,
        now: Instant
    ) {
        db
            .sql("UPDATE $m.integration_record_links SET state = :state, updated_at = :now WHERE id = :id")
            .bind("state", state.name)
            .bind("now", now.odt())
            .bind("id", linkId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // forget the hashes: the next run looks at the pair again whatever it reads
    suspend fun clearHashes(linkId: UUID) {
        db
            .sql("UPDATE $m.integration_record_links SET a_hash = NULL, b_hash = NULL WHERE id = :id")
            .bind("id", linkId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun delete(linkId: UUID) {
        db
            .sql("DELETE FROM $m.integration_record_links WHERE id = :id")
            .bind("id", linkId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun snapshot(linkId: UUID): Map<String, Agreed>? =
        db
            .sql("SELECT values::text AS values FROM $m.integration_record_snapshots WHERE link_id = :id")
            .bind("id", linkId)
            .map { row, _ -> row.text("values")!! }
            .one()
            .awaitFirstOrNull()
            ?.let { text ->
                json.readTree(text).properties().associate { (key, pair) ->
                    key to Agreed(FieldValues.decode(pair.get("a")), FieldValues.decode(pair.get("b")))
                }
            }

    suspend fun saveSnapshot(
        linkId: UUID,
        values: Map<String, Agreed>,
        now: Instant
    ) {
        val node = JsonNodeFactory.instance.objectNode()
        values.forEach { (key, agreed) ->
            val pair = JsonNodeFactory.instance.objectNode()
            pair.set("a", FieldValues.encode(agreed.a))
            pair.set("b", FieldValues.encode(agreed.b))
            node.set(key, pair)
        }
        db
            .sql(
                "INSERT INTO $m.integration_record_snapshots (link_id, values, updated_at) VALUES (:id, CAST(:values AS jsonb), :now) " +
                    "ON CONFLICT (link_id) DO UPDATE SET values = EXCLUDED.values, updated_at = EXCLUDED.updated_at"
            ).bind("id", linkId)
            .bind("values", node.toString())
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun cursor(
        definitionId: UUID,
        side: Side
    ): String? =
        db
            .sql("SELECT checkpoint FROM $m.integration_sync_cursors WHERE definition_id = :definition AND side = :side")
            .bind("definition", definitionId)
            .bind("side", side.name)
            .map { row, _ -> row.text("checkpoint") ?: "" }
            .one()
            .awaitFirstOrNull()
            ?.ifEmpty { null }

    suspend fun saveCursor(
        definitionId: UUID,
        side: Side,
        checkpoint: String?,
        now: Instant
    ) {
        db
            .sql(
                "INSERT INTO $m.integration_sync_cursors (definition_id, side, checkpoint, updated_at) VALUES (:definition, :side, :checkpoint, :now) " +
                    "ON CONFLICT (definition_id, side) DO UPDATE SET checkpoint = EXCLUDED.checkpoint, updated_at = EXCLUDED.updated_at"
            ).bind("definition", definitionId)
            .bind("side", side.name)
            .bindText("checkpoint", checkpoint)
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private fun col(side: Side) = if (side == Side.A) "a" else "b"

    private fun read(row: Readable) =
        RecordLink(
            row.uuid("id")!!,
            row.uuid("organization_id")!!,
            row.uuid("definition_id")!!,
            row.text("a_id"),
            row.text("b_id"),
            row.text("a_version"),
            row.text("b_version"),
            row.text("a_hash"),
            row.text("b_hash"),
            LinkState.valueOf(row.text("state")!!),
            row.uuid("create_token")!!,
            row.instant("last_synced_at")
        )
}
