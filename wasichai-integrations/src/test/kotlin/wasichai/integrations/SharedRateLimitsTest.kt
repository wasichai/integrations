package wasichai.integrations

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.TestTenant.Companion.call
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.RateLimitHint
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import wasichai.integrations.sdk.HttpMethod as StubMethod

// two RateLimits on one database stand for two replicas (ADR-0030). assertions count tokens where they can;
// where time is measured it is a lower bound only, never an upper one.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SharedRateLimitsTest : IntegrationsIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var connections: ConnectionRepository

    @Autowired
    private lateinit var credentials: ConnectionCredentials

    @Autowired
    private lateinit var http: PlatformHttp

    private val stub = StubProvider.server
    private val tenant by lazy { TestTenant.provision(client) }
    private val calls = AtomicInteger()
    private val throttled = AtomicInteger()

    private val connection by lazy {
        val created =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/connections",
                tenant.admin,
                mapOf("connectorId" to "stub-key", "authType" to "API_KEY", "apiKey" to "key-shared", "name" to "Shared"),
                201
            )
        runBlocking { connections.find(tenant.organizationId, UUID.fromString(created.get("id").asString()))!! }
    }

    @BeforeEach
    fun provider() {
        stub.reset()
        calls.set(0)
        throttled.set(0)
        stub.on(StubMethod.GET, "/me") { _, _ -> StubResponse.json("""{"account":"acct-shared","name":"Shared"}""") }
        stub.on(StubMethod.GET, "/items") { _, _ ->
            calls.incrementAndGet()
            if (throttled.getAndDecrement() > 0) StubResponse.json("{}", 429, mapOf("Retry-After" to "1")) else StubResponse.json("{}")
        }
        runBlocking {
            db
                .sql("DELETE FROM ${schemas.metadata}.integration_rate_buckets WHERE bucket_key LIKE 'http:%' OR bucket_key LIKE 'test:%'")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    private fun buckets(schema: WasichaiSchemas = schemas) = RateBuckets(db, schema, SimpleMeterRegistry())

    private fun replica(): RateLimits = RateLimits(Clock.systemUTC(), buckets(), SimpleMeterRegistry())

    private fun http(
        limits: RateLimits,
        hint: RateLimitHint
    ) = ConnectionHttp(connection, credentials, http, limits, hint, SimpleMeterRegistry(), null)

    private fun items() = ProviderRequest(StubMethod.GET, "${stub.baseUrl}/items")

    @Test
    fun `two replicas reserving on one key share one burst`() {
        val a = buckets()
        val b = buckets()
        val waits =
            runBlocking {
                coroutineScope {
                    (1..6).map { i -> async { (if (i % 2 == 0) a else b).reserve("test:burst", 1.0, 3)!! } }.awaitAll()
                }
            }
        assertTrue(waits.none { it.paused })
        // three tokens are there; the other three are reserved one second apart, whichever replica asked
        val sorted = waits.map { it.wait.toMillis() }.sorted()
        assertEquals(listOf(0L, 0L, 0L), sorted.take(3), sorted.toString())
        sorted.drop(3).forEachIndexed { i, ms -> assertTrue(ms in (i + 1) * 1000L - 200..(i + 1) * 1000L, sorted.toString()) }
    }

    @Test
    fun `two connection clients on two replicas stay within the connection's rate`() {
        val hint = RateLimitHint(requestsPerSecond = 10.0, burst = 2, scope = "connection")
        val one = http(replica(), hint)
        val two = http(replica(), hint)
        val started = System.nanoTime()
        runBlocking {
            coroutineScope {
                (1..10).map { i -> async { (if (i % 2 == 0) one else two).exchange(items()) } }.awaitAll()
            }
        }
        val elapsed = Duration.ofNanos(System.nanoTime() - started)
        assertEquals(10, calls.get())
        // 2 at once, then 8 at 10 a second: at least 0.8 s together. each replica on its own would take 0.4 s
        assertTrue(elapsed >= Duration.ofMillis(750), "took $elapsed")
    }

    @Test
    fun `a retry-after seen by one replica pauses the other`() {
        val hint = RateLimitHint(requestsPerSecond = 100.0, burst = 10, scope = "connection")
        val seer = replica()
        val other = replica()
        throttled.set(1)
        val refused = runBlocking { http(seer, hint).exchange(items()) }
        assertEquals(429, refused.status)
        val until = runBlocking { other.pausedUntil(connection.id.toString()) }
        assertNotNull(until)
        assertTrue(until!!.isAfter(Instant.now().plusMillis(500)), "paused until $until")
        val started = System.nanoTime()
        val ok = runBlocking { http(other, hint).exchange(items()) }
        assertEquals(200, ok.status)
        assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(700), "the other replica waited the pause out")
        assertNull(runBlocking { other.pausedUntil(connection.id.toString()) })
    }

    @Test
    fun `a refusing bucket only takes a token that is there`() {
        val a = buckets()
        val b = buckets()
        runBlocking {
            assertNull(a.tryTake("test:tool", 2 / 60.0, 2)!!.wait)
            assertNull(b.tryTake("test:tool", 2 / 60.0, 2)!!.wait)
            val refused = a.tryTake("test:tool", 2 / 60.0, 2)!!.wait
            assertNotNull(refused)
            assertTrue(refused!! > Duration.ofSeconds(25) && refused <= Duration.ofSeconds(30), "wait $refused")
            assertNotNull(b.tryTake("test:tool", 2 / 60.0, 2)!!.wait, "a refusal took nothing, still empty")
            a.forget("test:tool")
            assertNull(b.tryTake("test:tool", 2 / 60.0, 2)!!.wait, "forgotten: a full bucket again")
        }
    }

    @Test
    fun `without the database each replica falls back to its own bucket`() {
        val meters = SimpleMeterRegistry()
        val broken = RateBuckets(db, WasichaiSchemas("no_such_schema", schemas.data), meters)
        val limits = RateLimits(Clock.systemUTC(), broken, SimpleMeterRegistry())
        val hint = RateLimitHint(requestsPerSecond = 100.0, burst = 5, scope = "connection")
        runBlocking { repeat(3) { limits.acquire("x", hint) } }
        runBlocking { limits.pause("x", hint, Duration.ofSeconds(1)) }
        assertNotNull(runBlocking { limits.pausedUntil("x") }, "the local pause still holds")
        assertTrue(meters.find("integrations.rate_limit.fallbacks").counters().sumOf { it.count() } >= 4.0)
    }
}
