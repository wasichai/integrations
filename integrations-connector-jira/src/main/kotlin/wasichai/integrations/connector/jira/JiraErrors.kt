package wasichai.integrations.connector.jira

import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.HttpErrors
import wasichai.integrations.sdk.ProviderResponse

// jira answers {"errorMessages": [...], "errors": {"field": "message"}}. the status decides the class; the body only
// explains a permanent failure, in details (never in the message, which may reach logs: it can quote issue content)
internal object JiraErrors {
    fun classify(response: ProviderResponse): ConnectorException {
        val text = "jira answered ${response.status}"
        return when (response.status) {
            401 -> ConnectorException.AuthExpired(text)
            403 -> ConnectorException.PermissionDenied(text)
            404 -> ConnectorException.NotFound(text)
            409 -> ConnectorException.Conflict(text)
            429 -> ConnectorException.RateLimited(text, HttpErrors.retryAfter(response))
            in 500..599 -> ConnectorException.Transient(text)
            else -> ConnectorException.Permanent(text, details(response))
        }
    }

    private fun details(response: ProviderResponse): Map<String, String> {
        val body = runCatching { response.json() }.getOrNull() ?: return emptyMap()
        val details = mutableMapOf<String, String>()
        body
            .path("errorMessages")
            .values()
            .map { it.asString() }
            .takeIf { it.isNotEmpty() }
            ?.let { details["errorMessages"] = it.joinToString("; ") }
        body.path("errors").properties().forEach { (field, message) -> details["field.$field"] = message.asString() }
        return details
    }
}
