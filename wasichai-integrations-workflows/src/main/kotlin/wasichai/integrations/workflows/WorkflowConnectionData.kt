package wasichai.integrations.workflows

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.ConnectionDataSql
import wasichai.integrations.ErasureContributor
import wasichai.integrations.ExportContributor
import java.util.UUID

// the workflows module's part of a connection's export and erasure (ADR-0029): every workflow whose draft, any version
// or trigger names the connection, with its versions, trigger and runs
class WorkflowConnectionData(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) : ExportContributor,
    ErasureContributor {
    private val m = schemas.metadata
    private val names = "'$.**.connectionId ? (@ == \$c)', jsonb_build_object('c', CAST(:connection AS text))"
    private val definitions =
        "SELECT d.id FROM $m.integration_workflow_definitions d WHERE d.organization_id = :org AND (jsonb_path_exists(d.graph, $names) " +
            "OR EXISTS (SELECT 1 FROM $m.integration_workflow_versions v WHERE v.definition_id = d.id AND jsonb_path_exists(v.graph, $names)) " +
            "OR EXISTS (SELECT 1 FROM $m.integration_workflow_triggers t WHERE t.definition_id = d.id AND t.connection_id = :connection))"
    private val executions = "SELECT id FROM $m.integration_workflow_executions WHERE definition_id IN ($definitions)"

    override suspend fun export(
        organizationId: UUID,
        connectionId: UUID,
        offset: Long,
        limit: Int
    ): Map<String, List<JsonNode>> {
        suspend fun rows(sql: String) = ConnectionDataSql.rows(db, json, sql, organizationId, connectionId, offset, limit)
        return linkedMapOf(
            // the webhook secret's hash stays home: only whether one is set
            "workflows" to
                rows(
                    "SELECT ((to_jsonb(t) - 'webhook_token_hash') || jsonb_build_object('webhook_token_set', t.webhook_token_hash IS NOT NULL))::text AS row " +
                        "FROM $m.integration_workflow_definitions t WHERE t.id IN ($definitions) ORDER BY t.id"
                ),
            "workflowVersions" to
                rows(
                    "SELECT to_jsonb(t)::text AS row FROM $m.integration_workflow_versions t WHERE t.definition_id IN ($definitions) " +
                        "ORDER BY t.definition_id, t.number"
                ),
            "workflowTriggers" to
                rows(
                    "SELECT to_jsonb(t)::text AS row FROM $m.integration_workflow_triggers t WHERE t.definition_id IN ($definitions) ORDER BY t.definition_id"
                ),
            "workflowExecutions" to
                rows("SELECT to_jsonb(t)::text AS row FROM $m.integration_workflow_executions t WHERE t.id IN ($executions) ORDER BY t.id"),
            "workflowStepExecutions" to
                rows(
                    "SELECT to_jsonb(t)::text AS row FROM $m.integration_workflow_step_executions t WHERE t.execution_id IN ($executions) " +
                        "ORDER BY t.execution_id, t.step_id"
                )
        )
    }

    // a workflow naming the connection goes whole, runs included (a running one too: its steps then find nothing)
    override suspend fun eraseConnection(
        organizationId: UUID,
        connectionId: UUID
    ): Map<String, Long> {
        suspend fun delete(sql: String) = ConnectionDataSql.update(db, sql, organizationId, connectionId)
        return linkedMapOf(
            "integration_workflow_step_executions" to delete("DELETE FROM $m.integration_workflow_step_executions WHERE execution_id IN ($executions)"),
            "integration_workflow_executions" to delete("DELETE FROM $m.integration_workflow_executions WHERE id IN ($executions)"),
            "integration_workflow_triggers" to delete("DELETE FROM $m.integration_workflow_triggers WHERE definition_id IN ($definitions)"),
            "integration_workflow_versions" to delete("DELETE FROM $m.integration_workflow_versions WHERE definition_id IN ($definitions)"),
            "integration_workflow_definitions" to delete("DELETE FROM $m.integration_workflow_definitions WHERE id IN ($definitions)")
        )
    }

    // a finished run of such a workflow whose trigger payload or any step input or output carries the record id as a
    // value is deleted with its steps. a run still going is left alone; the workflow definitions are not changed
    override suspend fun eraseRecord(
        organizationId: UUID,
        connectionId: UUID,
        resource: String,
        recordId: String
    ): Map<String, Long> {
        val mentions = "'$.** ? (@ == \$id)', jsonb_build_object('id', CAST(:record AS text))"
        val target =
            "SELECT e.id FROM $m.integration_workflow_executions e WHERE e.organization_id = :org AND e.status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') " +
                "AND e.definition_id IN ($definitions) AND (jsonb_path_exists(e.trigger_payload, $mentions) " +
                "OR EXISTS (SELECT 1 FROM $m.integration_workflow_step_executions s WHERE s.execution_id = e.id " +
                "AND (jsonb_path_exists(s.input, $mentions) OR jsonb_path_exists(s.output, $mentions))))"
        // one statement: the steps are counted, then go with their runs (cascade), on the same snapshot
        val (runs, steps) =
            db
                .sql(
                    "WITH t AS ($target), s AS (SELECT count(*) AS n FROM $m.integration_workflow_step_executions WHERE execution_id IN (SELECT id FROM t)), " +
                        "e AS (DELETE FROM $m.integration_workflow_executions WHERE id IN (SELECT id FROM t) RETURNING 1) " +
                        "SELECT (SELECT count(*) FROM e) AS runs, (SELECT n FROM s) AS steps"
                ).bind("org", organizationId)
                .bind("connection", connectionId)
                .bind("record", recordId)
                .map { row, _ -> row.get("runs", Long::class.javaObjectType)!! to row.get("steps", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        return linkedMapOf("integration_workflow_executions" to runs, "integration_workflow_step_executions" to steps)
    }
}
