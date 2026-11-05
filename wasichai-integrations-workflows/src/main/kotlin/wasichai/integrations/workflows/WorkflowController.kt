package wasichai.integrations.workflows

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ValidationException
import java.util.UUID

// the ETag of a workflow is its version; PUT needs If-Match, publish takes it optionally
@RestController
@RequestMapping("/api/integrations/workflows")
class WorkflowController(
    private val workflows: WorkflowService,
    private val runs: WorkflowRunService
) {
    @GetMapping
    suspend fun list(): List<WorkflowView> = workflows.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody request: WorkflowRequest
    ): WorkflowView = workflows.create(request)

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: UUID
    ): ResponseEntity<WorkflowView> = tagged(workflows.get(id))

    @PutMapping("/{id}")
    suspend fun update(
        @PathVariable id: UUID,
        @RequestBody request: WorkflowRequest,
        @RequestHeader("If-Match", required = false) ifMatch: String?
    ): ResponseEntity<WorkflowView> = tagged(workflows.update(id, request, version(ifMatch)))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable id: UUID
    ) = workflows.delete(id)

    @PostMapping("/{id}/validate")
    suspend fun validate(
        @PathVariable id: UUID
    ): ValidationView = workflows.validate(id)

    @PostMapping("/{id}/publish")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun publish(
        @PathVariable id: UUID,
        @RequestHeader("If-Match", required = false) ifMatch: String?
    ): WorkflowVersionView = workflows.publish(id, version(ifMatch))

    @GetMapping("/{id}/versions")
    suspend fun versions(
        @PathVariable id: UUID
    ): List<WorkflowVersionSummary> = workflows.versions(id)

    @GetMapping("/{id}/versions/{number}")
    suspend fun version(
        @PathVariable id: UUID,
        @PathVariable number: Int
    ): WorkflowVersionView = workflows.version(id, number)

    // runs the published version now, whatever its trigger type; the body's payload is what trigger.payload reads
    @PostMapping("/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun run(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: RunRequest?
    ): ExecutionView = runs.run(id, request)

    @PostMapping("/{id}/pause")
    suspend fun pause(
        @PathVariable id: UUID
    ): WorkflowView = runs.pause(id, true)

    @PostMapping("/{id}/resume")
    suspend fun resume(
        @PathVariable id: UUID
    ): WorkflowView = runs.pause(id, false)

    @PostMapping("/{id}/webhook")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun webhook(
        @PathVariable id: UUID
    ): WebhookUrlView = runs.issueWebhook(id)

    @GetMapping("/{id}/executions")
    suspend fun executions(
        @PathVariable id: UUID,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?
    ): ExecutionPage = runs.executions(id, status, cursor, limit)

    @GetMapping("/executions/{executionId}")
    suspend fun execution(
        @PathVariable executionId: UUID
    ): ExecutionDetailView = runs.execution(executionId)

    @PostMapping("/executions/{executionId}/cancel")
    suspend fun cancel(
        @PathVariable executionId: UUID
    ): ExecutionDetailView = runs.cancel(executionId)

    @PostMapping("/executions/{executionId}/steps/{stepId}/approve")
    suspend fun approve(
        @PathVariable executionId: UUID,
        @PathVariable stepId: String,
        @RequestBody(required = false) request: DecisionRequest?
    ): ExecutionDetailView = runs.decide(executionId, stepId, true, request)

    @PostMapping("/executions/{executionId}/steps/{stepId}/reject")
    suspend fun reject(
        @PathVariable executionId: UUID,
        @PathVariable stepId: String,
        @RequestBody(required = false) request: DecisionRequest?
    ): ExecutionDetailView = runs.decide(executionId, stepId, false, request)

    private fun tagged(view: WorkflowView) = ResponseEntity.ok().eTag("\"${view.version}\"").body(view)

    private fun version(ifMatch: String?): Int? =
        ifMatch?.let {
            it
                .trim()
                .removePrefix("W/")
                .trim('"')
                .toIntOrNull() ?: throw ValidationException("Invalid If-Match", "If-Match", "expected \"<version>\"")
        }
}
