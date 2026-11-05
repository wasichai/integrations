package wasichai.integrations.ai

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.Retention
import wasichai.integrations.UsageMeter
import wasichai.integrations.ai.AiTenant.Companion.call
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// issue #46 in the AI module: export and erasure of the tools on a connection and their executions, the purge of
// finished executions and the tool calls counter
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolDataLifecycleTest : AiIntegrationTest() {
    @Autowired
    private lateinit var usage: UsageMeter

    @Autowired
    private lateinit var retention: Retention

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val secret = "sk-live-GDPR-SECRET-${UUID.randomUUID()}"
    private val tenant by lazy { AiTenant.provision(client) }
    private val other by lazy { AiTenant.provision(client) }
    private val base = "/api/integrations/ai"
    private val m by lazy { schemas.metadata }

    private val schema =
        mapOf(
            "type" to "object",
            "properties" to mapOf("channel" to mapOf("type" to "string"), "text" to mapOf("type" to "string")),
            "required" to listOf("channel", "text")
        )

    private fun expose(
        connection: String,
        approval: Boolean = false,
        t: AiTenant = tenant
    ): String =
        call(
            client,
            HttpMethod.POST,
            "$base/tools",
            t.admin,
            mapOf(
                "name" to "t_" + AiTenant.unique(),
                "description" to "Send a chat message",
                "connectionId" to connection,
                "action" to "send_message",
                "inputSchema" to schema,
                "approvalRequired" to approval
            ),
            201
        ).get("name").asString()

    private fun execute(
        tool: String,
        text: String,
        expected: Int = 200,
        t: AiTenant = tenant
    ): String =
        call(client, HttpMethod.POST, "$base/tools/$tool/execute", t.admin, mapOf("channel" to "general", "text" to text), expected).get("id").asString()

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

    private fun exists(execution: String) = count("SELECT count(*) FROM $m.integration_ai_tool_executions WHERE id = :id", execution) == 1L

    private fun calls(token: String): Long {
        val today = LocalDate.now(ZoneOffset.UTC)
        val rows = call(client, HttpMethod.GET, "/api/integrations/usage?metric=${ToolExecutions.TOOL_CALLS}&from=$today&to=$today", token, null, 200)
        return (0 until rows.size()).sumOf { rows.get(it).get("count").asLong() }
    }

    @Test
    fun `a tool call that ran counts for its tenant, one waiting for approval does not yet`() {
        val connection = tenant.connect("mem-m")
        val tool = expose(connection)
        val sensitive = expose(connection, approval = true)
        runBlocking { usage.flush() }
        val before = calls(tenant.admin)
        execute(tool, "one")
        execute(tool, "two")
        execute(sensitive, "three", 202)
        runBlocking { usage.flush() }
        assertEquals(before + 2, calls(tenant.member()))
        assertEquals(0L, calls(AiTenant.provision(client).admin), "a tenant that called nothing")
    }

    @Test
    fun `an export holds the tools on the connection and their executions, and never its api key`() {
        val connection = tenant.connect("mem-m", secret)
        val tool = expose(connection)
        execute(tool, "hello export")
        val export = call(client, HttpMethod.GET, "/api/integrations/connections/$connection/export", tenant.admin, null, 200)
        assertEquals(
            tool,
            export
                .get("sections")
                .get("aiTools")
                .get(0)
                .get("name")
                .asString()
        )
        assertEquals(1, export.get("sections").get("aiToolExecutions").size())
        assertTrue(export.toString().contains("hello export"))
        assertTrue(export.get("credentials").get("apiKey").asBoolean())
        assertFalse(export.toString().contains(secret))
    }

    @Test
    fun `erasing a record deletes the finished executions that carry its id, never a pending one`() {
        val connection = tenant.connect("mem-m")
        val tool = expose(connection)
        val mentioning = execute(tool, "rec-55")
        val unrelated = execute(tool, "rec-56")
        val pending = execute(expose(connection, approval = true), "rec-55", 202)
        val erased =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/connections/$connection/erase-record",
                tenant.admin,
                mapOf("resource" to "messages", "recordId" to "rec-55"),
                200
            ).get("erased")
        assertEquals(1, erased.get("integration_ai_tool_executions").asInt(), erased.toString())
        assertFalse(exists(mentioning))
        assertTrue(exists(unrelated))
        assertTrue(exists(pending))
    }

    @Test
    fun `erasing a used connection takes its tools and every execution against it`() {
        val connection = tenant.connect("mem-m")
        val tool = expose(connection)
        val ran = execute(tool, "bye")
        val pending = execute(expose(connection, approval = true), "later", 202)
        val keptTool = expose(tenant.connect("mem-m"))
        val kept = execute(keptTool, "stays")
        val elsewhere = execute(expose(other.connect("mem-m"), t = other), "other", t = other)
        call(client, HttpMethod.DELETE, "/api/integrations/connections/$connection", tenant.admin, null, 409)
        val erased = call(client, HttpMethod.POST, "/api/integrations/connections/$connection/erase", tenant.admin, null, 200).get("erased")
        assertEquals(2, erased.get("integration_ai_tools").asInt(), erased.toString())
        assertEquals(2, erased.get("integration_ai_tool_executions").asInt())
        call(client, HttpMethod.GET, "$base/tools/$tool", tenant.admin, null, 404)
        assertFalse(exists(ran))
        assertFalse(exists(pending))
        call(client, HttpMethod.GET, "$base/tools/$keptTool", tenant.admin, null, 200)
        assertTrue(exists(kept))
        assertTrue(exists(elsewhere))
    }

    @Test
    fun `retention purges old finished executions and keeps pending, recent and other tenants' ones`() {
        val connection = tenant.connect("mem-m")
        val tool = expose(connection)
        val old = execute(tool, "old")
        val recent = execute(tool, "recent")
        val pending = execute(expose(connection, approval = true), "waits", 202)
        val otherRecent = execute(expose(other.connect("mem-m"), t = other), "other", t = other)
        val long = Instant.now().minus(Duration.ofDays(400)).atOffset(ZoneOffset.UTC)
        runBlocking {
            db
                .sql("UPDATE $m.integration_ai_tool_executions SET created_at = :old, finished_at = :old WHERE id = :id")
                .bind("old", long)
                .bind("id", UUID.fromString(old))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            db
                .sql("UPDATE $m.integration_ai_tool_executions SET created_at = :old WHERE id = :id")
                .bind("old", long)
                .bind("id", UUID.fromString(pending))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val purged = runBlocking { retention.purge(Instant.now()) }
        assertTrue(purged.getValue("ai.executions") >= 1, purged.toString())
        assertFalse(exists(old))
        listOf(recent, pending, otherRecent).forEach { assertTrue(exists(it), it) }
    }
}
