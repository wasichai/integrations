package wasichai.integrations.sync

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
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ValidationException
import java.util.UUID

// the ETag of a definition is its version; PUT needs If-Match
@RestController
@RequestMapping("/api/integrations/sync-definitions")
class SyncDefinitionController(
    private val definitions: SyncDefinitionService
) {
    @GetMapping
    suspend fun list(): List<SyncDefinitionView> = definitions.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @RequestBody request: SyncDefinitionRequest
    ): SyncDefinitionView = definitions.create(request)

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: UUID
    ): ResponseEntity<SyncDefinitionView> = tagged(definitions.get(id))

    @PutMapping("/{id}")
    suspend fun replace(
        @PathVariable id: UUID,
        @RequestBody request: SyncDefinitionRequest,
        @RequestHeader("If-Match", required = false) ifMatch: String?
    ): ResponseEntity<SyncDefinitionView> = tagged(definitions.replace(id, request, version(ifMatch)))

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable id: UUID
    ) = definitions.delete(id)

    @PostMapping("/{id}/validate")
    suspend fun validate(
        @PathVariable id: UUID
    ): ValidationView = definitions.validate(id)

    @PostMapping("/{id}/activate")
    suspend fun activate(
        @PathVariable id: UUID
    ): SyncDefinitionView = definitions.activate(id)

    @PostMapping("/{id}/deactivate")
    suspend fun deactivate(
        @PathVariable id: UUID
    ): SyncDefinitionView = definitions.deactivate(id)

    private fun tagged(view: SyncDefinitionView) = ResponseEntity.ok().eTag("\"${view.version}\"").body(view)

    private fun version(ifMatch: String?): Int? =
        ifMatch?.let {
            it
                .trim()
                .removePrefix("W/")
                .trim('"')
                .toIntOrNull() ?: throw ValidationException("Invalid If-Match", "If-Match", "expected \"<version>\"")
        }
}
