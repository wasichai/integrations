package wasichai.integrations.connector.openapi

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import wasichai.integrations.connector.openapi.OpenApiFixtures.PetstoreStub
import wasichai.integrations.connector.openapi.OpenApiFixtures.owner
import wasichai.integrations.connector.openapi.OpenApiFixtures.petstore
import wasichai.integrations.connector.openapi.OpenApiFixtures.resource
import wasichai.integrations.connector.openapi.OpenApiFixtures.toy
import wasichai.integrations.connector.openapi.OpenApiFixtures.visit
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.ChangeFeed
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import wasichai.integrations.sdk.testing.TestConnectorContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

// the petstore mapping against a stub that answers as petstore.yaml documents
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiConnectorTest {
    private val stub = StubProviderServer()
    private val config by lazy { petstore("${stub.baseUrl}/v1") }
    private val connector by lazy { OpenApiConnector.of(config) }
    private val reader get() = connector as ResourceReader
    private val feed get() = connector as ChangeFeed
    private val writer get() = connector as ResourceWriter
    private val context = TestConnectorContext()
    private val json = JsonMapper.builder().build()
    private lateinit var store: PetstoreStub

    @BeforeEach
    fun reset() {
        stub.reset()
        store = PetstoreStub(stub).also { it.install() }
    }

    @AfterAll
    fun stop() = stub.close()

    private fun all(
        resource: String,
        pageSize: Int
    ): List<List<String>> {
        val pages = mutableListOf<List<String>>()
        var cursor: String? = null
        do {
            val page = runBlocking { reader.list(context, ReadRequest(resource, cursor, pageSize)) }
            pages += page.records.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)
        return pages
    }

    @Test
    fun `reads a record as typed fields, versioned by its updated-at field`() {
        store.pet("Rex")
        val record = runBlocking { reader.read(context, "pet", "1") }!!
        assertEquals("1", record.id)
        assertEquals("2026-11-01T00:01:00Z", record.version)
        assertEquals(Instant.parse("2026-11-01T00:01:00Z"), record.modifiedAt)
        assertEquals(FieldValue.Text("Rex"), record.fields["name"])
        assertEquals(FieldValue.Choice("available"), record.fields["status"])
        assertEquals(FieldValue.Date(LocalDate.parse("2020-05-17")), record.fields["birthday"])
        assertEquals(FieldValue.Bool(true), record.fields["vaccinated"])
        assertEquals(FieldValue.Number(BigDecimal("4.5")), record.fields["weight"])
        assertEquals(FieldValue.MultiChoice(listOf("friendly")), record.fields["tags"])
        assertEquals(FieldValue.Json(json.readTree("""{"id":"o1"}""")), record.fields["owner"])
        assertEquals(FieldValue.DateTime(Instant.parse("2026-11-01T00:01:00Z")), record.fields["updated_at"])
        val call = stub.requests.single()
        assertEquals("/v1/pets/1", call.path)
        assertEquals("application/json", call.header("Accept"))
    }

    @Test
    fun `a missing record reads as null, and a value off its schema is kept as json`() {
        assertNull(runBlocking { reader.read(context, "pet", "42") })
        stub.onJson(HttpMethod.GET, "/v1/pets/7", """{"id":7,"name":"Odd","weight":"heavy","updated_at":"2026-11-01T00:00:00Z"}""")
        val record = runBlocking { reader.read(context, "pet", "7") }!!
        assertEquals(FieldValue.Json(json.readTree("\"heavy\"")), record.fields["weight"])
        assertEquals(FieldValue.Null, record.fields["status"])
    }

    @Test
    fun `an id is one encoded path segment`() {
        stub.onJson(HttpMethod.GET, "/v1/pets/a%20b%2Fc", """{"id":"a b/c","name":"Slash"}""")
        assertEquals("a b/c", runBlocking { reader.read(context, "pet", "a b/c") }!!.id)
        assertThrows<ConnectorException.Permanent> { runBlocking { reader.read(context, "pet", "..") } }
    }

    @Test
    fun `page pagination sends page and limit and stops at a short page`() {
        repeat(5) { store.pet("Pet $it") }
        assertEquals(listOf(listOf("1", "2"), listOf("3", "4"), listOf("5")), all("pet", 2))
        assertEquals(listOf("1", "2", "3"), stub.requests.map { it.query["page"] })
        assertTrue(stub.requests.all { it.query["limit"] == "2" })
    }

    @Test
    fun `cursor pagination follows the cursor field until it is null`() {
        stub.on(HttpMethod.GET, "/v1/owners") { call, _ ->
            when (call.query["cursor"]) {
                null -> StubResponse.json("""{"data":[${owner("o1")},${owner("o2")}],"meta":{"next_cursor":"c2"}}""")
                "c2" -> StubResponse.json("""{"data":[${owner("o3")}],"meta":{"next_cursor":null}}""")
                else -> StubResponse.json("{}", 400)
            }
        }
        assertEquals(listOf(listOf("o1", "o2"), listOf("o3")), all("owner", 2))
        assertEquals("2", stub.requests.first().query["limit"])
        assertEquals("c2", stub.requests.last().query["cursor"])
        val page = runBlocking { reader.list(context, ReadRequest("owner", "c2")) }
        assertEquals(FieldValue.Text("o3@petstore.example"), page.records.single().fields["email"])
    }

    @Test
    fun `next url pagination follows a body field, never outside the base url`() {
        stub.on(HttpMethod.GET, "/v1/toys") { call, _ ->
            if (call.query["after"] == "t2") {
                StubResponse.json("""{"items":[${toy("t3")}]}""")
            } else {
                StubResponse.json("""{"items":[${toy("t1")},${toy("t2")}],"next":"${stub.baseUrl}/v1/toys?after=t2"}""")
            }
        }
        assertEquals(listOf(listOf("t1", "t2"), listOf("t3")), all("toy", 2))
        stub.onJson(HttpMethod.GET, "/v1/toys", """{"items":[${toy("t1")}],"next":"https://evil.example/v1/toys?after=t1"}""")
        assertThrows<ConnectorException.Permanent> { runBlocking { reader.list(context, ReadRequest("toy")) } }
        assertThrows<ConnectorException.Permanent> { runBlocking { reader.list(context, ReadRequest("toy", "${stub.baseUrl}/v2/toys")) } }
    }

    @Test
    fun `next url pagination follows the Link header`() {
        stub.on(HttpMethod.GET, "/v1/visits") { call, _ ->
            if (call.query["page"] == "2") {
                StubResponse.json("[${visit("v3")}]")
            } else {
                val link = "<${stub.baseUrl}/v1/visits?per_page=2&page=2>; rel=\"next\", <${stub.baseUrl}/v1/visits?per_page=2&page=2>; rel=\"last\""
                StubResponse.json("[${visit("v1")},${visit("v2")}]", headers = mapOf("Link" to link))
            }
        }
        assertEquals(listOf(listOf("v1", "v2"), listOf("v3")), all("visit", 2))
        assertEquals("2", stub.requests.first().query["per_page"])
    }

    @Test
    fun `without pagination one answer is the whole list`() {
        stub.onJson(HttpMethod.GET, "/v1/species", """[{"code":"cat","label":"Cat"},{"code":"dog","label":"Dog"}]""")
        val page = runBlocking { reader.list(context, ReadRequest("species")) }
        assertEquals(listOf("cat", "dog"), page.records.map { it.id })
        assertNull(page.nextCursor)
        assertTrue(
            stub.requests
                .single()
                .query
                .isEmpty()
        )
        stub.onJson(HttpMethod.GET, "/v1/species", """[{"label":"No code"}]""")
        assertThrows<ConnectorException.Permanent> { runBlocking { reader.list(context, ReadRequest("species")) } }
        stub.onJson(HttpMethod.GET, "/v1/species", """{"species":[]}""")
        assertThrows<ConnectorException.Permanent> { runBlocking { reader.list(context, ReadRequest("species")) } }
    }

    @Test
    fun `the change feed pages a pass on a fixed since, then moves since to the newest updated-at`() {
        repeat(3) { store.pet("Pet $it") }
        val first = runBlocking { feed.changes(context, "pet", null, 2) }
        assertEquals(listOf("1", "2"), first.changes.map { it.id })
        assertTrue(first.hasMore)
        val second = runBlocking { feed.changes(context, "pet", first.checkpoint, 2) }
        assertEquals(listOf("3"), second.changes.map { it.id })
        assertFalse(second.hasMore)
        assertTrue(stub.requests.none { "updated_since" in it.query }, "the first pass reads everything")
        // pet 1 is edited: the next pass starts at the newest updated-at seen (pet 3's, read again) and finds it
        runBlocking { writer.update(context, "pet", "1", mapOf("status" to FieldValue.Choice("sold"))) }
        stub.requests.size.let { before ->
            val third = runBlocking { feed.changes(context, "pet", second.checkpoint, 2) }
            assertEquals(listOf("1", "3"), third.changes.map { it.id })
            val upserted = third.changes.first() as Change.Upserted
            assertEquals(FieldValue.Choice("sold"), upserted.record.fields["status"])
            assertEquals("2026-11-01T00:03:00Z", stub.requests[before].query["updated_since"])
            // a full page may have a next one: the pass ends on the empty page, since then moves to pet 1's edit
            assertTrue(third.hasMore)
            val fourth = runBlocking { feed.changes(context, "pet", third.checkpoint, 2) }
            assertEquals(emptyList<String>(), fourth.changes.map { it.id })
            assertFalse(fourth.hasMore)
            assertEquals(Instant.parse("2026-11-01T00:04:00Z"), OpenApiCheckpoint.parse(fourth.checkpoint).since)
            assertEquals("2026-11-01T00:03:00Z", stub.requests.last().query["updated_since"], "the pass keeps its since")
        }
        assertThrows<ConnectorException.Permanent> { runBlocking { feed.changes(context, "pet", "{not json", 2) } }
        assertThrows<ConnectorException.Permanent> { runBlocking { feed.changes(context, "owner", null, 2) } }
    }

    @Test
    fun `create posts the fields and answers the new id and version`() {
        val result = runBlocking { writer.create(context, "pet", mapOf("name" to FieldValue.Text("Tom"), "tags" to FieldValue.MultiChoice(listOf("shy")))) }
        assertTrue(result.created)
        assertEquals("1", result.id)
        assertEquals("2026-11-01T00:01:00Z", result.version)
        val call = stub.requests.single()
        assertEquals(HttpMethod.POST, call.method)
        assertEquals("application/json", call.header("Content-Type"))
        assertEquals(json.readTree("""{"name":"Tom","tags":["shy"]}"""), json.readTree(call.body))
    }

    @Test
    fun `update sends the operation's media type and checks the expected version first`() {
        store.pet("Rex")
        val result = runBlocking { writer.update(context, "pet", "1", mapOf("weight" to FieldValue.Number(BigDecimal("5.2"))), "2026-11-01T00:01:00Z") }
        assertEquals("2026-11-01T00:02:00Z", result.version)
        assertFalse(result.created)
        val patch = stub.requests.last()
        assertEquals(HttpMethod.PATCH, patch.method)
        assertEquals("application/merge-patch+json", patch.header("Content-Type"))
        assertEquals(json.readTree("""{"weight":5.2}"""), json.readTree(patch.body))
        val conflict =
            assertThrows<ConnectorException.Conflict> {
                runBlocking { writer.update(context, "pet", "1", mapOf("name" to FieldValue.Text("Max")), "2026-11-01T00:01:00Z") }
            }
        assertEquals("2026-11-01T00:02:00Z", conflict.currentVersion)
        assertEquals(HttpMethod.GET, stub.requests.last().method, "nothing written over the newer record")
        assertThrows<ConnectorException.NotFound> {
            runBlocking { writer.update(context, "pet", "9", mapOf("name" to FieldValue.Text("Max")), "2026-11-01T00:01:00Z") }
        }
    }

    @Test
    fun `fields off the schema, read-only fields and wrong values are refused before any call`() {
        val refused =
            listOf(
                mapOf("color" to FieldValue.Text("red")),
                mapOf("id" to FieldValue.Number(BigDecimal.ONE)),
                mapOf("updated_at" to FieldValue.DateTime(Instant.now())),
                mapOf("status" to FieldValue.Choice("lost")),
                mapOf("tags" to FieldValue.MultiChoice(listOf("grumpy"))),
                mapOf("vaccinated" to FieldValue.Text("yes")),
                mapOf("weight" to FieldValue.Text("heavy"))
            )
        refused.forEach { fields ->
            assertThrows<ConnectorException.Permanent>("$fields") { runBlocking { writer.create(context, "pet", fields) } }
        }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `delete removes the record and is done when it is already gone`() {
        store.pet("Rex")
        runBlocking { writer.delete(context, "pet", "1") }
        assertTrue(store.pets.isEmpty())
        runBlocking { writer.delete(context, "pet", "1") }
        assertEquals(listOf(HttpMethod.DELETE, HttpMethod.DELETE), stub.requests.map { it.method })
    }

    @Test
    fun `writes the mapping does not provide are refused without a call`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { writer.create(context, "owner", mapOf("name" to FieldValue.Text("Ann"))) } }
        assertThrows<ConnectorException.Permanent> { runBlocking { writer.update(context, "owner", "o1", mapOf("name" to FieldValue.Text("Ann"))) } }
        assertThrows<ConnectorException.Permanent> { runBlocking { writer.delete(context, "owner", "o1") } }
        assertThrows<ConnectorException.NotFound> { runBlocking { reader.read(context, "vet", "1") } }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `a create answered without the record is an error, never a guessed id`() {
        stub.on(HttpMethod.POST, "/v1/pets") { _, _ -> StubResponse(201) }
        assertThrows<ConnectorException.Permanent> { runBlocking { writer.create(context, "pet", mapOf("name" to FieldValue.Text("Tom"))) } }
    }

    @Test
    fun `errors map by status, the provider's body only in details`() {
        fun failing(
            status: Int,
            headers: Map<String, String> = emptyMap()
        ): ConnectorException {
            stub.on(HttpMethod.GET, "/v1/pets") { _, _ -> StubResponse.json("""{"error":"secret detail"}""", status, headers) }
            return assertThrows<ConnectorException> { runBlocking { reader.list(context, ReadRequest("pet")) } }
        }
        assertTrue(failing(401) is ConnectorException.AuthExpired)
        assertTrue(failing(403) is ConnectorException.PermissionDenied)
        assertTrue(failing(404) is ConnectorException.NotFound)
        assertTrue(failing(409) is ConnectorException.Conflict)
        assertTrue(failing(412) is ConnectorException.Conflict)
        val limited = failing(429, mapOf("Retry-After" to "30")) as ConnectorException.RateLimited
        assertEquals(Duration.ofSeconds(30), limited.retryAfter)
        assertTrue(failing(503) is ConnectorException.Transient)
        val permanent = failing(422) as ConnectorException.Permanent
        assertEquals("provider answered 422", permanent.message)
        assertEquals("""{"error":"secret detail"}""", permanent.details["body"])
    }
}
