package wasichai.integrations.it

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.JobRepository
import wasichai.integrations.JobRunner
import wasichai.integrations.it.TestTenant.Companion.call
import wasichai.test.WasichaiIntegrationTest
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// issue #13: salesforce Accounts (A) <-> a notion data source (B) through the whole platform: oauth, discovery, a
// bidirectional definition, runs driven by the job runner, webhooks, conflicts, provider failures and isolation.
// one tenant for the class; the scenarios run in order on the same definition and use their own records.
@TestPropertySource(
    properties = [
        "wasichai.integrations.encryption.keys.e2e1=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        "wasichai.integrations.encryption.active-key=e2e1",
        "wasichai.integrations.public-base-url=http://localhost:8080",
        // the stubs listen on 127.0.0.1
        "wasichai.integrations.http.allow-private-addresses=true",
        // the tests drive the job runner and the scheduler themselves
        "wasichai.integrations.jobs.worker-enabled=false",
        "wasichai.integrations.jobs.backoff=1ms",
        "wasichai.integrations.jobs.max-backoff=1ms",
        "wasichai.integrations.sync.scheduler-enabled=false"
    ]
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NotionSalesforceSyncTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var jobs: JobRepository

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val notion = IntegrationsTestApplication.notion
    private val sf = IntegrationsTestApplication.salesforce
    private val tenant by lazy { TestTenant.provision(client) }
    private val other by lazy { TestTenant.provision(client) }
    private val browser: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()

    // built once, on first use, whatever test runs first
    private val notionConnection by lazy { connect("notion", notion.clientId, notion.clientSecret) }
    private val sfConnection by lazy { connect("salesforce", sf.clientId, sf.clientSecret) }
    private val definition by lazy { define() }

    // ---- helpers

    // registers the provider app, then the oauth dance: start, the browser at the provider, the callback
    private fun connect(
        connectorId: String,
        clientId: String,
        clientSecret: String
    ): String {
        call(
            client,
            HttpMethod.PUT,
            "/api/integrations/provider-configs/$connectorId",
            tenant.admin,
            mapOf("clientId" to clientId, "clientSecret" to clientSecret),
            200
        )
        val start =
            call(client, HttpMethod.POST, "/api/integrations/connections/oauth/start", tenant.admin, mapOf("connectorId" to connectorId), 200)
        val authorize = start.get("authorizationUrl").asString()
        val params = NotionStub.params(authorize)
        assertEquals("http://localhost:8080/api/integrations/oauth/callback", params["redirect_uri"])
        // the person allows access at the provider, which sends the browser back with a code
        val consent = browser.send(HttpRequest.newBuilder(URI.create(authorize)).GET().build(), HttpResponse.BodyHandlers.discarding())
        assertEquals(302, consent.statusCode(), "authorize at $connectorId")
        val back = URI.create(consent.headers().firstValue("Location").get())
        assertEquals("/api/integrations/oauth/callback", back.path)
        val landed =
            client
                .get()
                .uri(back.path + "?" + back.rawQuery)
                .exchange()
                .expectStatus()
                .isFound
                .returnResult(Void::class.java)
                .responseHeaders.location!!
        val result = NotionStub.params(landed.toString())
        assertEquals("connected", result["status"], "callback of $connectorId answered $landed")
        return result.getValue("connectionId")
    }

    private fun define(): String {
        // what each side holds before the first run
        sf.addAccount("Acme", "Banking", 120)
        sf.addAccount("Globex", "Energy", 40)
        // one record per query page: two accounts are enough to walk nextRecordsUrl
        sf.queryPageSize = 1
        notion.addPage("Initech", "Technology", 15)
        val body =
            mapOf(
                "name" to "Accounts",
                "a" to mapOf("connectionId" to sfConnection, "resource" to "Account"),
                "b" to mapOf("connectionId" to notionConnection, "resource" to notion.dataSourceId),
                "direction" to "BIDIRECTIONAL",
                "deletePolicy" to "PROPAGATE",
                "mappings" to
                    listOf(
                        mapOf("aField" to "Name", "bField" to "Name", "owner" to "BOTH", "conflictStrategy" to "MANUAL"),
                        mapOf("aField" to "Industry", "bField" to "Industry", "transform" to mapOf("type" to "copy"), "conflictStrategy" to "B_WINS"),
                        // headcount comes from the crm: notion shows it, never changes it
                        mapOf("aField" to "NumberOfEmployees", "bField" to "Employees", "owner" to "A")
                    )
            )
        val id = call(client, HttpMethod.POST, "/api/integrations/sync-definitions", tenant.admin, body, 201).get("id").asString()
        // checked against what both providers describe today
        val validation = call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/validate", tenant.admin, null, 200)
        assertTrue(validation.get("valid").asBoolean(), validation.toString())
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/activate", tenant.admin, null, 200)
        return id
    }

    private fun runs(): List<JsonNode> {
        val list = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/runs?limit=200", tenant.admin, null, 200)
        return (0 until list.size()).map { list.get(it) }
    }

    private fun drainOnce() = runBlocking { runner.drainOnce() }

    // drains until the run is SUCCEEDED or PARTIAL; a FAILED attempt is waited out while its job retries
    private fun await(
        runId: String,
        timeout: Duration = Duration.ofSeconds(20)
    ): JsonNode {
        val until = Instant.now().plus(timeout)
        var last: JsonNode? = null
        while (Instant.now().isBefore(until)) {
            drainOnce()
            last = runs().firstOrNull { it.get("id").asString() == runId }
            if (last != null && last.get("status").asString() in setOf("SUCCEEDED", "PARTIAL")) return last
            Thread.sleep(50)
        }
        throw AssertionError("run $runId did not succeed: $last")
    }

    private fun start(kind: String = "INCREMENTAL"): String =
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", tenant.admin, mapOf("kind" to kind), 202)
            .get("runId")
            .asString()

    private fun sync(kind: String = "INCREMENTAL"): JsonNode = await(start(kind))

    private fun conflicts(token: String = tenant.admin): List<JsonNode> {
        val list = call(client, HttpMethod.GET, "/api/integrations/conflicts?definitionId=$definition&status=OPEN", token, null, 200)
        return (0 until list.size()).map { list.get(it) }
    }

    private fun events(token: String = tenant.admin): List<JsonNode> {
        val page = call(client, HttpMethod.GET, "/api/integrations/events?connectionId=$notionConnection&limit=200", token, null, 200)
        val content = page.get("content")
        return (0 until content.size()).map { content.get(it) }
    }

    private fun hmac(
        secret: String,
        body: ByteArray
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    private fun deliver(
        endpointPath: String,
        body: String,
        signature: String?
    ): Int =
        client
            .post()
            .uri(endpointPath)
            .header("Content-Type", "application/json")
            .headers { h -> signature?.let { h.set("X-Notion-Signature", it) } }
            .bodyValue(body.toByteArray())
            .exchange()
            .returnResult(Void::class.java)
            .status
            .value()

    // ---- scenarios

    @Test
    @Order(1)
    fun `notion and salesforce connect through the real oauth endpoints`() {
        val n = call(client, HttpMethod.GET, "/api/integrations/connections/$notionConnection", tenant.admin, null, 200)
        assertEquals("ACTIVE", n.get("status").asString(), n.toString())
        assertEquals(notion.workspaceId, n.get("providerAccountId").asString())
        val s = call(client, HttpMethod.GET, "/api/integrations/connections/$sfConnection", tenant.admin, null, 200)
        assertEquals("ACTIVE", s.get("status").asString(), s.toString())
        assertEquals(sf.orgId, s.get("providerAccountId").asString())
        assertEquals(sf.baseUrl, s.get("metadata").get("instance_url").asString())
        // salesforce got a pkce verifier, notion the client secret over basic auth; nothing secret comes back
        val sfToken = sf.requests("POST", Regex("/services/oauth2/token")).first()
        assertTrue("code_verifier=" in sfToken.bodyText())
        val notionToken = notion.requests("POST", Regex("/v1/oauth/token")).first()
        assertTrue(notionToken.header("Authorization")!!.startsWith("Basic "))
        assertTrue("access_token" !in n.toString() && "access_token" !in s.toString())
        call(client, HttpMethod.POST, "/api/integrations/connections/$sfConnection/test", tenant.admin, null, 200)
    }

    @Test
    @Order(2)
    fun `resources and schemas are discovered through the api`() {
        val sfResources = call(client, HttpMethod.GET, "/api/integrations/connections/$sfConnection/resources", tenant.admin, null, 200)
        assertEquals(listOf("Account"), (0 until sfResources.size()).map { sfResources.get(it).get("name").asString() })
        val account = call(client, HttpMethod.GET, "/api/integrations/connections/$sfConnection/resources/Account/schema", tenant.admin, null, 200)
        val sfFields = (0 until account.get("fields").size()).associate { account.get("fields").get(it).let { f -> f.get("name").asString() to f } }
        assertEquals("CHOICE", sfFields.getValue("Industry").get("type").asString())
        assertEquals("NUMBER", sfFields.getValue("NumberOfEmployees").get("type").asString())
        assertTrue(sfFields.getValue("External_Id__c").get("externalId").asBoolean())
        assertTrue(sfFields.getValue("SystemModstamp").get("readOnly").asBoolean())
        val notionResources = call(client, HttpMethod.GET, "/api/integrations/connections/$notionConnection/resources", tenant.admin, null, 200)
        assertEquals(notion.dataSourceId, notionResources.get(0).get("name").asString())
        assertEquals("Accounts", notionResources.get(0).get("label").asString())
        val source =
            call(client, HttpMethod.GET, "/api/integrations/connections/$notionConnection/resources/${notion.dataSourceId}/schema", tenant.admin, null, 200)
        val notionFields = (0 until source.get("fields").size()).associate { source.get("fields").get(it).let { f -> f.get("name").asString() to f } }
        assertEquals("TEXT", notionFields.getValue("Name").get("type").asString())
        assertTrue(notionFields.getValue("Name").get("required").asBoolean())
        assertEquals(
            NotionStub.INDUSTRIES,
            (0 until 4).map {
                notionFields
                    .getValue("Industry")
                    .get("options")
                    .get(it)
                    .asString()
            }
        )
        assertTrue(notionFields.getValue("Last edited").get("readOnly").asBoolean())
    }

    @Test
    @Order(3)
    fun `an initial run creates notion pages for accounts and accounts for pages, and the next run writes nothing`() {
        val run = sync("INITIAL")
        assertEquals("SUCCEEDED", run.get("status").asString(), run.toString())
        assertEquals(3, run.get("created").asInt(), run.toString())
        val acme = notion.byName("Acme")!!
        assertEquals("Banking", notion.industry(acme))
        assertEquals(0, BigDecimal(120).compareTo(notion.employees(acme)))
        assertEquals("Energy", notion.industry(notion.byName("Globex")!!))
        val initech = sf.byName("Initech")!!
        assertEquals("Technology", sf.industry(initech))
        // owner A: notion's headcount does not travel to the crm
        assertNull(sf.employees(initech))
        val notionWrites = notion.apiWrites.get()
        val sfWrites = sf.apiWrites.get()
        val quiet = sync()
        assertEquals(0, quiet.get("created").asInt() + quiet.get("updated").asInt(), quiet.toString())
        assertEquals(notionWrites, notion.apiWrites.get(), "no echo into notion")
        assertEquals(sfWrites, sf.apiWrites.get(), "no echo into salesforce")
        // the query paged through nextRecordsUrl (one record per page)
        assertTrue(sf.requests("GET", Regex("/services/data/v66.0/query/01g.+")).isNotEmpty())
    }

    @Test
    @Order(4)
    fun `an edit in notion reaches salesforce once, and is not echoed back`() {
        val acme = notion.byName("Acme")!!
        notion.edit(acme.id, industry = "Retail")
        val notionWrites = notion.apiWrites.get()
        val run = sync()
        assertEquals("SUCCEEDED", run.get("status").asString(), run.toString())
        assertEquals("Retail", sf.industry(sf.byName("Acme")!!))
        assertEquals(notionWrites, notion.apiWrites.get(), "nothing written back to notion")
        val sfWrites = sf.apiWrites.get()
        val next = sync()
        assertEquals(0, next.get("updated").asInt(), next.toString())
        assertEquals(notionWrites, notion.apiWrites.get())
        assertEquals(sfWrites, sf.apiWrites.get())
    }

    @Test
    @Order(5)
    fun `an edit in salesforce reaches notion`() {
        val globex = sf.byName("Globex")!!
        sf.edit(globex.id, employees = 55, industry = "Technology")
        sync()
        val page = notion.byName("Globex")!!
        assertEquals(0, BigDecimal(55).compareTo(notion.employees(page)))
        assertEquals("Technology", notion.industry(page))
    }

    @Test
    @Order(6)
    fun `a signed notion webhook starts a run, and a duplicate delivery is one event and no second write`() {
        val endpoint = call(client, HttpMethod.POST, "/api/integrations/connections/$notionConnection/webhook-endpoints", tenant.admin, null, 201)
        val path = URI.create(endpoint.get("url").asString()).path
        // notion's verification request first: the endpoint keeps the token as its secret
        val secret = "secret_" + TestTenant.unique()
        assertEquals(200, deliver(path, """{"verification_token":"$secret"}""", null))
        val token =
            call(client, HttpMethod.GET, "/api/integrations/webhook-endpoints/${endpoint.get("id").asString()}/verification-token", tenant.admin, null, 200)
        assertEquals(secret, token.get("token").asString())
        assertEquals(401, deliver(path, """{"verification_token":"swap"}""", null), "the secret cannot be swapped")

        val initech = notion.byName("Initech")!!
        notion.edit(initech.id, industry = "Banking")
        val eventId = "evt-" + TestTenant.unique()
        val body =
            """{"id":"$eventId","timestamp":"${Instant.now()}","workspace_id":"${notion.workspaceId}","type":"page.properties_updated",""" +
                """"entity":{"id":"${initech.id}","type":"page"},"data":{"parent":{"id":"${NotionStub.DATABASE_ID}","type":"database",""" +
                """"data_source_id":"${notion.dataSourceId}"},"updated_properties":["ind"]}}"""
        assertEquals(401, deliver(path, body, "sha256=" + "0".repeat(64)), "a bad signature is refused")
        assertEquals(200, deliver(path, body, "sha256=" + hmac(secret, body.toByteArray())))
        val before = runs().size
        val until = Instant.now().plusSeconds(20)
        while (Instant.now().isBefore(until) && runs().none { it.get("trigger").asString() == "EVENT" && it.get("status").asString() == "SUCCEEDED" }) {
            drainOnce()
            Thread.sleep(50)
        }
        val eventRun = runs().first { it.get("trigger").asString() == "EVENT" }
        assertEquals("SUCCEEDED", eventRun.get("status").asString(), eventRun.toString())
        assertTrue(runs().size > before)
        assertEquals("Banking", sf.industry(sf.byName("Initech")!!))

        val sfWrites = sf.apiWrites.get()
        val runCount = runs().size
        assertEquals(200, deliver(path, body, "sha256=" + hmac(secret, body.toByteArray())), "a duplicate is acknowledged")
        repeat(5) { drainOnce() }
        assertEquals(1, events().count { it.path("providerEventId").asString() == eventId })
        assertEquals(runCount, runs().size, "a duplicate starts no run")
        assertEquals(sfWrites, sf.apiWrites.get())
    }

    @Test
    @Order(7)
    fun `the same field edited on both sides is a conflict, nothing is overwritten, and a resolution settles it`() {
        val page = notion.byName("Acme")!!
        val account = sf.byName("Acme")!!
        notion.edit(page.id, name = "Acme Notion")
        sf.edit(account.id, name = "Acme Salesforce")
        val run = sync()
        assertEquals(1, run.get("conflicts").asInt(), run.toString())
        assertEquals("Acme Notion", notion.name(notion.page(page.id)!!))
        assertEquals("Acme Salesforce", sf.name(sf.account(account.id)!!))
        val conflict = conflicts().single()
        assertEquals("FIELDS", conflict.get("kind").asString())
        assertEquals(
            "Name->Name",
            conflict
                .get("fields")
                .get(0)
                .get("key")
                .asString()
        )
        // held: another run leaves the pair alone
        sync()
        assertEquals("Acme Salesforce", sf.name(sf.account(account.id)!!))
        val resolved =
            call(client, HttpMethod.POST, "/api/integrations/conflicts/${conflict.get("id").asString()}/resolve", tenant.admin, mapOf("choice" to "B"), 200)
        assertEquals("RESOLVED", resolved.get("status").asString())
        assertEquals("Acme Notion", sf.name(sf.account(account.id)!!))
        assertEquals("Acme Notion", notion.name(notion.page(page.id)!!))
        val after = sync()
        assertEquals(0, after.get("conflicts").asInt(), after.toString())
        assertTrue(conflicts().isEmpty())
    }

    @Test
    @Order(8)
    fun `an expired salesforce session is refreshed without anyone noticing`() {
        val globex = sf.byName("Globex")!!
        sf.edit(globex.id, name = "Globex Corp")
        sf.expireSessions()
        val refreshes = sf.refreshes.get()
        val run = sync()
        assertEquals("SUCCEEDED", run.get("status").asString(), run.toString())
        assertTrue(sf.refreshes.get() > refreshes, "the token was refreshed")
        assertNotNull(notion.byName("Globex Corp"))
        val connection = call(client, HttpMethod.GET, "/api/integrations/connections/$sfConnection", tenant.admin, null, 200)
        assertEquals("ACTIVE", connection.get("status").asString())
    }

    @Test
    @Order(9)
    fun `a salesforce 429 fails the attempt, and the job tries again after Retry-After`() {
        val account = sf.byName("Initech")!!
        sf.edit(account.id, industry = "Energy")
        sf.throttle(1, retryAfter = 1)
        val runId = start()
        drainOnce()
        val failed = runs().first { it.get("id").asString() == runId }
        assertEquals("FAILED", failed.get("status").asString(), failed.toString())
        assertEquals("transient", failed.get("errorKind").asString())
        val status = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/status", tenant.admin, null, 200)
        assertEquals("transient", status.get("errorKind").asString())
        val run = await(runId)
        assertEquals("SUCCEEDED", run.get("status").asString())
        assertEquals("Energy", notion.industry(notion.byName("Initech")!!))
    }

    @Test
    @Order(10)
    fun `a notion 429 fails the attempt, and the job tries again after Retry-After`() {
        val page = notion.byName("Initech")!!
        notion.edit(page.id, industry = "Retail")
        notion.throttle(1, retryAfter = 1)
        val runId = start()
        val run = await(runId)
        assertEquals("SUCCEEDED", run.get("status").asString(), run.toString())
        assertEquals("Retail", sf.industry(sf.byName("Initech")!!))
        assertTrue(notion.requests("POST", Regex("/v1/data_sources/.+/query")).isNotEmpty())
    }

    @Test
    @Order(11)
    fun `a run whose worker died is taken over when its lease runs out, and creates nothing twice`() {
        sf.addAccount("Hooli", "Technology", 9)
        val runId = start()
        // a worker claims the job and dies before finishing it
        val claimed = runBlocking { jobs.claim(10, Duration.ofMinutes(5), Instant.now()) }
        assertTrue(claimed.any { it.payload.path("runId").asString() == runId }, claimed.toString())
        assertEquals(0, drainOnce(), "a job under a live lease is nobody else's")
        runBlocking {
            db
                .sql("UPDATE ${schemas.metadata}.integration_jobs SET lease_until = now() - interval '1 minute' WHERE status = 'RUNNING'")
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val run = await(runId)
        assertEquals("SUCCEEDED", run.get("status").asString(), run.toString())
        assertEquals(1, notion.live().count { notion.name(it) == "Hooli" })
        sync()
        assertEquals(1, notion.live().count { notion.name(it) == "Hooli" }, "no duplicate on the next run")
    }

    @Test
    @Order(12)
    fun `a reconcile finds an account deleted without a trace and applies the delete policy`() {
        val account = sf.byName("Hooli")!!
        val page = notion.byName("Hooli")!!
        sf.vanish(account.id)
        sync()
        assertTrue(notion.page(page.id)!!.inTrash.not(), "the feeds say nothing about it")
        val run = sync("RECONCILE")
        assertEquals(1, run.get("missing").asInt(), run.toString())
        // PROPAGATE: the page goes to notion's trash
        assertTrue(notion.page(page.id)!!.inTrash)
        val status = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/status", tenant.admin, null, 200)
        assertEquals("SUCCEEDED", status.get("lastRun").get("status").asString())
        assertEquals(0L, status.get("pendingConflicts").asLong())
    }

    @Test
    @Order(13)
    fun `another tenant sees none of it`() {
        // the fixtures exist before the other tenant looks
        assertTrue(runs().isNotEmpty())
        val definitions = call(client, HttpMethod.GET, "/api/integrations/sync-definitions", other.admin, null, 200)
        assertEquals(0, definitions.size())
        call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition", other.admin, null, 404)
        call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/runs", other.admin, null, 404)
        call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/status", other.admin, null, 404)
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", other.admin, mapOf("kind" to "INCREMENTAL"), 404)
        val conflicts = call(client, HttpMethod.GET, "/api/integrations/conflicts?definitionId=$definition", other.admin, null, 200)
        assertEquals(0, conflicts.size())
        assertTrue(events(other.admin).isEmpty())
        val connections = call(client, HttpMethod.GET, "/api/integrations/connections", other.admin, null, 200)
        assertEquals(0, connections.size())
        call(client, HttpMethod.GET, "/api/integrations/connections/$sfConnection", other.admin, null, 404)
    }
}
