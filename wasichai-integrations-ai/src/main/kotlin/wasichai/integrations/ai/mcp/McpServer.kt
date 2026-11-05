package wasichai.integrations.ai.mcp

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.RetryLaterException
import wasichai.core.common.UnauthorizedException
import wasichai.core.common.ValidationException
import wasichai.core.common.WasichaiException
import wasichai.integrations.ai.ExecutionResult
import wasichai.integrations.ai.ExecutionStatus
import wasichai.integrations.ai.ToolCatalog
import wasichai.integrations.ai.ToolKind
import wasichai.integrations.ai.ToolOperation
import wasichai.integrations.ai.ToolView
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

// what the endpoint answers: an http status and a JSON-RPC message, or no body (202, a notification)
data class McpReply(
    val status: Int,
    val body: String?
)

// MCP 2025-06-18 over streamable HTTP, stateless, JSON answers only (ADR-0027). an interface and nothing more: the
// tools are the caller's visible tools of the catalog, a call is ToolExecutions.execute with its whole policy
class McpServer(
    private val json: JsonMapper,
    allowedOrigins: Collection<String>,
    private val pageSize: Int,
    private val tools: suspend () -> List<ToolView>,
    private val execute: suspend (String, JsonNode?) -> ExecutionResult,
    private val version: String = McpServer::class.java.`package`?.implementationVersion ?: "dev"
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val origins = allowedOrigins.map(::origin).toSet()

    private class RpcError(
        val code: Int,
        override val message: String,
        val data: JsonNode? = null
    ) : RuntimeException(message)

    suspend fun handle(
        body: String?,
        origin: String?,
        protocolVersion: String?
    ): McpReply {
        // DNS rebinding: a browser page of another origin never reaches the tools (spec, security warning)
        if (origin != null && origin(origin) !in origins) return refused(HttpStatus.FORBIDDEN, FORBIDDEN, "Origin not allowed")
        // absent: an older client, answered the same (stateless, nothing differs)
        if (protocolVersion != null && protocolVersion.trim() !in SUPPORTED) {
            return refused(
                HttpStatus.BAD_REQUEST,
                INVALID_REQUEST,
                "Unsupported MCP-Protocol-Version '$protocolVersion'; supported: ${SUPPORTED.joinToString()}"
            )
        }
        val message =
            try {
                body?.takeIf { it.isNotBlank() }?.let { json.readTree(it) }
            } catch (_: JacksonException) {
                null
            } ?: return refused(HttpStatus.BAD_REQUEST, PARSE_ERROR, "Parse error")
        if (message.isArray) return refused(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "Batches are not supported (MCP $LATEST)")
        if (!message.isObject || message.get("jsonrpc")?.takeIf { it.isString }?.stringValue() != "2.0") {
            return refused(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "Invalid Request: a JSON-RPC 2.0 object is expected")
        }
        val method = message.get("method")
        val id = message.get("id")
        if (method == null) {
            // a response to a request of ours: this server sends none, so there is nothing it waits for
            if (id != null && (message.has("result") || message.has("error"))) return McpReply(HttpStatus.ACCEPTED.value(), null)
            return refused(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "Invalid Request: method is required")
        }
        if (!method.isString) return refused(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "Invalid Request: method must be a string")
        if (!message.has("id")) {
            // a notification is never answered; initialized and cancelled change nothing in a stateless server
            log.debug("MCP notification {}", method.stringValue())
            return McpReply(HttpStatus.ACCEPTED.value(), null)
        }
        // MCP: an id is a string or an integer, never null
        if (!(id.isString || id.isIntegralNumber)) return refused(HttpStatus.BAD_REQUEST, INVALID_REQUEST, "Invalid Request: id must be a string or an integer")
        val params = message.get("params")?.takeUnless { it.isNull }
        val result =
            try {
                if (params != null && !params.isObject) throw invalidParams("params must be an object")
                dispatch(method.stringValue(), params)
            } catch (e: RpcError) {
                return reply(id, error(e.code, e.message, e.data))
            }
        return reply(id, json.createObjectNode().set("result", result))
    }

    private suspend fun dispatch(
        method: String,
        params: JsonNode?
    ): JsonNode =
        when (method) {
            "initialize" -> initialize(params)
            "ping" -> json.createObjectNode()
            "tools/list" -> list(params)
            "tools/call" -> call(params)
            else -> throw RpcError(METHOD_NOT_FOUND, "Method not found: $method")
        }

    private fun initialize(params: JsonNode?): JsonNode {
        val requested =
            params?.get("protocolVersion")?.takeIf { it.isString }?.stringValue()
                ?: throw invalidParams("params.protocolVersion is required")
        val o = json.createObjectNode()
        // the client's revision when supported, else ours; a client that cannot speak it disconnects
        o.put("protocolVersion", if (requested in SUPPORTED) requested else LATEST)
        o.putObject("capabilities").putObject("tools").put("listChanged", false)
        o
            .putObject("serverInfo")
            .put("name", "wasichai-integrations")
            .put("title", "Wasichai Integrations")
            .put("version", version)
        o.put("instructions", INSTRUCTIONS)
        return o
    }

    // the tools the caller may call now: the catalog's per-caller visibility, enabled only, by name
    private suspend fun list(params: JsonNode?): JsonNode {
        val after =
            params?.get("cursor")?.takeUnless { it.isNull }?.let { c ->
                val name =
                    c.takeIf { it.isString }?.stringValue()?.let {
                        try {
                            String(Base64.getUrlDecoder().decode(it))
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }
                if (name == null || !ToolCatalog.NAME.matches(name)) throw invalidParams("Invalid cursor")
                name
            }
        val callable = tools().filter { it.enabled && (after == null || it.name > after) }.sortedBy { it.name }
        val page = callable.take(pageSize)
        val o = json.createObjectNode()
        val array = o.putArray("tools")
        page.forEach { array.add(definition(it)) }
        if (callable.size > page.size) o.put("nextCursor", Base64.getUrlEncoder().withoutPadding().encodeToString(page.last().name.toByteArray()))
        return o
    }

    private fun definition(t: ToolView): JsonNode {
        val o = json.createObjectNode()
        o.put("name", t.name)
        o.put("description", t.description)
        o.set("inputSchema", t.inputSchema)
        // hints only, a client trusts none of them; approval is what guards a write
        val readOnly = t.kind == ToolKind.RESOURCE && t.operation?.writes == false
        val destructive = t.kind == ToolKind.ACTION || t.operation in setOf(ToolOperation.UPDATE, ToolOperation.UPSERT, ToolOperation.DELETE)
        o
            .putObject("annotations")
            .put("readOnlyHint", readOnly)
            .put("destructiveHint", !readOnly && destructive)
            .put("openWorldHint", true)
        return o
    }

    private suspend fun call(params: JsonNode?): JsonNode {
        val name = params?.get("name")?.takeIf { it.isString }?.stringValue() ?: throw invalidParams("params.name: a tool name is required")
        val arguments = params.get("arguments")?.takeUnless { it.isNull }
        if (arguments != null && !arguments.isObject) throw invalidParams("params.arguments must be an object")
        val result =
            try {
                execute(name, arguments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnauthorizedException) {
                throw e
            } catch (e: WasichaiException) {
                throw mapped(name, e)
            } catch (e: Exception) {
                // the class only: a message may quote input
                log.warn("MCP call of '{}' failed unexpectedly: {}", name, e.javaClass.name)
                throw RpcError(INTERNAL_ERROR, "Internal error")
            }
        return result(result)
    }

    // the REST policy's answers, as JSON-RPC errors (ADR-0027)
    private fun mapped(
        name: String,
        e: WasichaiException
    ): RpcError =
        when {
            e is NotFoundException -> RpcError(INVALID_PARAMS, "Unknown tool: $name")
            e is ValidationException -> {
                val data = json.createObjectNode()
                val errors = data.putArray("errors")
                e.violations.forEach { v ->
                    errors.addObject().put("field", v.field.replaceFirst(Regex("^input(?=$|[.\\[])"), "arguments")).put("message", v.message)
                }
                RpcError(INVALID_PARAMS, e.message, data)
            }
            e is ForbiddenException -> RpcError(FORBIDDEN, e.message)
            e is RetryLaterException && e.status == HttpStatus.TOO_MANY_REQUESTS -> {
                val seconds = (e.retryAfter.toMillis() + 999) / 1000
                RpcError(RATE_LIMITED, e.message, json.createObjectNode().put("retryAfter", seconds.coerceAtLeast(1)))
            }
            e.status == HttpStatus.CONFLICT -> RpcError(CONFLICT, e.message)
            else -> RpcError(INTERNAL_ERROR, e.message)
        }

    private fun result(r: ExecutionResult): JsonNode {
        val e = r.execution
        val o = json.createObjectNode()
        val content = o.putArray("content")
        o.putObject("_meta").put(META_EXECUTION_ID, e.id.toString())
        when {
            r.pending -> {
                val pending =
                    json
                        .createObjectNode()
                        .put("executionId", e.id.toString())
                        .put("tool", e.toolName)
                        .put("status", e.status.name)
                        .put("statusUrl", "$EXECUTIONS${e.id}")
                content
                    .addObject()
                    .put("type", "text")
                    .put(
                        "text",
                        "Execution ${e.id} of tool '${e.toolName}' waits for a person's approval; it has not run. " +
                            "Its outcome: GET $EXECUTIONS${e.id}"
                    )
                o.set("structuredContent", pending)
                o.put("isError", false)
            }
            e.status == ExecutionStatus.SUCCEEDED -> {
                val output = e.output ?: json.nullNode()
                content.addObject().put("type", "text").put("text", json.writeValueAsString(output))
                if (output.isObject) o.set("structuredContent", output)
                o.put("isError", false)
            }
            else -> {
                // the provider or the tool failed: an outcome the model may act on, not a protocol error
                val failure =
                    json
                        .createObjectNode()
                        .put("executionId", e.id.toString())
                        .put("status", e.status.name)
                        .put("error", e.error)
                        .put("errorKind", e.errorKind)
                content.addObject().put("type", "text").put("text", json.writeValueAsString(failure))
                o.put("isError", true)
            }
        }
        return o
    }

    private fun invalidParams(message: String) = RpcError(INVALID_PARAMS, message)

    private fun error(
        code: Int,
        message: String,
        data: JsonNode? = null
    ): ObjectNode {
        val o = json.createObjectNode()
        val e = o.putObject("error").put("code", code).put("message", message)
        if (data != null) e.set("data", data)
        return o
    }

    private fun reply(
        id: JsonNode?,
        message: ObjectNode
    ): McpReply {
        val o = json.createObjectNode().put("jsonrpc", "2.0")
        o.set("id", id ?: json.nullNode())
        o.setAll(message)
        return McpReply(HttpStatus.OK.value(), json.writeValueAsString(o))
    }

    // the input could not be taken as a request: an http error whose body is a JSON-RPC error without an id
    private fun refused(
        status: HttpStatus,
        code: Int,
        message: String
    ): McpReply = reply(null, error(code, message)).copy(status = status.value())

    companion object {
        const val LATEST = "2025-06-18"
        val SUPPORTED = setOf(LATEST)

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602
        const val INTERNAL_ERROR = -32603

        // the implementation-defined range of JSON-RPC: the REST policy's 403, 409 and 429
        const val FORBIDDEN = -32001
        const val CONFLICT = -32002
        const val RATE_LIMITED = -32003

        const val META_EXECUTION_ID = "dev.wasichai/executionId"
        const val EXECUTIONS = "/api/integrations/ai/executions/"

        const val INSTRUCTIONS =
            "Tools a tenant administrator of this Wasichai organization exposed. A tool that needs approval does not run " +
                "when called: the result says it waits, with its execution id; a person approves or rejects it."

        // scheme://host[:port], lower case, without a trailing slash
        fun origin(value: String): String = value.trim().trimEnd('/').lowercase()
    }
}
