package wasichai.integrations.connector.notion

import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.HttpErrors
import wasichai.integrations.sdk.ProviderResponse

// notion answers {"object":"error","status":..,"code":..,"message":..}. the code is more precise than the status.
internal object NotionErrors {
    fun classify(response: ProviderResponse): ConnectorException {
        val body = runCatching { response.json() }.getOrNull()
        val code = body?.path("code")?.textOrNull()
        val message = "notion ${response.status} ${code ?: ""}: ${body?.path("message")?.textOrNull() ?: ""}".trim()
        return when {
            code == "rate_limited" || response.status == 429 || response.status == 529 ->
                ConnectorException.RateLimited(message, HttpErrors.retryAfter(response))
            code == "unauthorized" -> ConnectorException.AuthExpired(message)
            code == "restricted_resource" -> ConnectorException.PermissionDenied(message)
            code == "object_not_found" -> ConnectorException.NotFound(message)
            // notion's conflict_error is a transaction clash on its side: trying again is the documented answer
            code == "conflict_error" -> ConnectorException.Transient(message)
            code in setOf("internal_server_error", "service_unavailable", "gateway_timeout", "database_connection_unavailable") ->
                ConnectorException.Transient(message)
            code in setOf("validation_error", "invalid_json", "invalid_request_url", "invalid_request", "missing_version") ->
                ConnectorException.Permanent(message, mapOf("code" to code!!))
            else -> HttpErrors.classify(response)
        }
    }
}
