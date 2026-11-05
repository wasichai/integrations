package wasichai.integrations.connector.openapi

import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.HttpErrors
import wasichai.integrations.sdk.ProviderResponse
import java.nio.charset.StandardCharsets

// by status only: an openapi document does not say what an error body means. 401 AuthExpired, 403 PermissionDenied,
// 404/410 NotFound, 409/412 Conflict, 429 RateLimited (Retry-After), 5xx Transient, any other 4xx Permanent.
// the provider's body goes to details only, never to the message, which may reach logs
internal object OpenApiErrors {
    private const val MAX_BODY = 2000
    private val KNOWN = setOf(401, 403, 404, 409, 410, 412, 429)

    fun classify(response: ProviderResponse): ConnectorException {
        if (response.status in 400..499 && response.status !in KNOWN) {
            val body = String(response.body, StandardCharsets.UTF_8).take(MAX_BODY)
            return ConnectorException.Permanent("provider answered ${response.status}", if (body.isBlank()) emptyMap() else mapOf("body" to body))
        }
        return HttpErrors.classify(response)
    }
}
