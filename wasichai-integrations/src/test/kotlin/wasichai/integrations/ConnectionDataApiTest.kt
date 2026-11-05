package wasichai.integrations

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import wasichai.integrations.sdk.HttpMethod as StubMethod

// issue #46: GDPR export and erasure of a connection, usage counters and the retention purge of the core's tables
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionDataApiTest : IntegrationsIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var held: HeldConnections

    @Autowired
    private lateinit var usage: UsageMeter

    @Autowired
    private lateinit var retention: RetentionScheduler

    @Autowired
    private lateinit var meters: IntegrationsMeters

    private val stub = StubProvider.server
    private val tenant by lazy { TestTenant.provision(client).also { configure(it) } }
    private val other by lazy { TestTenant.provision(client) }
    private val m by lazy { schemas.metadata }

    private fun configure(t: TestTenant) {
        call(
            client,
            HttpMethod.PUT,
            "/api/integrations/provider-configs/stub-oauth",
            t.admin,
            mapOf("clientId" to "client-gdpr", "clientSecret" to "client-secret-gdpr"),
            200
        )
    }

    @BeforeEach
    fun provider() {
        stub.reset()
        stub.on(StubMethod.POST, "/token") { _, _ ->
            StubResponse.json("""{"access_token":"at-gdpr-secret","refresh_token":"rt-gdpr-secret","expires_in":3600}""")
        }
        stub.on(StubMethod.GET, "/me") { _, _ -> StubResponse.json("""{"account":"acct-gdpr","name":"Acme"}""") }
        stub.on(StubMethod.POST, "/revoke") { _, _ -> StubResponse(200) }
    }

    private fun form(body: String): Map<String, String> =
        body.split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8)
        }

    private fun connectOauth(): UUID {
        val start = call(client, HttpMethod.POST, "/api/integrations/connections/oauth/start", tenant.admin, mapOf("connectorId" to "stub-oauth"), 200)
        val state = form(URI.create(start.get("authorizationUrl").asString()).rawQuery).getValue("state")
        val back =
            client
                .get()
                .uri("/api/integrations/oauth/callback?code=c&state=$state")
                .exchange()
                .expectStatus()
                .isFound
                .returnResult(String::class.java)
                .responseHeaders.location!!
        return UUID.fromString(form(back.rawQuery).getValue("connectionId"))
    }

    // no account check: a new connection every time
    private fun connectUnchecked(
        t: TestTenant = tenant,
        key: String = "key-gdpr-secret"
    ): UUID =
        UUID.fromString(
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/connections",
                t.admin,
                mapOf("connectorId" to "test-unchecked", "authType" to "API_KEY", "apiKey" to key),
                201
            ).get("id").asString()
        )

    private fun event(
        org: UUID,
        connection: UUID,
        resourceId: String?,
        payload: String,
        status: String = "PROCESSED",
        receivedAt: Instant = Instant.now()
    ): UUID =
        runBlocking {
            db
                .sql(
                    "INSERT INTO $m.integration_events (organization_id, connection_id, connector_id, dedupe_key, event_type, resource_type, " +
                        "resource_id, received_at, source, correlation_id, payload, status) VALUES (:org, :connection, 'test', :dedupe, 'record.updated', " +
                        "'Thing', :resource, :received, 'WEBHOOK', 'c', CAST(:payload AS jsonb), :status) RETURNING id"
                ).bind("org", org)
                .bind("connection", connection)
                .bind("dedupe", UUID.randomUUID().toString())
                .bindNullable("resource", resourceId)
                .bind("received", receivedAt.atOffset(ZoneOffset.UTC))
                .bind("payload", payload)
                .bind("status", status)
                .map { row, _ -> row.get("id", UUID::class.java)!! }
                .one()
                .awaitSingle()
        }

    private fun count(
        sql: String,
        id: UUID
    ): Long =
        runBlocking {
            db
                .sql(sql)
                .bind("id", id)
                .map { row, _ -> row.get(0, Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    private fun exists(
        table: String,
        id: UUID
    ) = count("SELECT count(*) FROM $m.$table WHERE id = :id", id) > 0

    private fun sealed(id: UUID): String =
        runBlocking {
            db
                .sql("SELECT sealed FROM $m.integration_connection_credentials WHERE connection_id = :id")
                .bind("id", id)
                .map { row, _ -> row.get("sealed", String::class.java)!! }
                .one()
                .awaitSingle()
        }

    @Test
    fun `an export holds every section of the connection and no secret`() {
        val id = connectOauth()
        val first = event(tenant.organizationId, id, "r-1", """{"name":"Ana","id":"r-1"}""")
        event(tenant.organizationId, id, "r-2", """{"name":"Bo","id":"r-2"}""")
        val export = call(client, HttpMethod.GET, "/api/integrations/connections/$id/export", tenant.admin, null, 200)
        assertEquals(id.toString(), export.get("connectionId").asString())
        assertEquals("stub-oauth", export.get("connection").get("connectorId").asString())
        val flags = export.get("credentials")
        assertTrue(flags.get("stored").asBoolean() && flags.get("readable").asBoolean())
        assertTrue(flags.get("accessToken").asBoolean() && flags.get("refreshToken").asBoolean())
        assertFalse(flags.get("apiKey").asBoolean())
        val events = export.get("sections").get("events")
        assertEquals(2, events.size())
        assertTrue(events.toString().contains("Ana"))
        assertTrue(export.get("sections").has("webhookEndpoints"))
        assertFalse(export.get("hasMore").asBoolean())
        val text = export.toString()
        listOf("at-gdpr-secret", "rt-gdpr-secret", "client-secret-gdpr", sealed(id)).forEach { assertFalse(text.contains(it), "the export holds $it") }

        // paged: one row per section and page
        val page0 = call(client, HttpMethod.GET, "/api/integrations/connections/$id/export?size=1", tenant.admin, null, 200)
        assertTrue(page0.get("hasMore").asBoolean())
        val page1 = call(client, HttpMethod.GET, "/api/integrations/connections/$id/export?size=1&page=1", tenant.admin, null, 200)
        assertFalse(page1.get("hasMore").asBoolean())
        val ids =
            listOf(page0, page1)
                .map {
                    it
                        .get("sections")
                        .get("events")
                        .get(0)
                        .get("id")
                        .asString()
                }.toSet()
        assertEquals(2, ids.size)
        assertTrue(first.toString() in ids)
        call(client, HttpMethod.GET, "/api/integrations/connections/$id/export?size=100000", tenant.admin, null, 400)
    }

    @Test
    fun `an api key and a webhook secret are flags in the export`() {
        val id = connectUnchecked()
        val hooks =
            UUID.fromString(
                call(
                    client,
                    HttpMethod.POST,
                    "/api/integrations/connections",
                    tenant.admin,
                    mapOf("connectorId" to "stub-hooks", "authType" to "API_KEY", "apiKey" to "hooks-key-secret"),
                    201
                ).get("id").asString()
            )
        call(client, HttpMethod.POST, "/api/integrations/connections/$hooks/webhook-endpoints", tenant.admin, mapOf("secret" to "whsec-gdpr-secret"), 201)
        val export = call(client, HttpMethod.GET, "/api/integrations/connections/$hooks/export", tenant.admin, null, 200)
        assertTrue(export.get("credentials").get("apiKey").asBoolean())
        val endpoint = export.get("sections").get("webhookEndpoints").get(0)
        assertTrue(endpoint.get("secret_set").asBoolean())
        assertNull(endpoint.get("secret_sealed"))
        listOf("hooks-key-secret", "whsec-gdpr-secret", sealed(hooks)).forEach { assertFalse(export.toString().contains(it), "the export holds $it") }
        assertFalse(
            call(client, HttpMethod.GET, "/api/integrations/connections/$id/export", tenant.admin, null, 200).toString().contains("key-gdpr-secret")
        )
    }

    @Test
    fun `members cannot export or erase, and other tenants get 404`() {
        val id = connectUnchecked()
        val member = tenant.member()
        call(client, HttpMethod.GET, "/api/integrations/connections/$id/export", member, null, 403)
        call(client, HttpMethod.POST, "/api/integrations/connections/$id/erase", member, null, 403)
        call(client, HttpMethod.POST, "/api/integrations/connections/$id/erase-record", member, mapOf("resource" to "Thing", "recordId" to "r"), 403)
        call(client, HttpMethod.GET, "/api/integrations/connections/$id/export", other.admin, null, 404)
        call(client, HttpMethod.POST, "/api/integrations/connections/$id/erase", other.admin, null, 404)
        call(client, HttpMethod.POST, "/api/integrations/connections/$id/erase-record", other.admin, mapOf("resource" to "Thing", "recordId" to "r"), 404)
        call(client, HttpMethod.GET, "/api/integrations/connections/$id", tenant.admin, null, 200)
    }

    @Test
    fun `erasing a used connection removes it with its events, endpoints and credentials, revokes and audits`() {
        val id = connectOauth()
        val keep = connectUnchecked()
        val mine = event(tenant.organizationId, id, "r-1", "{}")
        val kept = event(tenant.organizationId, keep, "r-1", "{}")
        held.hold(id, "sync definition 'Contacts'")
        try {
            // the plain delete keeps its safe answer
            call(client, HttpMethod.DELETE, "/api/integrations/connections/$id", tenant.admin, null, 409)
            val erased = call(client, HttpMethod.POST, "/api/integrations/connections/$id/erase", tenant.admin, null, 200)
            assertTrue(erased.get("erased").get("integration_events").asInt() >= 1, erased.toString())
            assertEquals(1, erased.get("erased").get("integration_connections").asInt())
            assertTrue(erased.get("revoked").asBoolean())
        } finally {
            held.release(id)
        }
        assertEquals("rt-gdpr-secret", form(stub.requests.first { it.path == "/revoke" }.bodyText())["token"])
        call(client, HttpMethod.GET, "/api/integrations/connections/$id", tenant.admin, null, 404)
        assertFalse(exists("integration_events", mine))
        assertEquals(0, count("SELECT count(*) FROM $m.integration_connection_credentials WHERE connection_id = :id", id))
        assertTrue(exists("integration_events", kept))
        call(client, HttpMethod.GET, "/api/integrations/connections/$keep", tenant.admin, null, 200)
        assertEquals(1, count("SELECT count(*) FROM $m.audit_log WHERE record_id = :id AND object_name = 'integration_connection'", id))
        call(client, HttpMethod.POST, "/api/integrations/connections/$id/erase", tenant.admin, null, 404)
    }

    @Test
    fun `erasing a record deletes the events keyed by it or mentioning it, nothing else`() {
        val id = connectUnchecked()
        val keyed = event(tenant.organizationId, id, "r-9", """{"name":"Ana"}""")
        val mentioned = event(tenant.organizationId, id, "r-other", """{"links":[{"to":"r-9"}]}""")
        val unrelated = event(tenant.organizationId, id, "r-10", """{"name":"Bo"}""")
        val elsewhere = event(other.organizationId, connectUnchecked(other), "r-9", "{}")
        val erased =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/connections/$id/erase-record",
                tenant.admin,
                mapOf("resource" to "Thing", "recordId" to "r-9"),
                200
            )
        assertEquals(2, erased.get("erased").get("integration_events").asInt(), erased.toString())
        assertEquals("r-9", erased.get("recordId").asString())
        assertFalse(exists("integration_events", keyed))
        assertFalse(exists("integration_events", mentioned))
        assertTrue(exists("integration_events", unrelated))
        assertTrue(exists("integration_events", elsewhere))
        call(client, HttpMethod.POST, "/api/integrations/connections/$id/erase-record", tenant.admin, mapOf("resource" to "Thing"), 400)
        assertEquals(1, count("SELECT count(*) FROM $m.audit_log WHERE record_id = :id AND object_name = 'integration_connection_record'", id))
    }

    @Test
    fun `provider calls are counted per tenant and day, and read back by members`() {
        val id = connectOauth()
        runBlocking { usage.flush() }
        val before = providerCalls(tenant.admin)
        call(client, HttpMethod.POST, "/api/integrations/connections/$id/test", tenant.admin, null, 200)
        runBlocking { usage.flush() }
        assertTrue(providerCalls(tenant.member()) >= before + 1, "a test asks the provider through the connection")
        assertEquals(0L, providerCalls(other.admin))
        call(client, HttpMethod.GET, "/api/integrations/usage?from=2026-11-05&to=2026-11-01", tenant.admin, null, 400)
        call(client, HttpMethod.GET, "/api/integrations/usage?from=2020-01-01&to=2026-11-01", tenant.admin, null, 400)
    }

    private fun providerCalls(token: String): Long {
        val today = LocalDate.now(ZoneOffset.UTC)
        val rows =
            call(client, HttpMethod.GET, "/api/integrations/usage?metric=provider.calls&from=${today.minusDays(1)}&to=${today.plusDays(1)}", token, null, 200)
        return (0 until rows.size()).map { rows.get(it) }.filter { it.get("metric").asString() == "provider.calls" }.sumOf { it.get("count").asLong() }
    }

    @Test
    fun `retention purges old finished events, jobs and usage rows, and keeps the rest`() {
        val id = connectUnchecked()
        val otherConnection = connectUnchecked(other)
        val old = Instant.now().minus(Duration.ofDays(40))
        val oldProcessed = event(tenant.organizationId, id, "r", "{}", "PROCESSED", old)
        val oldFailed = event(tenant.organizationId, id, "r", "{}", "FAILED", old)
        val oldReceived = event(tenant.organizationId, id, "r", "{}", "RECEIVED", old)
        val recent = event(tenant.organizationId, id, "r", "{}", "PROCESSED")
        val otherRecent = event(other.organizationId, otherConnection, "r", "{}", "PROCESSED")
        val otherOld = event(other.organizationId, otherConnection, "r", "{}", "IGNORED", old)
        val oldDone = job(tenant.organizationId, "SUCCEEDED", old)
        val oldDead = job(tenant.organizationId, "DEAD", old)
        val oldPending = job(tenant.organizationId, "PENDING", null)
        val recentDone = job(tenant.organizationId, "SUCCEEDED", Instant.now())
        usageRow(tenant.organizationId, LocalDate.now(ZoneOffset.UTC).minusDays(500))
        usageRow(tenant.organizationId, LocalDate.now(ZoneOffset.UTC).minusDays(1))

        val purged = runBlocking { retention.tick() }
        assertNotNull(purged)
        assertTrue(purged!!.getValue("events") >= 3, purged.toString())
        listOf(oldProcessed, oldFailed, otherOld).forEach { assertFalse(exists("integration_events", it)) }
        listOf(oldReceived, recent, otherRecent).forEach { assertTrue(exists("integration_events", it)) }
        listOf(oldDone, oldDead).forEach { assertFalse(exists("integration_jobs", it)) }
        listOf(oldPending, recentDone).forEach { assertTrue(exists("integration_jobs", it)) }
        assertEquals(
            1,
            count("SELECT count(*) FROM $m.integration_usage_daily WHERE organization_id = :id AND metric = 'test.retention'", tenant.organizationId)
        )
        assertTrue(meters.registry.counter(Retention.PURGED, "task", "events", "table", "integration_events").count() >= 3)
    }

    private fun job(
        org: UUID,
        status: String,
        finishedAt: Instant?
    ): UUID =
        runBlocking {
            db
                .sql(
                    "INSERT INTO $m.integration_jobs (organization_id, kind, status, finished_at, next_attempt_at) " +
                        "VALUES (:org, 'test.retention', :status, :finished, now() + interval '1 day') RETURNING id"
                ).bind("org", org)
                .bind("status", status)
                .let { s ->
                    finishedAt?.let { s.bind("finished", it.atOffset(ZoneOffset.UTC)) } ?: s.bindNull("finished", java.time.OffsetDateTime::class.java)
                }.map { row, _ -> row.get("id", UUID::class.java)!! }
                .one()
                .awaitSingle()
        }

    private fun usageRow(
        org: UUID,
        day: LocalDate
    ) = runBlocking {
        db
            .sql("INSERT INTO $m.integration_usage_daily (organization_id, day, metric, count) VALUES (:org, :day, 'test.retention', 1)")
            .bind("org", org)
            .bind("day", day)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }
}
