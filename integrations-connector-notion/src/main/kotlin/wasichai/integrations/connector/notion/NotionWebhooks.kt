package wasichai.integrations.connector.notion

import wasichai.integrations.sdk.ProviderEvent
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookVerification
import java.security.MessageDigest
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// notion first posts a verification_token to the endpoint; afterwards every delivery is signed with it:
// X-Notion-Signature: sha256=<hex hmac-sha256(raw body, token)>
internal class NotionWebhooks {
    fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification {
        val signature = request.header("X-Notion-Signature")
        if (signature == null) {
            val token =
                runCatching {
                    NotionProperties.json
                        .readTree(request.body)
                        .path("verification_token")
                        .textOrNull()
                }.getOrNull()
            // a handshake is accepted only while the endpoint has no secret yet: nobody can swap it later
            return when {
                token != null && secret == null -> WebhookVerification.Handshake(secretToStore = token)
                token != null -> WebhookVerification.Invalid("verification already done for this endpoint")
                else -> WebhookVerification.Invalid("missing X-Notion-Signature")
            }
        }
        if (secret == null) return WebhookVerification.Invalid("endpoint not verified yet")
        val expected = "sha256=" + hmac(secret, request.body)
        // constant time: no timing hint about how much of the signature matched
        val same = MessageDigest.isEqual(expected.toByteArray(), signature.trim().toByteArray())
        return if (same) WebhookVerification.Valid else WebhookVerification.Invalid("signature mismatch")
    }

    fun parse(request: WebhookRequest): List<ProviderEvent> {
        val event = NotionProperties.json.readTree(request.body)
        val parent = event.path("data").path("parent")
        return listOf(
            ProviderEvent(
                providerEventId = event.path("id").textOrNull(),
                eventType = event.path("type").asString(),
                // the record's resource is its data source; older payloads name only the database
                resourceType = parent.path("data_source_id").textOrNull() ?: parent.path("id").textOrNull(),
                resourceId = event.path("entity").path("id").textOrNull(),
                occurredAt = event.path("timestamp").textOrNull()?.let { runCatching { Instant.parse(it) }.getOrNull() }
            )
        )
    }

    private fun hmac(
        secret: String,
        body: ByteArray
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }
}
