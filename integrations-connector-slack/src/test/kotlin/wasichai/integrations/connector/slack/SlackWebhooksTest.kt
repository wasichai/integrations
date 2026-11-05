package wasichai.integrations.connector.slack

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookVerification
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// deliveries shaped as slack's events api docs show them
class SlackWebhooksTest {
    private val now = Instant.parse("2026-11-05T10:00:00Z")
    private val slack = SlackConnector(clock = Clock.fixed(now, ZoneOffset.UTC))
    private val secret = "signing-secret"

    private fun callback(
        event: String,
        id: String = "Ev08MFMKH6"
    ) = """
        {"token":"XXYYZZ","team_id":"T12345678","api_app_id":"A0123ABCD","event":$event,"type":"event_callback",
         "event_id":"$id","event_time":1762336800,"authorizations":[{"team_id":"T12345678","user_id":"U0BOT","is_bot":true}]}
        """.trimIndent().toByteArray()

    private val posted =
        callback("""{"type":"message","channel":"C012AB3CD","user":"U012AB3CDE","text":"Hello","ts":"1762336800.000100","channel_type":"channel"}""")

    private fun sign(
        body: ByteArray,
        timestamp: Long,
        key: String = secret
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return "v0=" + mac.doFinal("v0:$timestamp:".toByteArray() + body).joinToString("") { "%02x".format(it) }
    }

    private fun request(
        headers: Map<String, String>,
        body: ByteArray
    ) = WebhookRequest(headers.mapValues { listOf(it.value) }, body)

    private fun delivery(
        body: ByteArray,
        timestamp: Long = now.epochSecond,
        extra: Map<String, String> = emptyMap()
    ) = request(mapOf("X-Slack-Request-Timestamp" to "$timestamp", "X-Slack-Signature" to sign(body, timestamp)) + extra, body)

    @Test
    fun `slack's documented signature example verifies`() {
        // docs.slack.dev, "Verifying requests from Slack": this signing secret, timestamp and body give this signature
        val body =
            (
                "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V&channel_name=foobar" +
                    "&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text=&response_url=https%3A%2F%2Fhooks.slack.com" +
                    "%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c"
            ).toByteArray()
        val signature = "v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503"
        val then = SlackConnector(clock = Clock.fixed(Instant.ofEpochSecond(1531420618), ZoneOffset.UTC))
        val documented = request(mapOf("X-Slack-Request-Timestamp" to "1531420618", "X-Slack-Signature" to signature), body)
        assertEquals(WebhookVerification.Valid, then.verify(documented, "8f742231b10e8888abcd99yyyzzz85a5"))
        assertEquals(signature, sign(body, 1531420618, "8f742231b10e8888abcd99yyyzzz85a5"))
        // the same delivery replayed today is too old
        assertInstanceOf(WebhookVerification.Invalid::class.java, slack.verify(documented, "8f742231b10e8888abcd99yyyzzz85a5"))
    }

    @Test
    fun `a signed delivery is valid, a tampered, mis-keyed, unsigned or unkeyed one is not`() {
        assertEquals(WebhookVerification.Valid, slack.verify(delivery(posted), secret))
        val tampered = posted.copyOf().also { it[20] = 'X'.code.toByte() }
        val signed = delivery(posted)
        assertInstanceOf(WebhookVerification.Invalid::class.java, slack.verify(WebhookRequest(signed.headers, tampered), secret))
        assertInstanceOf(WebhookVerification.Invalid::class.java, slack.verify(delivery(posted), "other"))
        assertInstanceOf(WebhookVerification.Invalid::class.java, slack.verify(request(emptyMap(), posted), secret))
        assertInstanceOf(
            WebhookVerification.Invalid::class.java,
            slack.verify(request(mapOf("X-Slack-Signature" to sign(posted, now.epochSecond)), posted), secret),
            "no timestamp"
        )
        assertInstanceOf(WebhookVerification.Invalid::class.java, slack.verify(delivery(posted), null))
    }

    @Test
    fun `a timestamp more than five minutes off is refused, even correctly signed`() {
        assertEquals(WebhookVerification.Valid, slack.verify(delivery(posted, now.epochSecond - 300), secret))
        assertEquals(WebhookVerification.Valid, slack.verify(delivery(posted, now.epochSecond + 300), secret))
        assertInstanceOf(WebhookVerification.Invalid::class.java, slack.verify(delivery(posted, now.epochSecond - 301), secret))
        assertInstanceOf(WebhookVerification.Invalid::class.java, slack.verify(delivery(posted, now.epochSecond + 301), secret))
        // the signed timestamp is the header's: changing it breaks the signature
        val moved = delivery(posted).headers + ("X-Slack-Request-Timestamp" to listOf("${now.epochSecond - 1}"))
        assertInstanceOf(WebhookVerification.Invalid::class.java, slack.verify(WebhookRequest(moved, posted), secret))
    }

    @Test
    fun `a signed url_verification is answered with its challenge, an unsigned one is refused`() {
        val challenge =
            """{"token":"Jhj5dZrVaK7ZwHHjRyZWjbDl","challenge":"3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P","type":"url_verification"}"""
                .toByteArray()
        val answer = slack.verify(delivery(challenge), secret) as WebhookVerification.Handshake
        assertEquals(200, answer.status)
        assertArrayEquals("3eZbrw1aBm2rZgRNFdxV2595E9CY3gmdALWMmHkvFXO7tYXAYM8P".toByteArray(), answer.responseBody)
        assertNull(answer.secretToStore)
        assertInstanceOf(WebhookVerification.Invalid::class.java, slack.verify(request(emptyMap(), challenge), secret))
    }

    @Test
    fun `a new message is a hint about it, identified by event_id`() {
        val parsed = slack.parse(delivery(posted)).single()
        assertEquals("Ev08MFMKH6", parsed.providerEventId)
        assertEquals("message", parsed.eventType)
        assertEquals("C012AB3CD", parsed.resourceType)
        assertEquals("1762336800.000100", parsed.resourceId)
        assertEquals(Instant.ofEpochSecond(1762336800), parsed.occurredAt)
    }

    @Test
    fun `a retry keeps its event_id, so the platform drops the repeat`() {
        val first = slack.parse(delivery(posted)).single()
        val retry = delivery(posted, extra = mapOf("X-Slack-Retry-Num" to "1", "X-Slack-Retry-Reason" to "http_timeout"))
        assertEquals(WebhookVerification.Valid, slack.verify(retry, secret))
        assertEquals(first, slack.parse(retry).single())
    }

    @Test
    fun `edits and deletions point at the message, thread replies at their parent`() {
        val changed =
            callback(
                """{"type":"message","subtype":"message_changed","hidden":true,"channel":"C012AB3CD","ts":"1762336900.000200",
                   |"message":{"type":"message","user":"U012AB3CDE","text":"Hello!","ts":"1762336800.000100",
                   |"edited":{"user":"U012AB3CDE","ts":"1762336900.000000"}},"previous_message":{"type":"message","ts":"1762336800.000100"}}
                """.trimMargin()
            )
        val edit = slack.parse(delivery(changed)).single()
        assertEquals("message.message_changed", edit.eventType)
        assertEquals("1762336800.000100", edit.resourceId)
        val deleted =
            callback(
                """{"type":"message","subtype":"message_deleted","hidden":true,"channel":"C012AB3CD","ts":"1762337000.000300","deleted_ts":"1762336800.000100"}"""
            )
        assertEquals("1762336800.000100", slack.parse(delivery(deleted)).single().resourceId)
        val reply = callback("""{"type":"message","channel":"C012AB3CD","user":"U2","text":"re","ts":"1762337100.000400","thread_ts":"1762336800.000100"}""")
        assertEquals("1762336800.000100", slack.parse(delivery(reply)).single().resourceId)
        val broadcast =
            callback(
                """{"type":"message","subtype":"thread_broadcast","channel":"C012AB3CD","user":"U2","text":"re","ts":"1762337100.000400",
                   |"thread_ts":"1762336800.000100"}
                """.trimMargin()
            )
        assertEquals("1762337100.000400", slack.parse(delivery(broadcast)).single().resourceId, "a reply also sent to the channel is a record")
    }

    @Test
    fun `other events are kept with nothing to fetch`() {
        val reaction =
            slack
                .parse(
                    delivery(
                        callback(
                            """{"type":"reaction_added","user":"U2","reaction":"thumbsup","item":{"type":"message","channel":"C012AB3CD","ts":"1762336800.000100"}}"""
                        )
                    )
                ).single()
        assertEquals("reaction_added", reaction.eventType)
        assertEquals("C012AB3CD", reaction.resourceType)
        assertNull(reaction.resourceId)
        val limited =
            slack
                .parse(
                    delivery(
                        """{"token":"Jhj5dZrVaK7ZwHHjRyZWjbDl","type":"app_rate_limited","team_id":"T123456","minute_rate_limited":1518467820}""".toByteArray()
                    )
                ).single()
        assertEquals("app_rate_limited", limited.eventType)
        assertNull(limited.providerEventId)
    }
}
