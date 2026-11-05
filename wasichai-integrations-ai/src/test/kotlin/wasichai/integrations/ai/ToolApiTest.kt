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
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.ai.AiTenant.Companion.call
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolApiTest : AiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var messenger: MemMessenger

    private val connectionSecret = "sk-live-CONNECTION-SECRET-${UUID.randomUUID()}"
    private val tenant by lazy { AiTenant.provision(client).also { it.role("AGENT") } }
    private val other by lazy { AiTenant.provision(client) }
    private val chat by lazy { tenant.connect("mem-m", connectionSecret) }
    private val records by lazy { tenant.connect("mem-r") }
    private val base = "/api/integrations/ai"

    private val messageSchema =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "channel" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 80),
                    "text" to mapOf("type" to "string", "maxLength" to 200),
                    "priority" to mapOf("type" to "string", "enum" to listOf("low", "high"))
                ),
            "required" to listOf("channel", "text")
        )

    private fun expose(
        name: String = "t_" + AiTenant.unique(),
        extra: Map<String, Any?> = emptyMap(),
        expected: Int = 201,
        token: String = tenant.admin
    ): JsonNode =
        call(
            client,
            HttpMethod.POST,
            "$base/tools",
            token,
            mapOf(
                "name" to name,
                "description" to "Send a chat message",
                "connectionId" to chat,
                "action" to "send_message",
                "inputSchema" to messageSchema,
                "allowedRoles" to listOf("agent")
            ) + extra,
            expected
        )

    private fun execute(
        name: String,
        input: Any?,
        expected: Int,
        token: String = tenant.admin
    ): JsonNode = call(client, HttpMethod.POST, "$base/tools/$name/execute", token, input, expected)

    private val hello = mapOf("channel" to "general", "text" to "hello")

    private fun fields(problem: JsonNode): Set<String> {
        val errors = problem.get("errors")
        return (0 until errors.size()).map { errors.get(it).get("field").asString() }.toSet()
    }

    private fun row(execution: String): String =
        runBlocking {
            db
                .sql("SELECT row_to_json(e)::text AS j FROM ${schemas.metadata}.integration_ai_tool_executions e WHERE id = :id")
                .bind("id", UUID.fromString(execution))
                .map { r, _ -> r.get("j", String::class.java)!! }
                .one()
                .awaitSingle()
        }

    private fun auditRows(execution: String): List<String> =
        runBlocking {
            db
                .sql("SELECT row_to_json(a)::text AS j FROM ${schemas.metadata}.audit_log a WHERE record_id = :id ORDER BY occurred_at")
                .bind("id", UUID.fromString(execution))
                .map { r, _ -> r.get("j", String::class.java)!! }
                .all()
                .collectList()
                .awaitSingle()
        }

    @Test
    fun `a tool that is not exposed cannot be executed`() {
        execute("send_message", hello, 404)
        execute("nothing_here", hello, 404)
    }

    @Test
    fun `exposing checks the connection, the connector, the schema, roles and the name`() {
        val problem =
            expose(
                name = "Bad-Name",
                extra = mapOf("connectionId" to UUID.randomUUID().toString(), "allowedRoles" to listOf("NOPE"), "description" to ""),
                expected = 400
            )
        assertEquals(setOf("name", "connectionId", "allowedRoles[0]", "description"), fields(problem))
        assertEquals(setOf("action"), fields(expose(extra = mapOf("action" to "launch_rockets"), expected = 400)))
        assertEquals(setOf("inputSchema"), fields(expose(extra = mapOf("inputSchema" to null), expected = 400)))
        val secretSchema = mapOf("type" to "object", "properties" to mapOf("password" to mapOf("type" to "string")))
        assertEquals(setOf("inputSchema.properties.password"), fields(expose(extra = mapOf("inputSchema" to secretSchema), expected = 400)))
        // the messenger has no records; the records connector has no actions
        val noRecords = mapOf("action" to null, "inputSchema" to null, "resource" to "contacts", "operation" to "create")
        assertEquals(setOf("operation", "resource"), fields(expose(extra = noRecords, expected = 400)))
        assertEquals(setOf("action"), fields(expose(extra = mapOf("connectionId" to records), expected = 400)))
        val record = mapOf("connectionId" to records, "action" to null, "inputSchema" to null, "resource" to "ghosts", "operation" to "read")
        assertEquals(setOf("resource"), fields(expose(extra = record, expected = 400)))
        assertEquals(setOf("externalIdField"), fields(expose(extra = record + mapOf("resource" to "contacts", "operation" to "upsert"), expected = 400)))
        assertEquals(
            setOf("externalIdField"),
            fields(expose(extra = record + mapOf("resource" to "contacts", "operation" to "upsert", "externalIdField" to "name"), expected = 400))
        )
        assertEquals(setOf("inputSchema"), fields(expose(extra = record + mapOf("resource" to "contacts", "inputSchema" to messageSchema), expected = 400)))
        val name = "dup_" + AiTenant.unique()
        expose(name = name)
        expose(name = name, expected = 409)
        // a member does not expose
        expose(token = tenant.member("AGENT"), expected = 403)
    }

    @Test
    fun `defaults are sensitive, and a tool is read, updated with its version and deleted`() {
        val name = "crud_" + AiTenant.unique()
        val created = expose(name = name)
        assertTrue(created.get("approvalRequired").asBoolean())
        assertTrue(created.get("enabled").asBoolean())
        assertEquals(60, created.get("rateLimitPerMinute").asInt())
        assertEquals(listOf("AGENT"), (0 until created.get("allowedRoles").size()).map { created.get("allowedRoles").get(it).asString() })
        assertFalse(created.get("inputSchema").get("additionalProperties").asBoolean())
        val read =
            expose(
                extra =
                    mapOf("connectionId" to records, "action" to null, "inputSchema" to null, "resource" to "contacts", "operation" to "read")
            )
        assertFalse(read.get("approvalRequired").asBoolean())
        assertEquals(
            "id",
            read
                .get("inputSchema")
                .get("required")
                .get(0)
                .asString()
        )

        call(client, HttpMethod.GET, "$base/tools/$name", tenant.admin, null, 200)
        val body =
            mapOf(
                "name" to name,
                "description" to "Changed",
                "connectionId" to chat,
                "action" to "send_message",
                "inputSchema" to messageSchema,
                "approvalRequired" to false
            )
        call(client, HttpMethod.PUT, "$base/tools/$name", tenant.admin, body, 412)
        val updated = call(client, HttpMethod.PUT, "$base/tools/$name", tenant.admin, body, 200, mapOf("If-Match" to "\"1\""))
        assertEquals(2, updated.get("version").asInt())
        assertFalse(updated.get("approvalRequired").asBoolean())
        assertEquals(0, updated.get("allowedRoles").size())
        call(client, HttpMethod.PUT, "$base/tools/$name", tenant.admin, body, 412, mapOf("If-Match" to "\"1\""))
        call(client, HttpMethod.DELETE, "$base/tools/$name", tenant.admin, null, 204)
        call(client, HttpMethod.GET, "$base/tools/$name", tenant.admin, null, 404)
        execute(name, hello, 404)
    }

    @Test
    fun `a caller without an allowed role is refused, one with it runs the tool`() {
        val name = expose(extra = mapOf("approvalRequired" to false)).get("name").asString()
        val stranger = tenant.member()
        execute(name, hello, 403, stranger)
        // what a member may not call, they do not see
        val listed = call(client, HttpMethod.GET, "$base/tools", stranger, null, 200)
        assertFalse((0 until listed.size()).any { listed.get(it).get("name").asString() == name })
        call(client, HttpMethod.GET, "$base/tools/$name", stranger, null, 404)

        val agent = tenant.member("AGENT")
        call(client, HttpMethod.GET, "$base/tools/$name", agent, null, 200)
        val ran = execute(name, hello, 200, agent)
        assertEquals("SUCCEEDED", ran.get("status").asString())
        assertEquals("hello", ran.get("output").get("sent").asString())
        // the agent reads its own execution, a stranger does not
        val id = ran.get("id").asString()
        call(client, HttpMethod.GET, "$base/executions/$id", agent, null, 200)
        call(client, HttpMethod.GET, "$base/executions/$id", stranger, null, 404)
        val own = call(client, HttpMethod.GET, "$base/executions", agent, null, 200).get("content")
        assertEquals(listOf(id), (0 until own.size()).map { own.get(it).get("id").asString() })
        assertEquals(0, call(client, HttpMethod.GET, "$base/executions", stranger, null, 200).get("content").size())
        // MANAGE_ORGANIZATION may always call
        assertEquals("SUCCEEDED", execute(name, hello, 200).get("status").asString())
        execute(name, hello, 401, "Bearer nope")
    }

    @Test
    fun `a sensitive tool waits for approval and runs once when approved`() {
        val name = expose().get("name").asString()
        val agent = tenant.member("AGENT")
        val before = messenger.calls.get()
        val pending = execute(name, hello, 202, agent)
        assertEquals("PENDING_APPROVAL", pending.get("status").asString())
        assertTrue(pending.get("output").isNull)
        assertEquals(before, messenger.calls.get())
        val id = pending.get("id").asString()

        call(client, HttpMethod.POST, "$base/executions/$id/approve", agent, null, 403)
        val approved = call(client, HttpMethod.POST, "$base/executions/$id/approve", tenant.admin, null, 200)
        assertEquals("SUCCEEDED", approved.get("status").asString())
        assertEquals("hello", approved.get("output").get("sent").asString())
        assertFalse(approved.get("decidedBy").isNull)
        assertEquals(before + 1, messenger.calls.get())
        call(client, HttpMethod.POST, "$base/executions/$id/approve", tenant.admin, null, 409)
        call(client, HttpMethod.POST, "$base/executions/$id/reject", tenant.admin, null, 409)
        assertEquals(before + 1, messenger.calls.get())
        assertEquals("SUCCEEDED", call(client, HttpMethod.GET, "$base/executions/$id", agent, null, 200).get("status").asString())
    }

    @Test
    fun `a rejected execution never runs`() {
        val name = expose().get("name").asString()
        val before = messenger.calls.get()
        val id = execute(name, hello, 202, tenant.member("AGENT")).get("id").asString()
        val rejected = call(client, HttpMethod.POST, "$base/executions/$id/reject", tenant.admin, mapOf("reason" to "not today"), 200)
        assertEquals("REJECTED", rejected.get("status").asString())
        assertEquals("not today", rejected.get("reason").asString())
        call(client, HttpMethod.POST, "$base/executions/$id/approve", tenant.admin, null, 409)
        assertEquals(before, messenger.calls.get())
    }

    @Test
    fun `a pending execution of a tool that changed is not approved`() {
        val name = expose().get("name").asString()
        val id = execute(name, hello, 202).get("id").asString()
        val body = mapOf("name" to name, "description" to "x", "connectionId" to chat, "action" to "send_message", "inputSchema" to messageSchema)
        call(client, HttpMethod.PUT, "$base/tools/$name", tenant.admin, body, 200, mapOf("If-Match" to "\"1\""))
        call(client, HttpMethod.POST, "$base/executions/$id/approve", tenant.admin, null, 409)
        call(client, HttpMethod.POST, "$base/executions/$id/reject", tenant.admin, null, 200)
    }

    @Test
    fun `every execution is audited, and no credential reaches the stored row`() {
        val name = expose(extra = mapOf("approvalRequired" to false)).get("name").asString()
        val ran = execute(name, hello, 200)
        // the caller gets the provider's answer; the stored summary masks it
        assertEquals(
            MemMessenger.PROVIDER_TOKEN,
            ran
                .get("output")
                .get("auth")
                .get("accessToken")
                .asString()
        )
        val id = ran.get("id").asString()
        val stored = row(id)
        assertFalse(stored.contains(connectionSecret), stored)
        assertFalse(stored.contains(MemMessenger.PROVIDER_TOKEN), stored)
        assertTrue(stored.contains("\"accessToken\" : \"***\"") || stored.contains("\"accessToken\": \"***\""), stored)
        assertTrue(stored.contains("\"general\""), stored)
        val viewed = call(client, HttpMethod.GET, "$base/executions/$id", tenant.admin, null, 200)
        assertEquals(
            "***",
            viewed
                .get("output")
                .get("auth")
                .get("accessToken")
                .asString()
        )
        assertFalse(viewed.get("correlationId").isNull)
        val audit = auditRows(id)
        assertEquals(2, audit.size, audit.toString())
        audit.forEach {
            assertFalse(it.contains(connectionSecret) || it.contains(MemMessenger.PROVIDER_TOKEN), it)
            assertTrue(it.contains(ToolExecutions.AUDIT_OBJECT), it)
        }

        // a failed run is recorded too, with the mapped error and no provider body
        val failing = expose(extra = mapOf("approvalRequired" to false, "action" to "fail")).get("name").asString()
        val failed = execute(failing, hello, 200)
        assertEquals("FAILED", failed.get("status").asString())
        assertEquals("permanent", failed.get("errorKind").asString())
        assertTrue(failed.get("error").asString().contains("channel_not_found"))
        assertFalse(row(failed.get("id").asString()).contains(connectionSecret))
    }

    @Test
    fun `an input that does not fit the schema is refused with its field paths`() {
        val name = expose(extra = mapOf("approvalRequired" to false)).get("name").asString()
        val problem = execute(name, mapOf("channel" to "", "priority" to "urgent", "extra" to 1), 400)
        assertEquals(setOf("input.channel", "input.text", "input.priority", "input.extra"), fields(problem))
        assertEquals(setOf("input.channel", "input.text"), fields(execute(name, null, 400)))
    }

    @Test
    fun `over its rate limit a tool answers 429`() {
        val name = expose(extra = mapOf("approvalRequired" to false, "rateLimitPerMinute" to 2)).get("name").asString()
        execute(name, hello, 200)
        execute(name, hello, 200)
        execute(name, hello, 429)
    }

    @Test
    fun `a disabled tool is not executed`() {
        val name = expose(extra = mapOf("enabled" to false)).get("name").asString()
        execute(name, hello, 409)
        execute(name, hello, 403, tenant.member())
        val agent = tenant.member("AGENT")
        execute(name, hello, 409, agent)
        assertEquals(
            0,
            call(client, HttpMethod.GET, "$base/tools", agent, null, 200).let { l ->
                (0 until l.size()).count {
                    l.get(it).get("name").asString() ==
                        name
                }
            }
        )
    }

    @Test
    fun `another tenant's tools and executions are not found`() {
        val name = expose().get("name").asString()
        val id = execute(name, hello, 202).get("id").asString()
        execute(name, hello, 404, other.admin)
        call(client, HttpMethod.GET, "$base/tools/$name", other.admin, null, 404)
        call(client, HttpMethod.PUT, "$base/tools/$name", other.admin, mapOf("name" to name), 404, mapOf("If-Match" to "\"1\""))
        call(client, HttpMethod.DELETE, "$base/tools/$name", other.admin, null, 404)
        call(client, HttpMethod.GET, "$base/executions/$id", other.admin, null, 404)
        call(client, HttpMethod.POST, "$base/executions/$id/approve", other.admin, null, 404)
        call(client, HttpMethod.POST, "$base/executions/$id/reject", other.admin, null, 404)
        assertEquals(0, call(client, HttpMethod.GET, "$base/tools", other.admin, null, 200).size())
        assertEquals(0, call(client, HttpMethod.GET, "$base/executions", other.admin, null, 200).get("content").size())
        // and the other tenant cannot expose this tenant's connection
        assertEquals(setOf("connectionId"), fields(expose(extra = mapOf("allowedRoles" to emptyList<String>()), token = other.admin, expected = 400)))
        call(client, HttpMethod.POST, "$base/executions/$id/reject", tenant.admin, null, 200)
    }

    @Test
    fun `record operations run through the connector`() {
        val connection = tenant.connect("mem-r")

        fun tool(
            operation: String,
            extra: Map<String, Any?> = emptyMap()
        ) = expose(
            extra =
                mapOf(
                    "connectionId" to connection,
                    "action" to null,
                    "inputSchema" to null,
                    "resource" to "contacts",
                    "operation" to operation,
                    "approvalRequired" to false
                ) + extra
        ).get("name").asString()
        val create = tool("create")
        val problem = execute(create, mapOf("fields" to mapOf("tier" to "bronze", "computed" to "x")), 400)
        assertEquals(setOf("input.fields.name", "input.fields.tier", "input.fields.computed"), fields(problem))
        val created = execute(create, mapOf("fields" to mapOf("name" to "Ada", "tier" to "gold", "amount" to 12.5, "since" to "2026-01-02")), 200)
        val id = created.get("output").get("id").asString()
        assertTrue(created.get("output").get("created").asBoolean())

        val read = execute(tool("read"), mapOf("id" to id), 200).get("output").get("record")
        assertEquals("Ada", read.get("fields").get("name").asString())
        assertEquals("2026-01-02", read.get("fields").get("since").asString())
        execute(tool("update"), mapOf("id" to id, "fields" to mapOf("tier" to "silver")), 200)
        val upsert = tool("upsert", mapOf("externalIdField" to "extId"))
        execute(upsert, mapOf("externalId" to "x-1", "fields" to mapOf("name" to "Grace")), 200)
        val listed = execute(tool("list"), mapOf("pageSize" to 10), 200).get("output").get("records")
        assertEquals(2, listed.size())
        execute(tool("delete"), mapOf("id" to id), 200)
        assertTrue(
            execute(tool("read"), mapOf("id" to id), 200)
                .get("output")
                .get("record")
                .isNull
        )
        // a write tool is sensitive unless said otherwise
        assertTrue(
            expose(extra = mapOf("connectionId" to connection, "action" to null, "inputSchema" to null, "resource" to "contacts", "operation" to "delete"))
                .get("approvalRequired")
                .asBoolean()
        )
    }

    @Test
    fun `a connection a tool uses is not deleted under it`() {
        val used = tenant.connect("mem-m")
        val name = expose(extra = mapOf("connectionId" to used)).get("name").asString()
        val refused = call(client, HttpMethod.DELETE, "/api/integrations/connections/$used", tenant.admin, null, 409)
        assertTrue(refused.get("detail").asString().contains("tool '$name'"), refused.toString())
        call(client, HttpMethod.DELETE, "$base/tools/$name", tenant.admin, null, 204)
        call(client, HttpMethod.DELETE, "/api/integrations/connections/$used", tenant.admin, null, 204)
    }

    @Test
    fun `executions page newest first with a cursor`() {
        val name = expose(extra = mapOf("approvalRequired" to false)).get("name").asString()
        val ids = (1..3).map { execute(name, hello, 200).get("id").asString() }
        val first = call(client, HttpMethod.GET, "$base/executions?tool=$name&limit=2", tenant.admin, null, 200)
        assertEquals(
            ids.reversed().take(2),
            (0 until 2).map {
                first
                    .get("content")
                    .get(it)
                    .get("id")
                    .asString()
            }
        )
        val cursor = first.get("nextCursor").asString()
        val second = call(client, HttpMethod.GET, "$base/executions?tool=$name&limit=2&cursor=$cursor", tenant.admin, null, 200)
        assertEquals(
            listOf(ids.first()),
            (0 until second.get("content").size()).map {
                second
                    .get("content")
                    .get(it)
                    .get("id")
                    .asString()
            }
        )
        assertTrue(second.get("nextCursor").isNull)
        assertEquals(3, call(client, HttpMethod.GET, "$base/executions?tool=$name&status=succeeded", tenant.admin, null, 200).get("content").size())
        call(client, HttpMethod.GET, "$base/executions?cursor=nope", tenant.admin, null, 400)
        call(client, HttpMethod.GET, "$base/executions?status=nope", tenant.admin, null, 400)
        call(client, HttpMethod.GET, "$base/executions?limit=0", tenant.admin, null, 400)
    }
}

// the module switched off: its routes are not there
@TestPropertySource(properties = ["wasichai.integrations.ai.enabled=false"])
class AiDisabledTest : AiIntegrationTest() {
    @Test
    fun `no tool routes`() {
        val tenant = AiTenant.provision(client)
        call(client, HttpMethod.GET, "/api/integrations/ai/tools", tenant.admin, null, 404)
        call(client, HttpMethod.POST, "/api/integrations/ai/tools/x/execute", tenant.admin, mapOf<String, Any>(), 404)
    }
}
