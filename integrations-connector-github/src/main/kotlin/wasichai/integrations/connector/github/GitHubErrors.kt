package wasichai.integrations.connector.github

import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.HttpErrors
import wasichai.integrations.sdk.ProviderResponse
import java.time.Duration
import java.time.Instant

// github answers {"message", "errors": [{"resource", "field", "code"}], "documentation_url"}. rate limits come as 403 or
// 429 (primary: x-ratelimit-remaining 0 + x-ratelimit-reset; secondary: retry-after, or a message and wait a minute).
// provider text goes to details only, never to the message, which may reach logs
internal object GitHubErrors {
    // github's advice when a secondary limit names no wait: at least one minute
    private val SECONDARY_DEFAULT: Duration = Duration.ofMinutes(1)

    fun classify(
        response: ProviderResponse,
        now: Instant
    ): ConnectorException {
        val text = "github answered ${response.status}"
        rateLimit(response, now)?.let { return ConnectorException.RateLimited(text, it) }
        return when (response.status) {
            401 -> ConnectorException.AuthExpired(text)
            403 -> ConnectorException.PermissionDenied(text)
            404, 410 -> ConnectorException.NotFound(text)
            409 -> ConnectorException.Conflict(text)
            in 500..599 -> ConnectorException.Transient(text)
            else -> ConnectorException.Permanent(text, details(response))
        }
    }

    // null: not a rate limit. a 403 without any rate limit sign is a permission problem
    private fun rateLimit(
        response: ProviderResponse,
        now: Instant
    ): Duration? {
        if (response.status != 403 && response.status != 429) return null
        HttpErrors.retryAfter(response)?.let { return it }
        if (response.header("x-ratelimit-remaining")?.trim() == "0") {
            val reset = response.header("x-ratelimit-reset")?.trim()?.toLongOrNull() ?: return SECONDARY_DEFAULT
            return Duration.between(now, Instant.ofEpochSecond(reset)).coerceAtLeast(Duration.ZERO)
        }
        val message = message(response).lowercase()
        if (response.status == 429 || "rate limit" in message || "abuse" in message) return SECONDARY_DEFAULT
        return null
    }

    private fun message(response: ProviderResponse): String = runCatching { response.json().path("message").asString("") }.getOrNull() ?: ""

    private fun details(response: ProviderResponse): Map<String, String> {
        val body = runCatching { response.json() }.getOrNull() ?: return emptyMap()
        val details = mutableMapOf<String, String>()
        body.path("message").asString(null)?.let { details["message"] = it }
        body.path("errors").values().forEach { error ->
            val field = error.path("field").asString(null) ?: return@forEach
            // code "custom" carries its own message
            details["field.$field"] = error.path("message").asString(null) ?: error.path("code").asString("invalid")
        }
        return details
    }
}
