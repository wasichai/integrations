package wasichai.integrations.ai.mcp

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

// the MCP endpoint, behind wasichai's security chain like every /api route: a Bearer JWT, a person's or a
// service account's. the body is read raw so a malformed message is answered as JSON-RPC, not as problem+json
@RestController
@RequestMapping("/api/integrations/mcp")
class McpController(
    private val server: McpServer
) {
    @PostMapping
    suspend fun post(
        @RequestBody(required = false) body: String?,
        @RequestHeader(HttpHeaders.ORIGIN, required = false) origin: String?,
        @RequestHeader(PROTOCOL_VERSION, required = false) protocolVersion: String?
    ): ResponseEntity<String> {
        val reply = server.handle(body, origin, protocolVersion)
        val answer = ResponseEntity.status(reply.status)
        return if (reply.body == null) answer.build() else answer.contentType(MediaType.APPLICATION_JSON).body(reply.body)
    }

    // no server-sent stream and no session to end: stateless, JSON answers only (ADR-0027)
    @RequestMapping(method = [RequestMethod.GET, RequestMethod.DELETE])
    fun notAllowed(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).allow(HttpMethod.POST).build()

    companion object {
        const val PROTOCOL_VERSION = "MCP-Protocol-Version"
    }
}
