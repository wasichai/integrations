package wasichai.integrations.sync

import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.ConnectionDataSql
import wasichai.integrations.ErasureContributor
import wasichai.integrations.ExportContributor
import java.util.UUID

// the sync module's part of a connection's export and erasure (ADR-0029): every definition with a side on the
// connection, and its links, snapshots, cursors, runs and conflicts
class SyncConnectionData(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) : ExportContributor,
    ErasureContributor {
    private val m = schemas.metadata
    private val definitions =
        "SELECT id FROM $m.integration_sync_definitions WHERE organization_id = :org AND (a_connection_id = :connection OR b_connection_id = :connection)"

    // the links holding the record, on whichever side stands on the connection and resource
    private val matching =
        "SELECT l.id FROM $m.integration_record_links l JOIN $m.integration_sync_definitions d ON d.id = l.definition_id " +
            "WHERE d.organization_id = :org AND ((d.a_connection_id = :connection AND d.a_resource = :resource AND l.a_id = :record) " +
            "OR (d.b_connection_id = :connection AND d.b_resource = :resource AND l.b_id = :record))"

    override suspend fun export(
        organizationId: UUID,
        connectionId: UUID,
        offset: Long,
        limit: Int
    ): Map<String, List<JsonNode>> {
        suspend fun rows(sql: String) = ConnectionDataSql.rows(db, json, sql, organizationId, connectionId, offset, limit)
        return linkedMapOf(
            "syncDefinitions" to rows("SELECT to_jsonb(t)::text AS row FROM $m.integration_sync_definitions t WHERE t.id IN ($definitions) ORDER BY t.id"),
            "recordLinks" to rows("SELECT to_jsonb(t)::text AS row FROM $m.integration_record_links t WHERE t.definition_id IN ($definitions) ORDER BY t.id"),
            "recordSnapshots" to
                rows(
                    "SELECT to_jsonb(t)::text AS row FROM $m.integration_record_snapshots t JOIN $m.integration_record_links l ON l.id = t.link_id " +
                        "WHERE l.definition_id IN ($definitions) ORDER BY t.link_id"
                ),
            "syncCursors" to
                rows(
                    "SELECT to_jsonb(t)::text AS row FROM $m.integration_sync_cursors t WHERE t.definition_id IN ($definitions) " +
                        "ORDER BY t.definition_id, t.side"
                ),
            "syncRuns" to rows("SELECT to_jsonb(t)::text AS row FROM $m.integration_sync_runs t WHERE t.definition_id IN ($definitions) ORDER BY t.id"),
            "syncConflicts" to
                rows("SELECT to_jsonb(t)::text AS row FROM $m.integration_sync_conflicts t WHERE t.definition_id IN ($definitions) ORDER BY t.id")
        )
    }

    // a definition with either side on the connection goes whole: its pairs mean nothing without that side.
    // children first, so every table's count is reported (the foreign keys would cascade anyway)
    override suspend fun eraseConnection(
        organizationId: UUID,
        connectionId: UUID
    ): Map<String, Long> {
        suspend fun delete(sql: String) = ConnectionDataSql.update(db, sql, organizationId, connectionId)
        return linkedMapOf(
            "integration_sync_conflicts" to delete("DELETE FROM $m.integration_sync_conflicts WHERE definition_id IN ($definitions)"),
            "integration_record_snapshots" to
                delete(
                    "DELETE FROM $m.integration_record_snapshots WHERE link_id IN " +
                        "(SELECT id FROM $m.integration_record_links WHERE definition_id IN ($definitions))"
                ),
            "integration_record_links" to delete("DELETE FROM $m.integration_record_links WHERE definition_id IN ($definitions)"),
            "integration_sync_cursors" to delete("DELETE FROM $m.integration_sync_cursors WHERE definition_id IN ($definitions)"),
            "integration_sync_runs" to delete("DELETE FROM $m.integration_sync_runs WHERE definition_id IN ($definitions)"),
            "integration_sync_definitions" to delete("DELETE FROM $m.integration_sync_definitions WHERE id IN ($definitions)")
        )
    }

    // the record's side of every link that holds it is forgotten: its conflicts and the snapshot (values of both sides)
    // are deleted; a link whose other side is set stays ORPHANED with this side's id, version and hash cleared, so the
    // paired record is neither created again nor paired again; a link with only this side is deleted. the paired
    // record at the other provider is never touched
    override suspend fun eraseRecord(
        organizationId: UUID,
        connectionId: UUID,
        resource: String,
        recordId: String
    ): Map<String, Long> {
        val values = mapOf("resource" to resource, "record" to recordId)

        suspend fun run(sql: String) = ConnectionDataSql.update(db, sql, organizationId, connectionId, values)

        fun side(s: String) =
            "FROM $m.integration_sync_definitions d WHERE d.id = l.definition_id AND d.organization_id = :org " +
                "AND d.${s}_connection_id = :connection AND d.${s}_resource = :resource AND l.${s}_id = :record"

        fun orphan(
            s: String,
            other: String
        ) = "UPDATE $m.integration_record_links l SET ${s}_id = NULL, ${s}_version = NULL, ${s}_hash = NULL, state = 'ORPHANED', updated_at = now() " +
            side(s) + " AND l.${other}_id IS NOT NULL"

        fun drop(
            s: String,
            other: String
        ) = "DELETE FROM $m.integration_record_links l USING " + side(s).removePrefix("FROM ") + " AND l.${other}_id IS NULL"

        val conflicts = run("DELETE FROM $m.integration_sync_conflicts WHERE organization_id = :org AND link_id IN ($matching)")
        val snapshots = run("DELETE FROM $m.integration_record_snapshots WHERE link_id IN ($matching)")
        // a side first, then b: a pair holding the record on both sides ends deleted, never with two null ids
        var deleted = run(drop("a", "b"))
        var changed = run(orphan("a", "b"))
        deleted += run(drop("b", "a"))
        changed += run(orphan("b", "a"))
        return linkedMapOf(
            "integration_sync_conflicts" to conflicts,
            "integration_record_snapshots" to snapshots,
            "integration_record_links" to deleted + changed
        )
    }
}
