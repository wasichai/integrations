package wasichai.integrations.connector.jira

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookVerification
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// deliveries shaped as atlassian's webhook docs show them; signatures made here, with the documented schemes
class JiraWebhooksTest {
    private val now = Instant.parse("2026-11-04T12:00:00Z")
    private val jira = JiraConnector(clock = Clock.fixed(now, ZoneOffset.UTC))
    private val secret = "app-client-secret"
    private val event =
        """
        {"timestamp":1793793600000,"webhookEvent":"jira:issue_updated","issue_event_type_name":"issue_generic","matchedWebhookIds":[1000],
         "issue":{"id":"10001","key":"PROJ-1","fields":{"project":{"id":"10000","key":"PROJ"},"summary":"First"}},
         "changelog":{"items":[{"field":"summary","fromString":"Old","toString":"First"}]}}
        """.trimIndent().toByteArray()

    private fun hmac(
        key: String,
        data: ByteArray
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hub(body: ByteArray) = "sha256=" + hmac(secret, body).joinToString("") { "%02x".format(it) }

    private fun jwt(
        claims: String,
        alg: String = "HS256",
        key: String = secret
    ): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val head = enc.encodeToString("""{"alg":"$alg","typ":"JWT"}""".toByteArray()) + "." + enc.encodeToString(claims.toByteArray())
        return head + "." + enc.encodeToString(hmac(key, head.toByteArray()))
    }

    private fun request(
        headers: Map<String, String>,
        body: ByteArray = event
    ) = WebhookRequest(headers.mapValues { listOf(it.value) }, body)

    @Test
    fun `an hmac signed delivery is valid, a tampered one is not`() {
        assertEquals(WebhookVerification.Valid, jira.verify(request(mapOf("X-Hub-Signature" to hub(event))), secret))
        val tampered = event.copyOf().also { it[20] = 'X'.code.toByte() }
        assertInstanceOf(WebhookVerification.Invalid::class.java, jira.verify(request(mapOf("X-Hub-Signature" to hub(event)), tampered), secret))
        assertInstanceOf(WebhookVerification.Invalid::class.java, jira.verify(request(mapOf("X-Hub-Signature" to hub(event))), "other"))
    }

    @Test
    fun `an oauth app delivery carries an hs256 jwt signed with the client secret`() {
        val valid = jwt("""{"iss":"x","exp":${now.epochSecond + 60}}""")
        assertEquals(WebhookVerification.Valid, jira.verify(request(mapOf("Authorization" to "Bearer $valid")), secret))
        val noExp = jwt("""{"iss":"x"}""")
        assertEquals(WebhookVerification.Valid, jira.verify(request(mapOf("Authorization" to "Bearer $noExp")), secret))
    }

    @Test
    fun `wrong key, expired, other algorithms, malformed tokens and missing secrets are invalid`() {
        fun invalid(
            headers: Map<String, String>,
            key: String? = secret
        ) = assertInstanceOf(WebhookVerification.Invalid::class.java, jira.verify(request(headers), key))
        invalid(mapOf("Authorization" to "Bearer ${jwt("""{"exp":${now.epochSecond + 60}}""", key = "other")}"))
        invalid(mapOf("Authorization" to "Bearer ${jwt("""{"exp":${now.epochSecond - 1}}""")}"))
        invalid(mapOf("Authorization" to "Bearer ${jwt("{}", alg = "none")}"))
        invalid(mapOf("Authorization" to "Bearer not.a"))
        invalid(mapOf("Authorization" to "Bearer !!.!!.!!"))
        invalid(emptyMap())
        invalid(mapOf("X-Hub-Signature" to hub(event)), key = null)
    }

    @Test
    fun `a delivery is a hint about one issue of one project`() {
        val parsed = jira.parse(request(mapOf("X-Atlassian-Webhook-Identifier" to "wh-1"))).single()
        assertEquals("wh-1", parsed.providerEventId)
        assertEquals("jira:issue_updated", parsed.eventType)
        assertEquals("PROJ", parsed.resourceType)
        assertEquals("10001", parsed.resourceId)
        assertEquals(Instant.ofEpochMilli(1793793600000), parsed.occurredAt)
        val other = jira.parse(request(emptyMap(), """{"webhookEvent":"project_created","project":{"id":"1"}}""".toByteArray())).single()
        assertEquals("project_created", other.eventType)
        assertNull(other.resourceId)
    }
}
