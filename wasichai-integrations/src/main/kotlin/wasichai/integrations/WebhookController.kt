package wasichai.integrations

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/integrations")
class WebhookController(
    private val webhooks: WebhookService
) {
    @PostMapping("/connections/{id}/webhook-endpoints")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: CreateEndpointRequest?
    ): WebhookEndpointView = webhooks.create(id, request ?: CreateEndpointRequest())

    @GetMapping("/connections/{id}/webhook-endpoints")
    suspend fun list(
        @PathVariable id: UUID
    ): List<WebhookEndpointView> = webhooks.list(id)

    @GetMapping("/webhook-endpoints/{id}/verification-token")
    suspend fun verificationToken(
        @PathVariable id: UUID
    ): VerificationTokenView = webhooks.verificationToken(id)

    @DeleteMapping("/webhook-endpoints/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable id: UUID
    ) = webhooks.delete(id)

    // public (IntegrationsSecurity): the raw bytes, because signatures are computed over them
    @PostMapping("/webhooks/{connectorId}/{endpointId}")
    suspend fun receive(
        @PathVariable connectorId: String,
        @PathVariable endpointId: String,
        @RequestHeader headers: HttpHeaders,
        @RequestBody(required = false) body: ByteArray?
    ): ResponseEntity<ByteArray> {
        val answer = webhooks.receive(connectorId, endpointId, headers.headerSet().associate { it.key to it.value }, body ?: ByteArray(0))
        return ResponseEntity.status(answer.status).body(answer.body)
    }

    @GetMapping("/events")
    suspend fun events(
        @RequestParam(required = false) connectionId: UUID?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?
    ): EventPage = webhooks.events(connectionId, status, cursor, limit)

    @GetMapping("/events/{id}")
    suspend fun event(
        @PathVariable id: UUID
    ): EventView = webhooks.event(id)

    @PostMapping("/events/{id}/replay")
    suspend fun replay(
        @PathVariable id: UUID
    ): EventView = webhooks.replay(id)
}
