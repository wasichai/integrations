package wasichai.integrations.ai

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
import tools.jackson.databind.JsonNode
import wasichai.core.common.ValidationException
import java.util.UUID

// the ETag of a tool is its version; PUT needs If-Match. execute answers 200 when it ran, 202 when it waits
@RestController
@RequestMapping("/api/integrations/ai")
class ToolController(
    private val catalog: ToolCatalog,
    private val executions: ToolExecutions
) {
    @GetMapping("/tools")
    suspend fun list(): List<ToolView> = catalog.list()

    @PostMapping("/tools")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody request: ToolRequest
    ): ToolView = catalog.create(request)

    @GetMapping("/tools/{name}")
    suspend fun get(
        @PathVariable name: String
    ): ResponseEntity<ToolView> = tagged(catalog.get(name))

    @PutMapping("/tools/{name}")
    suspend fun update(
        @PathVariable name: String,
        @RequestBody request: ToolRequest,
        @RequestHeader("If-Match", required = false) ifMatch: String?
    ): ResponseEntity<ToolView> = tagged(catalog.update(name, request, version(ifMatch)))

    @DeleteMapping("/tools/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable name: String
    ) = catalog.delete(name)

    @PostMapping("/tools/{name}/execute")
    suspend fun execute(
        @PathVariable name: String,
        @RequestBody(required = false) input: JsonNode?
    ): ResponseEntity<ExecutionView> = answer(executions.execute(name, input))

    @GetMapping("/executions")
    suspend fun executions(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) tool: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?
    ): ExecutionPage = executions.list(status, tool, cursor, limit)

    @GetMapping("/executions/{id}")
    suspend fun execution(
        @PathVariable id: UUID
    ): ExecutionView = executions.get(id)

    @PostMapping("/executions/{id}/approve")
    suspend fun approve(
        @PathVariable id: UUID
    ): ResponseEntity<ExecutionView> = answer(executions.approve(id))

    @PostMapping("/executions/{id}/reject")
    suspend fun reject(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: RejectRequest?
    ): ExecutionView = executions.reject(id, request?.reason)

    private fun answer(result: ExecutionResult) = ResponseEntity.status(if (result.pending) HttpStatus.ACCEPTED else HttpStatus.OK).body(result.execution)

    private fun tagged(view: ToolView) = ResponseEntity.ok().eTag("\"${view.version}\"").body(view)

    private fun version(ifMatch: String?): Int? =
        ifMatch?.let {
            it
                .trim()
                .removePrefix("W/")
                .trim('"')
                .toIntOrNull() ?: throw ValidationException("Invalid If-Match", "If-Match", "expected \"<version>\"")
        }
}
