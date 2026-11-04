package wasichai.integrations.connector.salesforce

import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.HttpErrors
import wasichai.integrations.sdk.ProviderResponse

// salesforce answers errors as [{"errorCode": "...", "message": "...", "fields": [...]}]
internal object SalesforceErrors {
    fun classify(response: ProviderResponse): ConnectorException {
        val first =
            runCatching { response.json() }
                .getOrNull()
                ?.let { if (it.isArray) it.firstOrNull() else it }
        val code = first?.get("errorCode")?.asString() ?: return HttpErrors.classify(response)
        val message = "salesforce $code (http ${response.status})"
        return when {
            code == "INVALID_SESSION_ID" -> ConnectorException.AuthExpired(message)
            code == "REQUEST_LIMIT_EXCEEDED" -> ConnectorException.RateLimited(message, HttpErrors.retryAfter(response))
            code == "ENTITY_IS_DELETED" || code == "NOT_FOUND" -> ConnectorException.NotFound(message)
            code.startsWith("INSUFFICIENT_ACCESS") -> ConnectorException.PermissionDenied(message)
            code == "UNABLE_TO_LOCK_ROW" || code == "SERVER_UNAVAILABLE" -> ConnectorException.Transient(message)
            response.status == 412 -> ConnectorException.Conflict(message)
            else -> {
                val details = mutableMapOf("errorCode" to code)
                first.get("message")?.asString()?.let { details["message"] = it }
                first
                    .get("fields")
                    ?.takeIf { it.isArray && !it.isEmpty }
                    ?.let { fields -> details["fields"] = fields.values().joinToString(",") { it.asString() } }
                ConnectorException.Permanent(message, details)
            }
        }
    }
}
