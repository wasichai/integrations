package wasichai.integrations.connector.github

import wasichai.integrations.sdk.ProviderEvent
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookVerification
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// github signs the raw body with the webhook's secret: X-Hub-Signature-256: sha256=<hex hmac-sha256>. the legacy sha-1
// X-Hub-Signature is not accepted. the admin pastes the same secret when creating the endpoint, no handshake stores one
internal object GitHubWebhooks {
    fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification {
        if (secret.isNullOrEmpty()) return WebhookVerification.Invalid("endpoint has no secret")
        val signature = request.header("X-Hub-Signature-256")?.trim() ?: return WebhookVerification.Invalid("missing X-Hub-Signature-256")
        val expected = "sha256=" + hex(hmac(secret, request.body))
        // constant time: no timing hint about how much matched
        if (!MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())) return WebhookVerification.Invalid("signature mismatch")
        // github's ping, sent when a hook is created, is a signed check, not a change: answered, not stored
        if (request.header("X-GitHub-Event") == "ping") return WebhookVerification.Handshake(200)
        return WebhookVerification.Valid
    }

    // a delivery is a hint about one issue: the engine reads the issue again, payload order is never trusted.
    // other deliveries (pull requests, pushes...) are kept with nothing to fetch
    fun parse(request: WebhookRequest): List<ProviderEvent> {
        val event = GitHubIssues.json.readTree(request.body)
        val name = request.header("X-GitHub-Event") ?: "unknown"
        val action = event.path("action").asString(null)
        val issue = event.path("issue")
        val number = issue.path("number").takeIf { issue.isObject && !GitHubIssues.isPullRequest(issue) && it.isIntegralNumber }
        return listOf(
            ProviderEvent(
                // a redelivery keeps its X-GitHub-Delivery: the platform drops the repeat by this id
                providerEventId = request.header("X-GitHub-Delivery"),
                eventType = action?.let { "$name.$it" } ?: name,
                resourceType = event.path("repository").path("full_name").asString(null),
                resourceId = number?.asString(),
                // a delivery carries no time of the event itself
                occurredAt = null
            )
        )
    }

    private fun hmac(
        secret: String,
        body: ByteArray
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
}
