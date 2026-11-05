package wasichai.integrations.connector.slack

import tools.jackson.databind.JsonNode
import wasichai.integrations.sdk.ProviderEvent
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookVerification
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

// the events api signs every request with the app's signing secret: X-Slack-Signature: v0=<hex hmac-sha256 of
// "v0:{X-Slack-Request-Timestamp}:{raw body}">. a timestamp more than five minutes off is refused (replays).
// the admin pastes the signing secret when creating the endpoint; slack's url_verification is answered, not stored
internal class SlackWebhooks(
    private val clock: Clock = Clock.systemUTC()
) {
    fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification {
        if (secret.isNullOrEmpty()) return WebhookVerification.Invalid("endpoint has no signing secret")
        val signature = request.header("X-Slack-Signature")?.trim() ?: return WebhookVerification.Invalid("missing X-Slack-Signature")
        val timestamp =
            request.header("X-Slack-Request-Timestamp")?.trim()?.toLongOrNull()
                ?: return WebhookVerification.Invalid("missing X-Slack-Request-Timestamp")
        if (abs(clock.instant().epochSecond - timestamp) > MAX_SKEW.seconds) return WebhookVerification.Invalid("timestamp outside five minutes")
        val expected = "v0=" + hex(hmac(secret, "v0:$timestamp:".toByteArray() + request.body))
        // constant time: no timing hint about how much matched
        if (!MessageDigest.isEqual(expected.toByteArray(), signature.toByteArray())) return WebhookVerification.Invalid("signature mismatch")
        // the subscription check: echo the challenge, nothing to store (the secret was pasted, not handed over)
        val body = runCatching { SlackMessages.json.readTree(request.body) }.getOrNull()
        if (body != null && body.path("type").asString(null) == "url_verification") {
            val challenge = body.path("challenge").asString(null) ?: return WebhookVerification.Invalid("url_verification without challenge")
            return WebhookVerification.Handshake(200, challenge.toByteArray())
        }
        return WebhookVerification.Valid
    }

    // a delivery is a hint about one message: the engine reads it again, payload order is never trusted.
    // a retry (X-Slack-Retry-Num) keeps its event_id, so the platform drops the repeat
    fun parse(request: WebhookRequest): List<ProviderEvent> {
        val body = SlackMessages.json.readTree(request.body)
        val type = body.path("type").asString("unknown")
        val event = body.path("event")
        if (type != "event_callback" || !event.isObject) {
            // app_rate_limited and the like: kept, with nothing to fetch
            return listOf(ProviderEvent(body.path("event_id").asString(null), type, null, null, null))
        }
        val eventType = event.path("type").asString("unknown")
        val subtype = event.path("subtype").asString(null)
        return listOf(
            ProviderEvent(
                providerEventId = body.path("event_id").asString(null),
                eventType = subtype?.let { "$eventType.$it" } ?: eventType,
                resourceType = channel(event),
                resourceId = if (eventType == "message") messageTs(event, subtype) else null,
                occurredAt = body.path("event_time").takeIf { it.isIntegralNumber }?.let { Instant.ofEpochSecond(it.asLong()) }
            )
        )
    }

    private fun channel(event: JsonNode): String? {
        val channel = event.path("channel")
        return when {
            channel.isString -> channel.asString()
            channel.isObject -> channel.path("id").asString(null)
            else -> event.path("item").path("channel").asString(null)
        }
    }

    // the record a message event is about: the message itself, or for a thread reply its parent, whose reply_count
    // moved (replies are not records, only the channel's history is; a reply also sent to the channel is)
    private fun messageTs(
        event: JsonNode,
        subtype: String?
    ): String? {
        val message =
            when (subtype) {
                "message_changed" -> event.path("message")
                "message_deleted" -> return event.path("deleted_ts").asString(null)
                else -> event
            }
        val ts = message.path("ts").asString(null)
        val thread = message.path("thread_ts").asString(null)
        val reply = thread != null && thread != ts && message.path("subtype").asString(null) != "thread_broadcast"
        return if (reply) thread else ts
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

    companion object {
        val MAX_SKEW: Duration = Duration.ofMinutes(5)
    }
}
