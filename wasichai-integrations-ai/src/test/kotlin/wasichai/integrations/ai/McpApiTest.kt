package wasichai.integrations.ai

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.ai.AiTenant.Companion.call
import java.util.UUID

// the MCP endpoint over http: the protocol, and that it is only an interface over the catalog and ToolExecutions
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = ["wasichai.integrations.ai.mcp.allowed-origins=http://localhost:5173"])
class McpApiTest : AiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var messenger: MemMessenger

    private val json = JsonMapper.builder().build()
    private val tenant by lazy { AiTenant.provision(client).also { it.role("AGENT") } }
    private val other by lazy { AiTenant.provision(client) }
    private val chat by lazy { tenant.connect("mem-m") }
    private val agent by lazy { tenant.member("AGENT") }
    private val mcp = "/api/integrations/mcp"
    private val hello = mapOf("channel" to "general", "text" to "hello")

    private val messageSchema =
        mapOf(
            "type" to "object",
            "properties" to mapOf("channel" to mapOf("type" to "string", "minLength" to 1), "text" to mapOf("type" to "string")),
            "required" to listOf("channel", "text")
        )

    private fun expose(
        on: AiTenant = tenant,
        connection: String = chat,
        extra: Map<String, Any?> = emptyMap()
    ): String =
        call(
            client,
            HttpMethod.POST,
            "/api/integrations/ai/tools",
            on.admin,
            mapOf(
                "name" to "m_" + AiTenant.unique(),
                "description" to "Send a chat message",
                "connectionId" to connection,
                "action" to "send_message",
                "inputSchema" to messageSchema,
                "allowedRoles" to listOf("AGENT")
            ) + extra,
            201
        ).get("name").asString()

    private fun post(
        token: String?,
        body: String,
        expected: Int,
        headers: Map<String, String> = emptyMap()
    ): JsonNode {
        val result =
            client
                .post()
                .uri(mcp)
                .contentType(MediaType.APPLICATION_JSON)
                .headers { h ->
                    h.accept = listOf(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                    token?.let { h.set(HttpHeaders.AUTHORIZATION, it) }
                    headers.forEach { (k, v) -> h.set(k, v) }
                }.bodyValue(body)
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        if (result.status.value() != expected) throw AssertionError("POST $mcp answered ${result.status}, expected $expected: ${result.responseBody}")
        return result.responseBody?.takeIf { it.isNotBlank() }?.let { json.readTree(it) } ?: json.nullNode()
    }

    private fun rpc(
        token: String,
        method: String,
        params: Any? = null,
        headers: Map<String, String> = mapOf("MCP-Protocol-Version" to "2025-06-18")
    ): JsonNode {
        val message = mutableMapOf<String, Any?>("jsonrpc" to "2.0", "id" to UUID.randomUUID().toString(), "method" to method)
        if (params != null) message["params"] = params
        val answer = post(token, json.writeValueAsString(message), 200, headers)
        assertEquals(message["id"], answer.get("id").asString())
        return answer
    }

    private fun toolCall(
        token: String,
        name: String,
        arguments: Any? = hello
    ): JsonNode = rpc(token, "tools/call", mapOf("name" to name, "arguments" to arguments))

    private fun listed(token: String): List<String> {
        val tools = rpc(token, "tools/list").get("result").get("tools")
        return (0 until tools.size()).map { tools.get(it).get("name").asString() }
    }

    private fun userId(token: String): String = call(client, HttpMethod.GET, "/api/auth/me", token, null, 200).get("userId").asString()

    private fun auditUsers(execution: String): List<String> =
        runBlocking {
            db
                .sql("SELECT user_id::text AS u FROM ${schemas.metadata}.audit_log WHERE record_id = :id ORDER BY occurred_at")
                .bind("id", UUID.fromString(execution))
                .map { r, _ -> r.get("u", String::class.java) ?: "" }
                .all()
                .collectList()
                .awaitSingle()
        }

    @Test
    fun `initialize negotiates the revision, initialized is accepted, ping answers`() {
        val init = rpc(tenant.admin, "initialize", mapOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any>()), emptyMap())
        val result = init.get("result")
        assertEquals("2025-06-18", result.get("protocolVersion").asString())
        assertFalse(
            result
                .get("capabilities")
                .get("tools")
                .get("listChanged")
                .asBoolean()
        )
        assertEquals("wasichai-integrations", result.get("serverInfo").get("name").asString())
        val older = rpc(tenant.admin, "initialize", mapOf("protocolVersion" to "2024-11-05"), emptyMap())
        assertEquals("2025-06-18", older.get("result").get("protocolVersion").asString())
        assertTrue(post(tenant.admin, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", 202).isNull)
        assertEquals(0, rpc(agent, "ping").get("result").size())
        // stateless: no session is issued, a client's session header changes nothing
        assertEquals(0, rpc(agent, "ping", headers = mapOf("Mcp-Session-Id" to "anything")).get("result").size())
    }

    @Test
    fun `malformed messages, unknown methods and a revision the server does not speak`() {
        assertEquals(-32700, post(tenant.admin, "{not json", 400).get("error").get("code").asInt())
        assertEquals(-32600, post(tenant.admin, """{"jsonrpc":"1.0","id":1,"method":"ping"}""", 400).get("error").get("code").asInt())
        assertEquals(-32600, post(tenant.admin, """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""", 400).get("error").get("code").asInt())
        assertEquals(-32601, rpc(tenant.admin, "prompts/list").get("error").get("code").asInt())
        assertEquals(
            -32600,
            post(
                tenant.admin,
                """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
                400,
                mapOf("MCP-Protocol-Version" to "1999-01-01")
            ).get("error").get("code").asInt()
        )
    }

    @Test
    fun `authentication is the platform's, and no stream or session route exists`() {
        post(null, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", 401)
        post("Bearer nope", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", 401)
        client
            .get()
            .uri(mcp)
            .header(HttpHeaders.AUTHORIZATION, tenant.admin)
            .exchange()
            .expectStatus()
            .isEqualTo(405)
            .expectHeader()
            .valueEquals(HttpHeaders.ALLOW, "POST")
        client
            .delete()
            .uri(mcp)
            .header(HttpHeaders.AUTHORIZATION, tenant.admin)
            .exchange()
            .expectStatus()
            .isEqualTo(405)
    }

    @Test
    fun `an origin outside the allow-list is refused`() {
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        post(tenant.admin, ping, 200, mapOf(HttpHeaders.ORIGIN to "http://localhost:5173"))
        // the platform's cors allows any localhost port; the endpoint's own list does not
        assertEquals(-32001, post(tenant.admin, ping, 403, mapOf(HttpHeaders.ORIGIN to "http://localhost:6666")).get("error").get("code").asInt())
    }

    @Test
    fun `tools list holds what the caller may call, by role and by tenant`() {
        val own = AiTenant.provision(client).also { it.role("AGENT") }
        val connection = own.connect("mem-m")
        val forAgents = expose(own, connection)
        val managersOnly = expose(own, connection, mapOf("allowedRoles" to emptyList<String>()))
        expose(own, connection, mapOf("enabled" to false))
        assertEquals(listOf(forAgents), listed(own.member("AGENT")))
        assertEquals(emptyList<String>(), listed(own.member()))
        assertEquals(listOf(forAgents, managersOnly).sorted(), listed(own.admin))
        assertFalse(listed(other.admin).contains(forAgents))
        val definition =
            rpc(own.admin, "tools/list")
                .get("result")
                .get("tools")
                .let { t -> (0 until t.size()).map { t.get(it) } }
                .first { it.get("name").asString() == forAgents }
        assertEquals("Send a chat message", definition.get("description").asString())
        assertEquals("object", definition.get("inputSchema").get("type").asString())
        assertFalse(definition.get("inputSchema").get("additionalProperties").asBoolean())
    }

    @Test
    fun `a call runs through the execution path, recorded and audited as the caller`() {
        val name = expose(extra = mapOf("approvalRequired" to false))
        val before = messenger.calls.get()
        val result = toolCall(agent, name).get("result")
        assertFalse(result.get("isError").asBoolean())
        assertEquals("hello", result.get("structuredContent").get("sent").asString())
        assertTrue(
            result
                .get("content")
                .get(0)
                .get("text")
                .asString()
                .contains("\"sent\"")
        )
        assertEquals(before + 1, messenger.calls.get())
        val id = result.get("_meta").get("dev.wasichai/executionId").asString()
        val stored = call(client, HttpMethod.GET, "/api/integrations/ai/executions/$id", agent, null, 200)
        assertEquals("SUCCEEDED", stored.get("status").asString())
        val caller = userId(agent)
        assertEquals(caller, stored.get("callerId").asString())
        assertEquals(listOf(caller, caller), auditUsers(id))

        // a provider failure is a tool result with isError, and an execution
        val failing = expose(extra = mapOf("approvalRequired" to false, "action" to "fail"))
        val failed = toolCall(agent, failing).get("result")
        assertTrue(failed.get("isError").asBoolean())
        assertTrue(
            failed
                .get("content")
                .get(0)
                .get("text")
                .asString()
                .contains("permanent")
        )
    }

    @Test
    fun `the policy's refusals - unknown, invalid, forbidden, disabled, rate limited, another tenant`() {
        val name = expose(extra = mapOf("approvalRequired" to false, "rateLimitPerMinute" to 2))
        val unknown = toolCall(agent, "not_exposed").get("error")
        assertEquals(-32602, unknown.get("code").asInt())
        assertEquals("Unknown tool: not_exposed", unknown.get("message").asString())

        val invalid = toolCall(agent, name, mapOf("channel" to "", "extra" to 1)).get("error")
        assertEquals(-32602, invalid.get("code").asInt())
        val errors = invalid.get("data").get("errors")
        assertEquals(
            setOf("arguments.channel", "arguments.text", "arguments.extra"),
            (0 until errors.size()).map { errors.get(it).get("field").asString() }.toSet()
        )
        assertEquals(-32001, toolCall(tenant.member(), name).get("error").get("code").asInt())
        assertEquals(-32002, toolCall(agent, expose(extra = mapOf("enabled" to false))).get("error").get("code").asInt())

        // the invalid call above counted against the limit (the policy's order); a fresh tool for the budget
        val budget = expose(extra = mapOf("approvalRequired" to false, "rateLimitPerMinute" to 2))
        val before = messenger.calls.get()
        toolCall(agent, budget)
        toolCall(agent, budget)
        val limited = toolCall(agent, budget).get("error")
        assertEquals(-32003, limited.get("code").asInt())
        assertTrue(limited.get("data").get("retryAfter").asInt() >= 1)
        assertEquals(before + 2, messenger.calls.get())

        // another tenant's tool does not exist for this caller
        assertEquals(-32602, toolCall(other.admin, name).get("error").get("code").asInt())
        assertEquals(before + 2, messenger.calls.get())
    }

    @Test
    fun `an approval-required call is pending until a manager approves it over REST`() {
        val name = expose()
        val before = messenger.calls.get()
        val result = toolCall(agent, name).get("result")
        assertFalse(result.get("isError").asBoolean())
        val pending = result.get("structuredContent")
        assertEquals("PENDING_APPROVAL", pending.get("status").asString())
        val id = pending.get("executionId").asString()
        assertEquals("/api/integrations/ai/executions/$id", pending.get("statusUrl").asString())
        assertEquals(before, messenger.calls.get())
        // MCP grants no approval: the agent cannot approve its own call
        call(client, HttpMethod.POST, "/api/integrations/ai/executions/$id/approve", agent, null, 403)
        call(client, HttpMethod.POST, "/api/integrations/ai/executions/$id/approve", tenant.admin, null, 200)
        assertEquals(before + 1, messenger.calls.get())
        assertEquals("SUCCEEDED", call(client, HttpMethod.GET, "/api/integrations/ai/executions/$id", agent, null, 200).get("status").asString())
        call(client, HttpMethod.GET, "/api/integrations/ai/executions/$id", other.admin, null, 404)
    }
}

// MCP switched off: the endpoint is gone, the REST tool routes stay
@TestPropertySource(properties = ["wasichai.integrations.ai.mcp.enabled=false"])
class McpDisabledTest : AiIntegrationTest() {
    @Test
    fun `no MCP route`() {
        val tenant = AiTenant.provision(client)
        call(client, HttpMethod.POST, "/api/integrations/mcp", tenant.admin, mapOf("jsonrpc" to "2.0", "id" to 1, "method" to "ping"), 404)
        call(client, HttpMethod.GET, "/api/integrations/ai/tools", tenant.admin, null, 200)
    }
}
