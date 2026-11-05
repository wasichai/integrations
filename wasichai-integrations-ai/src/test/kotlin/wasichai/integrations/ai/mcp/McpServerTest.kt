package wasichai.integrations.ai.mcp

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.RetryLaterException
import wasichai.core.common.UnauthorizedException
import wasichai.core.common.ValidationException
import wasichai.integrations.ai.ExecutionResult
import wasichai.integrations.ai.ExecutionStatus
import wasichai.integrations.ai.ExecutionView
import wasichai.integrations.ai.ToolKind
import wasichai.integrations.ai.ToolOperation
import wasichai.integrations.ai.ToolView
import java.time.Duration
import java.time.Instant
import java.util.UUID

class McpServerTest {
    private val json = JsonMapper.builder().build()
    private var visible: List<ToolView> = emptyList()
    private var outcome: suspend (String, JsonNode?) -> ExecutionResult = { _, _ -> error("not called") }
    private val calls = mutableListOf<Pair<String, JsonNode?>>()

    private val server =
        McpServer(json, listOf("https://Agent.example/"), 2, { visible }, { name, args ->
            calls += name to args
            outcome(name, args)
        }, "1.2.3")

    private fun handle(
        body: String?,
        origin: String? = null,
        version: String? = null
    ): McpReply = runBlocking { server.handle(body, origin, version) }

    private fun rpc(
        method: String,
        params: Any? = null,
        id: Any = 1
    ): JsonNode {
        val message = mutableMapOf<String, Any?>("jsonrpc" to "2.0", "id" to id, "method" to method)
        if (params != null) message["params"] = params
        val reply = handle(json.writeValueAsString(message))
        assertEquals(200, reply.status, reply.body)
        return json.readTree(reply.body)
    }

    private fun errorOf(reply: McpReply): JsonNode = json.readTree(reply.body).get("error")

    private fun tool(
        name: String,
        enabled: Boolean = true,
        kind: ToolKind = ToolKind.ACTION,
        operation: ToolOperation? = null
    ) = ToolView(
        UUID.randomUUID(),
        name,
        "does $name",
        UUID.randomUUID(),
        kind,
        if (kind == ToolKind.ACTION) "act" else null,
        if (kind == ToolKind.RESOURCE) "contacts" else null,
        operation,
        null,
        json.readTree("""{"type":"object","properties":{},"additionalProperties":false}"""),
        emptyList(),
        false,
        60,
        enabled,
        1,
        Instant.EPOCH,
        Instant.EPOCH
    )

    private fun execution(
        status: ExecutionStatus,
        output: JsonNode? = null,
        error: String? = null
    ) = ExecutionView(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "send",
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        status,
        json.createObjectNode(),
        output,
        false,
        error,
        error?.let { "permanent" },
        null,
        null,
        null,
        null,
        Instant.EPOCH,
        null,
        null
    )

    @Test
    fun `malformed input is an http 400 with a JSON-RPC error without id`() {
        listOf(
            "{" to -32700,
            "" to -32700,
            null to -32700,
            "[]" to -32600,
            """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""" to -32600,
            "42" to -32600,
            """{"jsonrpc":"1.0","id":1,"method":"ping"}""" to -32600,
            """{"id":1,"method":"ping"}""" to -32600,
            """{"jsonrpc":"2.0","id":1}""" to -32600,
            """{"jsonrpc":"2.0","id":1,"method":7}""" to -32600,
            """{"jsonrpc":"2.0","id":null,"method":"ping"}""" to -32600,
            """{"jsonrpc":"2.0","id":{"a":1},"method":"ping"}""" to -32600
        ).forEach { (body, code) ->
            val reply = handle(body)
            assertEquals(400, reply.status, body)
            assertEquals(code, errorOf(reply).get("code").asInt(), body)
            assertTrue(json.readTree(reply.body).get("id").isNull)
        }
    }

    @Test
    fun `notifications and responses are accepted without an answer`() {
        assertEquals(McpReply(202, null), handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        assertEquals(McpReply(202, null), handle("""{"jsonrpc":"2.0","method":"notifications/whatever","params":{}}"""))
        assertEquals(McpReply(202, null), handle("""{"jsonrpc":"2.0","id":"s1","result":{}}"""))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `initialize negotiates the revision`() {
        val same = rpc("initialize", mapOf("protocolVersion" to "2025-06-18", "capabilities" to emptyMap<String, Any>()))
        assertEquals(1, same.get("id").asInt())
        val result = same.get("result")
        assertEquals("2025-06-18", result.get("protocolVersion").asString())
        assertFalse(
            result
                .get("capabilities")
                .get("tools")
                .get("listChanged")
                .asBoolean()
        )
        assertEquals("wasichai-integrations", result.get("serverInfo").get("name").asString())
        assertEquals("1.2.3", result.get("serverInfo").get("version").asString())
        val older = rpc("initialize", mapOf("protocolVersion" to "2024-11-05"), id = "a")
        assertEquals("a", older.get("id").asString())
        assertEquals(McpServer.LATEST, older.get("result").get("protocolVersion").asString())
        assertEquals(-32602, rpc("initialize").get("error").get("code").asInt())
        assertEquals(-32602, rpc("initialize", mapOf("protocolVersion" to 3)).get("error").get("code").asInt())
    }

    @Test
    fun `ping, unknown methods and params that are not an object`() {
        assertEquals(0, rpc("ping").get("result").size())
        val missing = rpc("resources/list").get("error")
        assertEquals(-32601, missing.get("code").asInt())
        assertTrue(missing.get("message").asString().contains("resources/list"))
        assertEquals(-32602, rpc("ping", listOf(1)).get("error").get("code").asInt())
    }

    @Test
    fun `the protocol version header and the origin are checked first`() {
        val ping = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
        assertEquals(200, handle(ping, version = "2025-06-18").status)
        val old = handle(ping, version = "2024-11-05")
        assertEquals(400, old.status)
        assertEquals(-32600, errorOf(old).get("code").asInt())
        assertEquals(200, handle(ping, origin = "https://agent.example").status)
        val evil = handle(ping, origin = "https://evil.example")
        assertEquals(HttpStatus.FORBIDDEN.value(), evil.status)
        assertEquals(McpServer.FORBIDDEN, errorOf(evil).get("code").asInt())
        assertEquals(403, handle(ping, origin = "null").status)
    }

    @Test
    fun `tools list shows enabled tools only, by name, in pages`() {
        visible =
            listOf(
                tool("zeta"),
                tool("alpha", kind = ToolKind.RESOURCE, operation = ToolOperation.READ),
                tool("off", enabled = false),
                tool("beta", kind = ToolKind.RESOURCE, operation = ToolOperation.CREATE)
            )
        val first = rpc("tools/list").get("result")
        val tools = first.get("tools")
        assertEquals(listOf("alpha", "beta"), (0 until tools.size()).map { tools.get(it).get("name").asString() })
        assertEquals(
            "object",
            tools
                .get(0)
                .get("inputSchema")
                .get("type")
                .asString()
        )
        assertTrue(
            tools
                .get(0)
                .get("annotations")
                .get("readOnlyHint")
                .asBoolean()
        )
        assertFalse(
            tools
                .get(1)
                .get("annotations")
                .get("readOnlyHint")
                .asBoolean()
        )
        assertFalse(
            tools
                .get(1)
                .get("annotations")
                .get("destructiveHint")
                .asBoolean()
        )
        val cursor = first.get("nextCursor").asString()
        val second = rpc("tools/list", mapOf("cursor" to cursor)).get("result")
        assertEquals(
            "zeta",
            second
                .get("tools")
                .get(0)
                .get("name")
                .asString()
        )
        assertTrue(
            second
                .get("tools")
                .get(0)
                .get("annotations")
                .get("destructiveHint")
                .asBoolean()
        )
        assertNull(second.get("nextCursor"))
        assertEquals(-32602, rpc("tools/list", mapOf("cursor" to "!!")).get("error").get("code").asInt())
        assertEquals(-32602, rpc("tools/list", mapOf("cursor" to 5)).get("error").get("code").asInt())
    }

    @Test
    fun `a call that ran answers its output as text and structured content`() {
        val ran = execution(ExecutionStatus.SUCCEEDED, json.readTree("""{"sent":"hi"}"""))
        outcome = { _, _ -> ExecutionResult(ran, false) }
        val result = rpc("tools/call", mapOf("name" to "send", "arguments" to mapOf("text" to "hi"))).get("result")
        assertFalse(result.get("isError").asBoolean())
        assertEquals("hi", result.get("structuredContent").get("sent").asString())
        assertEquals(
            """{"sent":"hi"}""",
            result
                .get("content")
                .get(0)
                .get("text")
                .asString()
        )
        assertEquals(ran.id.toString(), result.get("_meta").get(McpServer.META_EXECUTION_ID).asString())
        assertEquals("send", calls.single().first)
        assertEquals(
            "hi",
            calls
                .single()
                .second!!
                .get("text")
                .asString()
        )

        // no arguments: the policy decides what an empty input means
        rpc("tools/call", mapOf("name" to "send"))
        assertNull(calls.last().second)
    }

    @Test
    fun `a provider failure is a tool error, a pending call is not`() {
        outcome = { _, _ -> ExecutionResult(execution(ExecutionStatus.FAILED, error = "channel_not_found"), false) }
        val failed = rpc("tools/call", mapOf("name" to "send")).get("result")
        assertTrue(failed.get("isError").asBoolean())
        assertTrue(
            failed
                .get("content")
                .get(0)
                .get("text")
                .asString()
                .contains("channel_not_found")
        )

        val waiting = execution(ExecutionStatus.PENDING_APPROVAL)
        outcome = { _, _ -> ExecutionResult(waiting, true) }
        val pending = rpc("tools/call", mapOf("name" to "send")).get("result")
        assertFalse(pending.get("isError").asBoolean())
        assertEquals("PENDING_APPROVAL", pending.get("structuredContent").get("status").asString())
        assertEquals(waiting.id.toString(), pending.get("structuredContent").get("executionId").asString())
        assertEquals("/api/integrations/ai/executions/${waiting.id}", pending.get("structuredContent").get("statusUrl").asString())
        assertTrue(
            pending
                .get("content")
                .get(0)
                .get("text")
                .asString()
                .contains(waiting.id.toString())
        )
    }

    @Test
    fun `the policy's refusals become JSON-RPC errors`() {
        fun refusedWith(e: Exception): JsonNode {
            outcome = { _, _ -> throw e }
            return rpc("tools/call", mapOf("name" to "send")).get("error")
        }
        val unknown = refusedWith(NotFoundException("Tool 'send' not found"))
        assertEquals(-32602, unknown.get("code").asInt())
        assertEquals("Unknown tool: send", unknown.get("message").asString())
        assertEquals(McpServer.FORBIDDEN, refusedWith(ForbiddenException("no")).get("code").asInt())
        assertEquals(McpServer.CONFLICT, refusedWith(ConflictException("disabled")).get("code").asInt())
        val limited = refusedWith(RetryLaterException("slow down", Duration.ofMillis(1500), status = HttpStatus.TOO_MANY_REQUESTS))
        assertEquals(McpServer.RATE_LIMITED, limited.get("code").asInt())
        assertEquals(2, limited.get("data").get("retryAfter").asInt())
        val invalid = refusedWith(ValidationException("Invalid input", listOf(FieldViolation("input.fields.name", "required"), FieldViolation("input", "big"))))
        assertEquals(-32602, invalid.get("code").asInt())
        val errors = invalid.get("data").get("errors")
        assertEquals(listOf("arguments.fields.name", "arguments"), (0 until errors.size()).map { errors.get(it).get("field").asString() })
        val unexpected = refusedWith(IllegalStateException("secret input"))
        assertEquals(-32603, unexpected.get("code").asInt())
        assertFalse(unexpected.toString().contains("secret input"))
        outcome = { _, _ -> throw UnauthorizedException("Authentication required") }
        assertThrows<UnauthorizedException> { handle("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"send"}}""") }
    }

    @Test
    fun `a call needs a tool name and object arguments`() {
        assertEquals(-32602, rpc("tools/call").get("error").get("code").asInt())
        assertEquals(-32602, rpc("tools/call", mapOf("name" to 1)).get("error").get("code").asInt())
        assertEquals(-32602, rpc("tools/call", mapOf("name" to "send", "arguments" to listOf(1))).get("error").get("code").asInt())
        assertTrue(calls.isEmpty())
    }
}
