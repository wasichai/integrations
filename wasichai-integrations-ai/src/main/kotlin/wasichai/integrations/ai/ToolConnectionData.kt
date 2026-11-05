package wasichai.integrations.ai

import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.ConnectionDataSql
import wasichai.integrations.ErasureContributor
import wasichai.integrations.ExportContributor
import java.util.UUID

// the AI module's part of a connection's export and erasure (ADR-0029): the tools on the connection and every
// execution against it (an execution keeps its connection id after its tool is deleted)
class ToolConnectionData(
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
    ): Map<String, List<JsonNode>> {
        suspend fun rows(sql: String) = ConnectionDataSql.rows(db, json, sql, organizationId, connectionId, offset, limit)
        return linkedMapOf(
            "aiTools" to
                rows(
                    "SELECT to_jsonb(t)::text AS row FROM $m.integration_ai_tools t WHERE t.organization_id = :org " +
                        "AND t.connection_id = :connection ORDER BY t.id"
                ),
            "aiToolExecutions" to
                rows(
                    "SELECT to_jsonb(t)::text AS row FROM $m.integration_ai_tool_executions t WHERE t.organization_id = :org " +
                        "AND t.connection_id = :connection ORDER BY t.id"
                )
        )
    }

    // pending approvals and running executions too: the connection they would run on is going
    override suspend fun eraseConnection(
        organizationId: UUID,
        connectionId: UUID
    ): Map<String, Long> {
        suspend fun delete(sql: String) = ConnectionDataSql.update(db, sql, organizationId, connectionId)
        return linkedMapOf(
            "integration_ai_tool_executions" to
                delete("DELETE FROM $m.integration_ai_tool_executions WHERE organization_id = :org AND connection_id = :connection"),
            "integration_ai_tools" to delete("DELETE FROM $m.integration_ai_tools WHERE organization_id = :org AND connection_id = :connection")
        )
    }

    // a finished execution whose input or output carries the record id as a value is deleted; a pending or running
    // one is left alone. the tools themselves hold no record
    override suspend fun eraseRecord(
        organizationId: UUID,
        connectionId: UUID,
        resource: String,
        recordId: String
    ): Map<String, Long> {
        val mentions = "'$.** ? (@ == \$id)', jsonb_build_object('id', CAST(:record AS text))"
        return mapOf(
            "integration_ai_tool_executions" to
                ConnectionDataSql.update(
                    db,
                    "DELETE FROM $m.integration_ai_tool_executions WHERE organization_id = :org AND connection_id = :connection " +
                        "AND status IN ('SUCCEEDED', 'FAILED', 'REJECTED') AND (jsonb_path_exists(input, $mentions) OR jsonb_path_exists(output, $mentions))",
                    organizationId,
                    connectionId,
                    mapOf("record" to recordId)
                )
        )
    }
}
