package wasichai.integrations.it

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.it.TestTenant.Companion.call
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// issue #46 with every module booted together: one export holds each module's sections, and one erasure takes the
// derived rows of all of them (sync, workflows, AI, core) for that connection only
@TestPropertySource(
    properties = [
        "wasichai.integrations.encryption.keys.gdpr1=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        "wasichai.integrations.encryption.active-key=gdpr1",
        "wasichai.integrations.jobs.worker-enabled=false",
        "wasichai.integrations.sync.scheduler-enabled=false",
        "wasichai.integrations.workflows.scheduler-enabled=false",
        "wasichai.integrations.retention.enabled=false"
    ]
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionErasureTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val tenant by lazy { TestTenant.provision(client) }
    private val other by lazy { TestTenant.provision(client) }
    private val m by lazy { schemas.metadata }

    private fun exec(
        sql: String,
        vararg binds: Pair<String, Any>
    ): Long =
        runBlocking {
            binds
                .fold(db.sql(sql)) { s, (k, v) -> s.bind(k, v) }
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }

    private fun count(
        table: String,
        column: String,
        id: UUID
    ): Long =
        runBlocking {
            db
                .sql("SELECT count(*) FROM $m.$table WHERE $column = :id")
                .bind("id", id)
                .map { row, _ -> row.get(0, Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    // rows as each module would have written them: a connection, a sync definition with a link and a run, a workflow
    // naming the connection with a run, an AI tool with an execution and an event
    private fun seed(org: UUID): Pair<UUID, Map<String, UUID>> {
        val connection = UUID.randomUUID()
        val partner = UUID.randomUUID()
        listOf(connection, partner).forEach {
            exec(
                "INSERT INTO $m.integration_connections (id, organization_id, connector_id, name, status, auth_type) " +
                    "VALUES (:id, :org, 'notion', 'N', 'ACTIVE', 'API_KEY')",
                "id" to it,
                "org" to org
            )
        }
        val definition = UUID.randomUUID()
        exec(
            "INSERT INTO $m.integration_sync_definitions (id, organization_id, name, a_connection_id, a_resource, b_connection_id, b_resource, direction) " +
                "VALUES (:id, :org, 'S', :a, 'pages', :b, 'pages', 'A_TO_B')",
            "id" to definition,
            "org" to org,
            "a" to connection,
            "b" to partner
        )
        val link = UUID.randomUUID()
        exec(
            "INSERT INTO $m.integration_record_links (id, organization_id, definition_id, a_id, b_id, state) VALUES (:id, :org, :d, 'p-1', 'q-1', 'LINKED')",
            "id" to link,
            "org" to org,
            "d" to definition
        )
        exec(
            "INSERT INTO $m.integration_sync_runs (organization_id, definition_id, trigger, status) VALUES (:org, :d, 'MANUAL', 'SUCCEEDED')",
            "org" to org,
            "d" to definition
        )
        val workflow = UUID.randomUUID()
        exec(
            "INSERT INTO $m.integration_workflow_definitions (id, organization_id, name, graph) VALUES (:id, :org, 'W', CAST(:graph AS jsonb))",
            "id" to workflow,
            "org" to org,
            "graph" to """{"steps":[{"id":"a","type":"action","connectionId":"$connection"}]}"""
        )
        val tool = UUID.randomUUID()
        exec(
            "INSERT INTO $m.integration_ai_tools (id, organization_id, connection_id, name, description, kind, action, input_schema, approval_required, " +
                "rate_limit_per_minute) VALUES (:id, :org, :c, :name, 'd', 'ACTION', 'x', '{}'::jsonb, true, 10)",
            "id" to tool,
            "org" to org,
            "c" to connection,
            "name" to "t_" + TestTenant.unique()
        )
        val execution = UUID.randomUUID()
        exec(
            "INSERT INTO $m.integration_ai_tool_executions (id, organization_id, tool_id, tool_name, tool_version, connection_id, input, status, created_at) " +
                "VALUES (:id, :org, :t, 't', 1, :c, '{}'::jsonb, 'PENDING_APPROVAL', now())",
            "id" to execution,
            "org" to org,
            "t" to tool,
            "c" to connection
        )
        val event = UUID.randomUUID()
        exec(
            "INSERT INTO $m.integration_events (id, organization_id, connection_id, connector_id, dedupe_key, event_type, source, correlation_id) " +
                "VALUES (:id, :org, :c, 'notion', :dedupe, 'page.updated', 'POLL', 'c')",
            "id" to event,
            "dedupe" to event.toString(),
            "org" to org,
            "c" to connection
        )
        return connection to
            mapOf(
                "partner" to partner,
                "definition" to definition,
                "link" to link,
                "workflow" to workflow,
                "tool" to tool,
                "execution" to execution,
                "event" to event
            )
    }

    private fun left(
        connection: UUID,
        ids: Map<String, UUID>
    ): Map<String, Long> =
        mapOf(
            "connection" to count("integration_connections", "id", connection),
            "partner" to count("integration_connections", "id", ids.getValue("partner")),
            "definition" to count("integration_sync_definitions", "id", ids.getValue("definition")),
            "link" to count("integration_record_links", "id", ids.getValue("link")),
            "runs" to count("integration_sync_runs", "definition_id", ids.getValue("definition")),
            "workflow" to count("integration_workflow_definitions", "id", ids.getValue("workflow")),
            "tool" to count("integration_ai_tools", "id", ids.getValue("tool")),
            "execution" to count("integration_ai_tool_executions", "id", ids.getValue("execution")),
            "event" to count("integration_events", "id", ids.getValue("event"))
        )

    @Test
    fun `one export holds every module's sections, and one erasure takes every derived row of the connection only`() {
        val (connection, ids) = seed(tenant.organizationId)
        val (otherConnection, otherIds) = seed(other.organizationId)

        val sections = call(client, HttpMethod.GET, "/api/integrations/connections/$connection/export", tenant.admin, null, 200).get("sections")
        mapOf("events" to 1, "syncDefinitions" to 1, "recordLinks" to 1, "syncRuns" to 1, "workflows" to 1, "aiTools" to 1, "aiToolExecutions" to 1)
            .forEach { (section, size) -> assertEquals(size, sections.get(section).size(), section) }

        call(client, HttpMethod.DELETE, "/api/integrations/connections/$connection", tenant.admin, null, 409)
        call(client, HttpMethod.POST, "/api/integrations/connections/$connection/erase", tenant.member(), null, 403)
        call(client, HttpMethod.POST, "/api/integrations/connections/$connection/erase", other.admin, null, 404)
        call(client, HttpMethod.POST, "/api/integrations/connections/$connection/erase", tenant.admin, null, 200)

        val gone = left(connection, ids)
        assertEquals(1L, gone["partner"], "the other side's connection stays")
        assertEquals(gone.keys.minus("partner").associateWith { 0L }, gone.minus("partner"))
        assertEquals(left(otherConnection, otherIds).keys.associateWith { 1L }.plus("runs" to 1L), left(otherConnection, otherIds))
    }
}
