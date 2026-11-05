package wasichai.integrations.connector.slack

import tools.jackson.databind.JsonNode
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.HttpErrors
import wasichai.integrations.sdk.ProviderResponse
import java.time.Duration

// slack's web api answers most failures with http 200 and {"ok": false, "error": "<code>"}; rate limits come as 429 with
// retry-after. provider text goes to details only, never to the message, which may reach logs
internal object SlackErrors {
    // slack sends retry-after with every 429; a minute when it does not (or when ok:false says ratelimited)
    private val RATE_LIMIT_DEFAULT: Duration = Duration.ofMinutes(1)

    private val AUTH = setOf("invalid_auth", "not_authed", "token_revoked", "token_expired", "account_inactive")
    private val PERMISSION =
        setOf(
            "missing_scope",
            "not_in_channel",
            "no_permission",
            "access_denied",
            "not_allowed_token_type",
            "cant_update_message",
            "cant_delete_message",
            "restricted_action",
            "restricted_action_read_only_channel",
            "restricted_action_thread_only_channel",
            "restricted_action_non_threadable_channel",
            "team_access_not_granted",
            "ekm_access_denied",
            "is_archived"
        )
    private val NOT_FOUND = setOf("channel_not_found", "message_not_found", "thread_not_found")
    private val TRANSIENT = setOf("internal_error", "fatal_error", "service_unavailable", "request_timeout")

    // an http failure (not 2xx)
    fun http(response: ProviderResponse): ConnectorException {
        val text = "slack answered ${response.status}"
        if (response.status == 429) return ConnectorException.RateLimited(text, HttpErrors.retryAfter(response) ?: RATE_LIMIT_DEFAULT)
        return when (response.status) {
            in 500..599 -> ConnectorException.Transient(text)
            else -> HttpErrors.classify(response)
        }
    }

    // an ok:false body
    fun api(
        body: JsonNode,
        response: ProviderResponse
    ): ConnectorException {
        val code = body.path("error").asString("unknown_error")
        val text = "slack refused the call: $code"
        return when (code) {
            "ratelimited" -> ConnectorException.RateLimited(text, HttpErrors.retryAfter(response) ?: RATE_LIMIT_DEFAULT)
            in AUTH -> ConnectorException.AuthExpired(text)
            in PERMISSION -> ConnectorException.PermissionDenied(text)
            in NOT_FOUND -> ConnectorException.NotFound(text)
            in TRANSIENT -> ConnectorException.Transient(text)
            else -> ConnectorException.Permanent(text, details(body))
        }
    }

    // the error code is slack's documented enum, safe in the message; free text (warnings, messages) stays in details
    private fun details(body: JsonNode): Map<String, String> {
        val details = mutableMapOf("error" to body.path("error").asString("unknown_error"))
        body.path("needed").asString(null)?.let { details["needed"] = it }
        body.path("warning").asString(null)?.let { details["warning"] = it }
        body
            .path("response_metadata")
            .path("messages")
            .values()
            .mapNotNull { it.asString(null) }
            .takeIf { it.isNotEmpty() }
            ?.let { details["messages"] = it.joinToString("; ") }
        return details
    }
}
