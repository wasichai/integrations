package wasichai.integrations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import wasichai.integrations.TestTenant.Companion.call
import java.net.URI

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookApiTest : IntegrationsIntegrationTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var listener: RecordingListener

    private val tenant by lazy { TestTenant.provision(client) }
    private val other by lazy { TestTenant.provision(client) }
    private val connectionId by lazy {
        call(
            client,
            HttpMethod.POST,
            "/api/integrations/connections",
            tenant.admin,
            mapOf(
                "connectorId" to "stub-hooks",
                "authType" to "API_KEY",
                "apiKey" to "k"
            ),
            201
        ).get("id")
            .asString()
    }

    private fun endpoint(secret: String?): Pair<String, String> {
        val body = secret?.let { mapOf("secret" to it) } ?: emptyMap()
        val created = call(client, HttpMethod.POST, "/api/integrations/connections/$connectionId/webhook-endpoints", tenant.admin, body, 201)
        return created.get("id").asString() to URI.create(created.get("url").asString()).path
    }

    private fun deliver(
        path: String,
        body: String,
        signature: String?
    ): Pair<Int, String?> {
        val result =
            client
                .post()
                .uri(path)
                .headers { h -> signature?.let { h.set("X-Stub-Signature", it) } }
                .bodyValue(body.toByteArray())
                .exchange()
                .expectBody(String::class.java)
                .returnResult()
        return result.status.value() to result.responseBody
    }

    private fun events(token: String = tenant.admin) =
        call(client, HttpMethod.GET, "/api/integrations/events?connectionId=$connectionId&limit=200", token, null, 200)

    private fun drain() = repeat(5) { runBlocking { runner.drainOnce() } }

    @Test
    fun `a signed delivery is stored once, queued and handed to listeners`() {
        val (_, path) = endpoint("s3cret")
        assertTrue(path.startsWith("/api/integrations/webhooks/stub-hooks/"))
        val id = "evt-" + TestTenant.unique()
        val body = """{"id":"$id","type":"record.updated","record":"r-1"}"""
        assertEquals(200, deliver(path, body, StubHooks.sign("s3cret", body.toByteArray())).first)
        // the provider retries the same delivery: still one event
        assertEquals(200, deliver(path, body, StubHooks.sign("s3cret", body.toByteArray())).first)
        val stored = events().get("content").let { c -> (0 until c.size()).map { c.get(it) } }.filter { it.get("providerEventId").asString() == id }
        assertEquals(1, stored.size)
        assertEquals("WEBHOOK", stored[0].get("source").asString())
        assertEquals("r-1", stored[0].get("resourceId").asString())
        drain()
        assertTrue(listener.seen.any { it.providerEventId == id })
        val processed = call(client, HttpMethod.GET, "/api/integrations/events/${stored[0].get("eventId").asString()}", tenant.admin, null, 200)
        assertEquals("PROCESSED", processed.get("status").asString())
    }

    @Test
    fun `an event nobody acts on is IGNORED, never lost, and can be replayed`() {
        val (_, path) = endpoint("s3cret")
        val id = "evt-" + TestTenant.unique()
        val body = """{"id":"$id","type":"record.archived","record":"r-2"}"""
        deliver(path, body, StubHooks.sign("s3cret", body.toByteArray()))
        drain()
        val event = events().get("content").let { c -> (0 until c.size()).map { c.get(it) } }.first { it.get("providerEventId").asString() == id }
        assertEquals("IGNORED", event.get("status").asString())
        val eventId = event.get("eventId").asString()
        call(client, HttpMethod.POST, "/api/integrations/events/$eventId/replay", tenant.member(), null, 403)
        call(client, HttpMethod.POST, "/api/integrations/events/$eventId/replay", other.admin, null, 404)
        assertEquals("RECEIVED", call(client, HttpMethod.POST, "/api/integrations/events/$eventId/replay", tenant.admin, null, 200).get("status").asString())
        drain()
        assertEquals(2, listener.seen.count { it.providerEventId == id })
    }

    @Test
    fun `bad signatures and unknown endpoints store nothing`() {
        val (endpointId, path) = endpoint("s3cret")
        val id = "evt-" + TestTenant.unique()
        val body = """{"id":"$id","type":"record.updated","record":"r-3"}"""
        assertEquals(401, deliver(path, body, StubHooks.sign("wrong", body.toByteArray())).first)
        assertEquals(401, deliver(path, body, null).first)
        assertEquals(404, deliver("/api/integrations/webhooks/stub-hooks/${java.util.UUID.randomUUID()}", body, "x").first)
        assertEquals(404, deliver("/api/integrations/webhooks/stub-oauth/$endpointId", body, "x").first)
        assertEquals(404, deliver("/api/integrations/webhooks/stub-hooks/not-a-uuid", body, "x").first)
        val c = events().get("content")
        assertFalse((0 until c.size()).any { c.get(it).get("providerEventId").asString() == id })
    }

    @Test
    fun `a handshake stores the provider's secret once, and only an admin can read it back`() {
        val (endpointId, path) = endpoint(null)
        val (status, answer) = deliver(path, """{"challenge":"chal-1"}""", null)
        assertEquals(200, status)
        assertEquals("chal-1", answer)
        assertEquals(
            "chal-1",
            call(client, HttpMethod.GET, "/api/integrations/webhook-endpoints/$endpointId/verification-token", tenant.admin, null, 200).get("token").asString()
        )
        call(client, HttpMethod.GET, "/api/integrations/webhook-endpoints/$endpointId/verification-token", tenant.member(), null, 403)
        call(client, HttpMethod.GET, "/api/integrations/webhook-endpoints/$endpointId/verification-token", other.admin, null, 404)
        // a second handshake cannot swap the secret
        assertEquals(401, deliver(path, """{"challenge":"evil"}""", null).first)
        val body = """{"id":"evt-${TestTenant.unique()}","type":"record.updated","record":"r"}"""
        assertEquals(200, deliver(path, body, StubHooks.sign("chal-1", body.toByteArray())).first)
        val listed = call(client, HttpMethod.GET, "/api/integrations/connections/$connectionId/webhook-endpoints", tenant.member(), null, 200)
        val mine = (0 until listed.size()).map { listed.get(it) }.first { it.get("id").asString() == endpointId }
        assertEquals("ACTIVE", mine.get("status").asString())
        assertFalse(mine.toString().contains("chal-1"))
    }

    @Test
    fun `events page by cursor and stay within the tenant`() {
        val (_, path) = endpoint("s3cret")
        repeat(3) {
            val body = """{"id":"evt-${TestTenant.unique()}","type":"record.updated","record":"r-$it"}"""
            deliver(path, body, StubHooks.sign("s3cret", body.toByteArray()))
        }
        val first = call(client, HttpMethod.GET, "/api/integrations/events?limit=2", tenant.admin, null, 200)
        assertEquals(2, first.get("content").size())
        val cursor = first.get("nextCursor").asString()
        val second = call(client, HttpMethod.GET, "/api/integrations/events?limit=2&cursor=$cursor", tenant.admin, null, 200)
        assertTrue(second.get("content").size() >= 1)
        assertFalse(
            second
                .get("content")
                .get(0)
                .get("eventId")
                .asString() ==
                first
                    .get("content")
                    .get(1)
                    .get("eventId")
                    .asString()
        )
        call(client, HttpMethod.GET, "/api/integrations/events?cursor=garbage", tenant.admin, null, 400)
        assertEquals(0, call(client, HttpMethod.GET, "/api/integrations/events?connectionId=$connectionId", other.admin, null, 200).get("content").size())
    }
}
