package wasichai.integrations.connector.notion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookVerification
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class NotionWebhooksTest {
    private val notion = NotionConnector()
    private val secret = "secret_token_from_handshake"
    private val event =
        """
        {"id":"evt-1","timestamp":"2026-11-04T10:15:30.000Z","workspace_id":"ws","type":"page.properties_updated",
         "entity":{"id":"${NotionFixtures.PAGE}","type":"page"},
         "data":{"parent":{"id":"db-1","type":"database","data_source_id":"${NotionFixtures.SOURCE}"},"updated_properties":["title"]}}
        """.trimIndent().toByteArray()

    private fun sign(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private fun request(
        body: ByteArray,
        signature: String?
    ) = WebhookRequest(signature?.let { mapOf("X-Notion-Signature" to listOf(it)) } ?: emptyMap(), body)

    @Test
    fun `a correctly signed delivery is valid`() {
        assertEquals(WebhookVerification.Valid, notion.verify(request(event, sign(event)), secret))
    }

    @Test
    fun `tampered body, wrong secret or no signature are invalid`() {
        val tampered = event.copyOf().also { it[10] = 'X'.code.toByte() }
        assertInstanceOf(WebhookVerification.Invalid::class.java, notion.verify(request(tampered, sign(event)), secret))
        assertInstanceOf(WebhookVerification.Invalid::class.java, notion.verify(request(event, sign(event)), "other"))
        assertInstanceOf(WebhookVerification.Invalid::class.java, notion.verify(request(event, null), secret))
        assertInstanceOf(WebhookVerification.Invalid::class.java, notion.verify(request(event, sign(event)), null))
    }

    @Test
    fun `the verification token is a handshake only while the endpoint has no secret`() {
        val handshake = """{"verification_token":"secret_abc"}""".toByteArray()
        val first = notion.verify(request(handshake, null), null)
        assertEquals("secret_abc", (first as WebhookVerification.Handshake).secretToStore)
        assertInstanceOf(WebhookVerification.Invalid::class.java, notion.verify(request(handshake, null), "already"))
    }

    @Test
    fun `parses the event as a hint about one page of one data source`() {
        val parsed = notion.parse(request(event, sign(event))).single()
        assertEquals("evt-1", parsed.providerEventId)
        assertEquals("page.properties_updated", parsed.eventType)
        assertEquals(NotionFixtures.SOURCE, parsed.resourceType)
        assertEquals(NotionFixtures.PAGE, parsed.resourceId)
        assertEquals(Instant.parse("2026-11-04T10:15:30Z"), parsed.occurredAt)
    }
}
