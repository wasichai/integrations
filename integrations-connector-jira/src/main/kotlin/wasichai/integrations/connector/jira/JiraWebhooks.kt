package wasichai.integrations.connector.jira

import wasichai.integrations.sdk.ProviderEvent
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookVerification
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// two documented ways a jira delivery proves where it comes from, both keyed by the endpoint's stored secret:
// - a webhook with a secret (admin webhooks) signs the raw body: X-Hub-Signature: sha256=<hex hmac-sha256>
// - a dynamic webhook of an oauth 2.0 app carries Authorization: Bearer <jwt> signed with the app's client secret.
//   atlassian documents the key, not the claims: only the hs256 signature and a present exp are checked (ADR-0016).
// no handshake: the admin pastes the secret when creating the endpoint
internal class JiraWebhooks(
    private val clock: Clock = Clock.systemUTC()
) {
    fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification {
        if (secret.isNullOrEmpty()) return WebhookVerification.Invalid("endpoint has no secret")
        request.header("X-Hub-Signature")?.let { signature ->
            val expected = "sha256=" + hex(hmac(secret, request.body))
            return verdict(same(expected, signature.trim()), "signature mismatch")
        }
        val bearer =
            request
                .header("Authorization")
                ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
                ?.substring(7)
                ?.trim()
                ?: return WebhookVerification.Invalid("missing X-Hub-Signature or bearer jwt")
        return jwt(bearer, secret)
    }

    private fun jwt(
        token: String,
        secret: String
    ): WebhookVerification {
        val parts = token.split(".")
        if (parts.size != 3) return WebhookVerification.Invalid("malformed jwt")
        val decoder = Base64.getUrlDecoder()
        val header = runCatching { JiraFields.json.readTree(decoder.decode(parts[0])) }.getOrNull() ?: return WebhookVerification.Invalid("malformed jwt")
        // the algorithm is fixed here, never taken from the token: no "none", no key confusion
        if (header.path("alg").asString("") != "HS256") return WebhookVerification.Invalid("jwt algorithm is not HS256")
        val signature = runCatching { decoder.decode(parts[2]) }.getOrNull() ?: return WebhookVerification.Invalid("malformed jwt")
        val expected = hmac(secret, "${parts[0]}.${parts[1]}".toByteArray())
        if (!MessageDigest.isEqual(expected, signature)) return WebhookVerification.Invalid("jwt signature mismatch")
        val claims = runCatching { JiraFields.json.readTree(decoder.decode(parts[1])) }.getOrNull() ?: return WebhookVerification.Invalid("malformed jwt")
        val exp = claims.path("exp")
        if (exp.isNumber && Instant.ofEpochSecond(exp.asLong()).isBefore(clock.instant())) return WebhookVerification.Invalid("jwt expired")
        return WebhookVerification.Valid
    }

    // a delivery is a hint about one issue: the engine reads the issue again, payload order is never trusted
    fun parse(request: WebhookRequest): List<ProviderEvent> {
        val event = JiraFields.json.readTree(request.body)
        // a delivery about no issue (a project or version event) is still kept, with nothing to fetch
        val issue = event.path("issue")
        return listOf(
            ProviderEvent(
                // the same across atlassian's retries of one delivery
                providerEventId = request.header("X-Atlassian-Webhook-Identifier"),
                eventType = event.path("webhookEvent").asString("unknown"),
                resourceType =
                    issue
                        .path("fields")
                        .path("project")
                        .path("key")
                        .asString(null),
                resourceId = issue.path("id").asString(null),
                occurredAt = event.path("timestamp").takeIf { it.isNumber }?.let { Instant.ofEpochMilli(it.asLong()) }
            )
        )
    }

    private fun verdict(
        ok: Boolean,
        reason: String
    ) = if (ok) WebhookVerification.Valid else WebhookVerification.Invalid(reason)

    // constant time: no timing hint about how much matched
    private fun same(
        a: String,
        b: String
    ) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

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
