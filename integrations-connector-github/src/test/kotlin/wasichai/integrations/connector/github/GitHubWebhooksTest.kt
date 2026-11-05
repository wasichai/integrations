package wasichai.integrations.connector.github

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookVerification
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// deliveries shaped as github's webhook docs show them
class GitHubWebhooksTest {
    private val github = GitHubConnector()
    private val secret = "webhook-secret"
    private val issues =
        """
        {"action":"opened","issue":{"number":1347,"title":"Found a bug","state":"open","updated_at":"2026-11-04T12:00:00Z"},
         "repository":{"id":1296269,"full_name":"octocat/Hello-World"},"sender":{"login":"octocat","id":1}}
        """.trimIndent().toByteArray()

    private fun sign(
        body: ByteArray,
        key: String = secret
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private fun request(
        headers: Map<String, String>,
        body: ByteArray = issues
    ) = WebhookRequest(headers.mapValues { listOf(it.value) }, body)

    private fun delivery(
        event: String,
        body: ByteArray,
        id: String = "72d3162e-cc78-11e3-81ab-4c9367dc0958"
    ) = request(mapOf("X-GitHub-Event" to event, "X-GitHub-Delivery" to id, "X-Hub-Signature-256" to sign(body)), body)

    @Test
    fun `github's documented test vector verifies`() {
        // docs.github.com, "Validating webhook deliveries": this secret and payload give this signature
        val body = "Hello, World!".toByteArray()
        val signature = "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17"
        assertEquals(WebhookVerification.Valid, github.verify(request(mapOf("X-Hub-Signature-256" to signature), body), "It's a Secret to Everybody"))
        assertEquals(signature, sign(body, "It's a Secret to Everybody"))
    }

    @Test
    fun `a signed delivery is valid, a tampered, mis-keyed, unsigned or sha-1 one is not`() {
        assertEquals(WebhookVerification.Valid, github.verify(delivery("issues", issues), secret))
        val tampered = issues.copyOf().also { it[15] = 'X'.code.toByte() }
        assertInstanceOf(WebhookVerification.Invalid::class.java, github.verify(request(mapOf("X-Hub-Signature-256" to sign(issues)), tampered), secret))
        assertInstanceOf(WebhookVerification.Invalid::class.java, github.verify(delivery("issues", issues), "other"))
        assertInstanceOf(WebhookVerification.Invalid::class.java, github.verify(request(emptyMap()), secret))
        assertInstanceOf(WebhookVerification.Invalid::class.java, github.verify(request(mapOf("X-Hub-Signature" to "sha1=abc")), secret))
        assertInstanceOf(WebhookVerification.Invalid::class.java, github.verify(delivery("issues", issues), null))
    }

    @Test
    fun `a signed ping is answered as a handshake, an unsigned one is refused`() {
        val ping = """{"zen":"Keep it logically awesome.","hook_id":123,"hook":{"type":"Repository","events":["issues"]}}""".toByteArray()
        assertEquals(WebhookVerification.Handshake(200), github.verify(delivery("ping", ping), secret))
        assertInstanceOf(WebhookVerification.Invalid::class.java, github.verify(request(mapOf("X-GitHub-Event" to "ping"), ping), secret))
    }

    @Test
    fun `a delivery is a hint about one issue, identified by its delivery id`() {
        val parsed = github.parse(delivery("issues", issues)).single()
        assertEquals("72d3162e-cc78-11e3-81ab-4c9367dc0958", parsed.providerEventId)
        assertEquals("issues.opened", parsed.eventType)
        assertEquals("octocat/Hello-World", parsed.resourceType)
        assertEquals("1347", parsed.resourceId)
        assertNull(parsed.occurredAt)
    }

    @Test
    fun `a redelivery keeps its delivery id, so the platform drops the repeat`() {
        val first = github.parse(delivery("issues", issues)).single()
        val again = delivery("issues", issues)
        assertEquals(WebhookVerification.Valid, github.verify(again, secret))
        assertEquals(first, github.parse(again).single())
        assertEquals("other-id", github.parse(delivery("issues", issues, id = "other-id")).single().providerEventId)
    }

    @Test
    fun `deliveries about pull requests or other things are kept, with nothing to fetch`() {
        val prComment =
            """{"action":"created","issue":{"number":7,"pull_request":{"url":"x"}},"comment":{"id":1},"repository":{"full_name":"octocat/Hello-World"}}"""
                .toByteArray()
        val parsed = github.parse(delivery("issue_comment", prComment)).single()
        assertEquals("issue_comment.created", parsed.eventType)
        assertNull(parsed.resourceId)
        val push = github.parse(delivery("push", """{"ref":"refs/heads/main","repository":{"full_name":"octocat/Hello-World"}}""".toByteArray())).single()
        assertEquals("push", push.eventType)
        assertNull(push.resourceId)
    }
}
