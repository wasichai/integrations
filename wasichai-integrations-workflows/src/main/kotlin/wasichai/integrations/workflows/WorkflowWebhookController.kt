package wasichai.integrations.workflows

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

// public, like the connectors' webhooks (IntegrationsSecurityConfiguration permits /api/integrations/webhooks/**):
// the secret in the path authenticates. Idempotency-Key, when sent, starts one run per key
@RestController
class WorkflowWebhookController(
    private val runs: WorkflowRunService
) {
    @PostMapping("${WorkflowRunService.WEBHOOK_BASE}/{id}/{secret}")
    suspend fun receive(
        @PathVariable id: String,
        @PathVariable secret: String,
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) body: ByteArray?
    ): ResponseEntity<StartedView> {
        val (status, started) = runs.receive(id, secret, idempotencyKey, body ?: ByteArray(0))
        return ResponseEntity.status(status).body(started)
    }
}
