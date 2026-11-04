package wasichai.integrations

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/integrations")
class ConnectionController(
    private val connections: ConnectionService
) {
    @GetMapping("/connections")
    suspend fun list(): List<ConnectionView> = connections.list()

    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody request: CreateConnectionRequest
    ): ConnectionView = connections.create(request)

    @GetMapping("/connections/{id}")
    suspend fun get(
        @PathVariable id: UUID
    ): ConnectionView = connections.get(id)

    @DeleteMapping("/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable id: UUID
    ) = connections.delete(id)

    @PostMapping("/connections/{id}/test")
    suspend fun test(
        @PathVariable id: UUID
    ): ConnectionTestView = connections.test(id)

    @PostMapping("/connections/oauth/start")
    suspend fun start(
        @RequestBody request: OAuthStartRequest
    ): OAuthStartView = connections.startOAuth(request)

    // public (IntegrationsSecurity): the provider sends the browser here with code and state
    @GetMapping("/oauth/callback")
    suspend fun callback(
        @RequestParam(required = false) code: String?,
        @RequestParam(required = false) state: String?,
        @RequestParam(required = false) error: String?
    ): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.FOUND).location(connections.completeOAuth(code, state, error)).build()
}
