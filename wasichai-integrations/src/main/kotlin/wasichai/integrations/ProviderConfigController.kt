package wasichai.integrations

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import wasichai.core.common.ValidationException

// the tenant's oauth apps. PUT creates or replaces (If-Match: "<version>" to replace); the ETag is the version.
@RestController
@RequestMapping("/api/integrations/provider-configs")
class ProviderConfigController(
    private val configs: ProviderConfigService
) {
    @GetMapping
    suspend fun list(): List<ProviderConfigView> = configs.list()

    @GetMapping("/{connectorId}")
    suspend fun get(
        @PathVariable connectorId: String
    ): ResponseEntity<ProviderConfigView> = tagged(configs.get(connectorId))

    @PutMapping("/{connectorId}")
    suspend fun put(
        @PathVariable connectorId: String,
        @RequestBody request: ProviderConfigRequest,
        @RequestHeader("If-Match", required = false) ifMatch: String?
    ): ResponseEntity<ProviderConfigView> = tagged(configs.put(connectorId, request, version(ifMatch)))

    @DeleteMapping("/{connectorId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @PathVariable connectorId: String
    ) = configs.delete(connectorId)

    private fun tagged(view: ProviderConfigView) = ResponseEntity.ok().eTag("\"${view.version}\"").body(view)

    private fun version(ifMatch: String?): Int? =
        ifMatch?.let {
            it
                .trim()
                .removePrefix("W/")
                .trim('"')
                .toIntOrNull() ?: throw ValidationException("Invalid If-Match", "If-Match", "expected \"<version>\"")
        }
}
