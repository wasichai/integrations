package wasichai.integrations.workflows

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.JobRunner
import wasichai.integrations.Retention
import wasichai.integrations.UsageMeter
import wasichai.integrations.workflows.WorkflowTenant.Companion.call
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// issue #46 in the workflows module: export and erasure of what stands on a connection, the purge of finished runs
// and the steps counter
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowDataLifecycleTest : WorkflowsIntegrationTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var usage: UsageMeter

    @Autowired
    private lateinit var retention: Retention

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val tenant by lazy { WorkflowTenant.provision(client) }
    private val other by lazy { WorkflowTenant.provision(client) }
    private val base = "/api/integrations/workflows"
    private val m by lazy { schemas.metadata }

    // manual trigger, a create on the connection with the payload's name, an end
    private fun publish(
        connection: String,
        t: WorkflowTenant = tenant
    ): String {
        val graph =
            mapOf(
                "trigger" to mapOf("type" to "manual", "next" to "create"),
                "steps" to
                    listOf(
                        mapOf(
                            "id" to "create",
                            "type" to "action",
                            "connectionId" to connection,
                            "write" to mapOf("resource" to "contacts", "operation" to "create"),
                            "input" to mapOf("name" to mapOf("ref" to "trigger.payload.name")),
                            "next" to "done"
                        ),
                        mapOf("id" to "done", "type" to "end")
                    )
            )
        val id = call(client, HttpMethod.POST, base, t.admin, mapOf("name" to "W ${WorkflowTenant.unique()}", "graph" to graph), 201).get("id").asString()
        call(client, HttpMethod.POST, "$base/$id/publish", t.admin, null, 201)
        return id
    }

    private fun run(
        workflow: String,
        payload: Map<String, Any?>,
        t: WorkflowTenant = tenant
    ): String {
        val execution = call(client, HttpMethod.POST, "$base/$workflow/run", t.admin, mapOf("payload" to payload), 202).get("id").asString()
        repeat(200) {
            runBlocking { runner.drainOnce() }
            val status = call(client, HttpMethod.GET, "$base/executions/$execution", t.admin, null, 200).get("execution").get("status").asString()
            if (status in setOf("SUCCEEDED", "FAILED", "CANCELLED")) return execution
            Thread.sleep(10)
        }
        throw AssertionError("execution $execution never finished")
    }

    private fun count(
        sql: String,
        id: String
    ): Long =
        runBlocking {
            db
                .sql(sql)
                .bind("id", UUID.fromString(id))
                .map { row, _ -> row.get(0, Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    private fun executionExists(id: String) = count("SELECT count(*) FROM $m.integration_workflow_executions WHERE id = :id", id) == 1L

    private fun steps(token: String): Long {
        val today = LocalDate.now(ZoneOffset.UTC)
        val rows: JsonNode =
            call(client, HttpMethod.GET, "/api/integrations/usage?metric=${WorkflowEngine.STEPS_RUN}&from=${today.minusDays(1)}&to=$today", token, null, 200)
        return (0 until rows.size()).sumOf { rows.get(it).get("count").asLong() }
    }

    @Test
    fun `every step that ran counts for its tenant`() {
        val workflow = publish(tenant.connect("mem-a"))
        runBlocking { usage.flush() }
        val before = steps(tenant.admin)
        run(workflow, mapOf("name" to "Ana"))
        runBlocking { usage.flush() }
        assertEquals(before + 2, steps(tenant.member()))
        assertEquals(0L, steps(WorkflowTenant.provision(client).admin), "a tenant that ran nothing")
    }

    @Test
    fun `an export of a connection holds the workflows naming it and their runs, without the webhook secret's hash`() {
        val connection = tenant.connect("mem-a")
        val workflow = publish(connection)
        run(workflow, mapOf("name" to "Bo"))
        val sections = call(client, HttpMethod.GET, "/api/integrations/connections/$connection/export", tenant.admin, null, 200).get("sections")
        assertEquals(
            workflow,
            sections
                .get("workflows")
                .get(0)
                .get("id")
                .asString()
        )
        assertTrue(sections.get("workflows").get(0).has("webhook_token_set"))
        assertTrue(!sections.get("workflows").get(0).has("webhook_token_hash"))
        assertEquals(1, sections.get("workflowVersions").size())
        assertEquals(1, sections.get("workflowExecutions").size())
        assertEquals(2, sections.get("workflowStepExecutions").size())
        assertTrue(sections.get("workflowStepExecutions").toString().contains("Bo"))
    }

    @Test
    fun `erasing a record deletes the finished runs that carry its id, and no other`() {
        val connection = tenant.connect("mem-a")
        val workflow = publish(connection)
        val mentioning = run(workflow, mapOf("name" to "Cy", "id" to "rec-77"))
        val unrelated = run(workflow, mapOf("name" to "Di", "id" to "rec-78"))
        val erased =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/connections/$connection/erase-record",
                tenant.admin,
                mapOf("resource" to "contacts", "recordId" to "rec-77"),
                200
            ).get("erased")
        assertEquals(1, erased.get("integration_workflow_executions").asInt(), erased.toString())
        assertEquals(2, erased.get("integration_workflow_step_executions").asInt())
        assertTrue(!executionExists(mentioning))
        assertTrue(executionExists(unrelated))
        call(client, HttpMethod.GET, "$base/$workflow", tenant.admin, null, 200)
    }

    @Test
    fun `erasing a used connection takes the workflows naming it, with their runs, and nothing else`() {
        val connection = tenant.connect("mem-a")
        val workflow = publish(connection)
        val execution = run(workflow, mapOf("name" to "Ed"))
        val kept = publish(tenant.connect("mem-a"))
        val keptRun = run(kept, mapOf("name" to "Fa"))
        val elsewhere = publish(other.connect("mem-a"), other)
        call(client, HttpMethod.DELETE, "/api/integrations/connections/$connection", tenant.admin, null, 409)
        val erased = call(client, HttpMethod.POST, "/api/integrations/connections/$connection/erase", tenant.admin, null, 200).get("erased")
        assertEquals(1, erased.get("integration_workflow_definitions").asInt(), erased.toString())
        assertEquals(1, erased.get("integration_workflow_versions").asInt())
        assertEquals(1, erased.get("integration_workflow_executions").asInt())
        assertEquals(2, erased.get("integration_workflow_step_executions").asInt())
        call(client, HttpMethod.GET, "$base/$workflow", tenant.admin, null, 404)
        assertTrue(!executionExists(execution))
        call(client, HttpMethod.GET, "$base/$kept", tenant.admin, null, 200)
        assertTrue(executionExists(keptRun))
        call(client, HttpMethod.GET, "$base/$elsewhere", other.admin, null, 200)
    }

    @Test
    fun `retention purges old finished runs with their steps, and keeps recent and unfinished ones`() {
        val workflow = publish(tenant.connect("mem-a"))
        val old = run(workflow, mapOf("name" to "Gi"))
        val waiting = run(workflow, mapOf("name" to "Ha"))
        val recent = run(workflow, mapOf("name" to "Io"))
        val otherRun = run(publish(other.connect("mem-a"), other), mapOf("name" to "Jo"), other)
        val long = Instant.now().minus(Duration.ofDays(120)).atOffset(ZoneOffset.UTC)
        runBlocking {
            db
                .sql("UPDATE $m.integration_workflow_executions SET finished_at = :old WHERE id IN (:a, :b)")
                .bind("old", long)
                .bind("a", UUID.fromString(old))
                .bind("b", UUID.fromString(waiting))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            // as if it were still waiting for an approval: never purged, whatever its dates
            db
                .sql("UPDATE $m.integration_workflow_executions SET status = 'WAITING' WHERE id = :id")
                .bind("id", UUID.fromString(waiting))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val purged = runBlocking { retention.purge(Instant.now()) }
        assertTrue(purged.getValue("workflows.executions") >= 1, purged.toString())
        assertTrue(!executionExists(old))
        assertEquals(0, count("SELECT count(*) FROM $m.integration_workflow_step_executions WHERE execution_id = :id", old))
        listOf(waiting, recent, otherRun).forEach { assertTrue(executionExists(it), it) }
    }
}
