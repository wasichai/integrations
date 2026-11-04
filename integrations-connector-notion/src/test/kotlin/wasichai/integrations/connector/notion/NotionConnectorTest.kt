package wasichai.integrations.connector.notion

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import wasichai.integrations.connector.notion.NotionFixtures.PAGE
import wasichai.integrations.connector.notion.NotionFixtures.SOURCE
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.TokenGrant
import wasichai.integrations.sdk.testing.ConnectorContractTest
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import wasichai.integrations.sdk.testing.TestConnectorContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotionConnectorTest : ConnectorContractTest() {
    private val stub = StubProviderServer()
    private val notion by lazy { NotionConnector(NotionConfig(apiBaseUrl = stub.baseUrl)) }
    private val context = TestConnectorContext()
    private val json = JsonMapper.builder().build()

    override fun connector(): Connector = notion

    override fun discoveryContext() = context

    @BeforeEach
    fun stubs() {
        stub.reset()
        stub.onJson(HttpMethod.GET, "/v1/data_sources/$SOURCE", NotionFixtures.dataSource)
        stub.onJson(
            HttpMethod.POST,
            "/v1/search",
            """{"results":[{"object":"data_source","id":"$SOURCE","title":[{"plain_text":"Accounts"}]}],"has_more":false,"next_cursor":null}"""
        )
    }

    @AfterAll
    fun stop() = stub.close()

    @Test
    fun `every call carries the pinned notion version`() {
        runBlocking { notion.listResources(context) }
        assertEquals("2025-09-03", stub.requests.single().header("Notion-Version"))
        val body = json.readTree(stub.requests.single().body)
        assertEquals("data_source", body.path("filter").path("value").asString())
    }

    @Test
    fun `describe maps notion property types`() {
        val schema = runBlocking { notion.describe(context, SOURCE) }
        assertEquals("Accounts", schema.label)
        assertEquals(FieldType.TEXT, schema.field("Name")!!.type)
        assertTrue(schema.field("Name")!!.required)
        assertEquals(FieldType.CHOICE, schema.field("Industry")!!.type)
        assertEquals(listOf("Energy", "Retail"), schema.field("Industry")!!.options)
        assertEquals(FieldType.NUMBER, schema.field("Employees")!!.type)
        assertEquals(FieldType.MULTI_CHOICE, schema.field("Tags")!!.type)
        assertTrue(schema.field("Edited")!!.readOnly)
        assertFalse(schema.field("Notes")!!.readOnly)
    }

    @Test
    fun `reads a page as typed fields`() {
        stub.onJson(HttpMethod.GET, "/v1/pages/$PAGE", NotionFixtures.page())
        val record = runBlocking { notion.read(context, SOURCE, PAGE) }!!
        assertEquals("2026-11-04T10:15:00.000Z", record.version)
        assertEquals(Instant.parse("2026-11-04T10:15:00Z"), record.modifiedAt)
        assertEquals(FieldValue.Text("Acme"), record.fields["Name"])
        assertEquals(FieldValue.Choice("Energy"), record.fields["Industry"])
        assertEquals(FieldValue.Number(BigDecimal(120)), record.fields["Employees"])
        assertEquals(FieldValue.Bool(true), record.fields["Active"])
        assertEquals(FieldValue.Date(LocalDate.of(2020, 1, 15)), record.fields["Since"])
        assertEquals(FieldValue.MultiChoice(listOf("a", "b")), record.fields["Tags"])
        assertEquals(FieldValue.Text(""), record.fields["Notes"])
    }

    @Test
    fun `missing and trashed pages read as absent`() {
        stub.onJson(HttpMethod.GET, "/v1/pages/$PAGE", """{"object":"error","status":404,"code":"object_not_found","message":"x"}""", 404)
        assertNull(runBlocking { notion.read(context, SOURCE, PAGE) })
        stub.onJson(HttpMethod.GET, "/v1/pages/$PAGE", NotionFixtures.page(trashed = true))
        assertNull(runBlocking { notion.read(context, SOURCE, PAGE) })
    }

    @Test
    fun `ids that are not notion ids never reach a url`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { notion.read(context, SOURCE, "../users") } }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `list pages through the cursor`() {
        stub.on(HttpMethod.POST, "/v1/data_sources/$SOURCE/query") { call, _ ->
            val cursor = json.readTree(call.body).path("start_cursor")
            if (cursor.isMissingNode) {
                StubResponse.json(NotionFixtures.query(listOf(NotionFixtures.page()), next = "c2"))
            } else {
                StubResponse.json(NotionFixtures.query(listOf(NotionFixtures.page(id = "3b8c4d5e-6f70-4b12-8c9d-1e2f3a4b5c6d"))))
            }
        }
        val first = runBlocking { notion.list(context, ReadRequest(SOURCE, pageSize = 50)) }
        assertEquals("c2", first.nextCursor)
        val second = runBlocking { notion.list(context, ReadRequest(SOURCE, cursor = first.nextCursor)) }
        assertNull(second.nextCursor)
        assertEquals("3b8c4d5e-6f70-4b12-8c9d-1e2f3a4b5c6d", second.records.single().id)
    }

    @Test
    fun `change feed filters on last edit, pages, then restarts at the newest minute seen`() {
        val bodies = mutableListOf<String>()
        stub.on(HttpMethod.POST, "/v1/data_sources/$SOURCE/query") { call, _ ->
            bodies += call.bodyText()
            val cursor = json.readTree(call.body).path("start_cursor")
            if (cursor.isMissingNode) {
                StubResponse.json(NotionFixtures.query(listOf(NotionFixtures.page(edited = "2026-11-04T10:00:00.000Z")), next = "c2"))
            } else {
                StubResponse.json(NotionFixtures.query(listOf(NotionFixtures.page(edited = "2026-11-04T10:30:00.000Z"))))
            }
        }
        val first = runBlocking { notion.changes(context, SOURCE, null) }
        assertTrue(first.hasMore)
        assertFalse(json.readTree(bodies[0]).has("filter"), "an initial pass reads everything")
        val second = runBlocking { notion.changes(context, SOURCE, first.checkpoint) }
        assertFalse(second.hasMore)
        assertInstanceOf(Change.Upserted::class.java, second.changes.single())
        val third = runBlocking { notion.changes(context, SOURCE, second.checkpoint) }
        val filter = json.readTree(bodies[2]).path("filter")
        assertEquals("2026-11-04T10:30:00Z", filter.path("last_edited_time").path("on_or_after").asString())
        assertEquals(
            "ascending",
            json
                .readTree(bodies[2])
                .path("sorts")[0]
                .path("direction")
                .asString()
        )
        assertEquals(1, third.changes.size)
    }

    @Test
    fun `creates a page in the data source with notion property json`() {
        stub.onJson(HttpMethod.POST, "/v1/pages", NotionFixtures.page())
        val result =
            runBlocking {
                notion.create(
                    context,
                    SOURCE,
                    mapOf(
                        "Name" to FieldValue.Text("Acme"),
                        "Industry" to FieldValue.Choice("Energy"),
                        "Employees" to FieldValue.Number(BigDecimal(120)),
                        "Since" to FieldValue.Date(LocalDate.of(2020, 1, 15)),
                        "Notes" to FieldValue.Null
                    )
                )
            }
        assertTrue(result.created)
        assertEquals(PAGE, result.id)
        val body = json.readTree(stub.requests.last().body)
        assertEquals(SOURCE, body.path("parent").path("data_source_id").asString())
        assertEquals(
            "Acme",
            body
                .path("properties")
                .path("Name")
                .path("title")[0]
                .path("text")
                .path("content")
                .asString()
        )
        assertEquals(
            "Energy",
            body
                .path("properties")
                .path("Industry")
                .path("select")
                .path("name")
                .asString()
        )
        assertEquals(
            120,
            body
                .path("properties")
                .path("Employees")
                .path("number")
                .asInt()
        )
        assertEquals(
            "2020-01-15",
            body
                .path("properties")
                .path("Since")
                .path("date")
                .path("start")
                .asString()
        )
        assertEquals(
            0,
            body
                .path("properties")
                .path("Notes")
                .path("rich_text")
                .size()
        )
    }

    @Test
    fun `writing a read-only or unknown property is refused before any write`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { notion.create(context, SOURCE, mapOf("Edited" to FieldValue.Text("x"))) } }
        assertThrows<ConnectorException.Permanent> { runBlocking { notion.create(context, SOURCE, mapOf("Nope" to FieldValue.Text("x"))) } }
        assertTrue(stub.requests.none { it.path == "/v1/pages" })
    }

    @Test
    fun `update with a stale expected version is a conflict and writes nothing`() {
        stub.onJson(HttpMethod.GET, "/v1/pages/$PAGE", NotionFixtures.page(edited = "2026-11-04T11:00:00.000Z"))
        val error =
            assertThrows<ConnectorException.Conflict> {
                runBlocking { notion.update(context, SOURCE, PAGE, mapOf("Name" to FieldValue.Text("B")), "2026-11-04T10:15:00.000Z") }
            }
        assertEquals("2026-11-04T11:00:00.000Z", error.currentVersion)
        assertTrue(stub.requests.none { it.method == HttpMethod.PATCH })
    }

    @Test
    fun `update with the current version patches the page`() {
        stub.onJson(HttpMethod.GET, "/v1/pages/$PAGE", NotionFixtures.page())
        stub.onJson(HttpMethod.PATCH, "/v1/pages/$PAGE", NotionFixtures.page(name = "B", edited = "2026-11-04T10:20:00.000Z"))
        val result = runBlocking { notion.update(context, SOURCE, PAGE, mapOf("Name" to FieldValue.Text("B")), "2026-11-04T10:15:00.000Z") }
        assertEquals("2026-11-04T10:20:00.000Z", result.version)
        assertFalse(result.created)
    }

    @Test
    fun `delete moves the page to the trash`() {
        stub.onJson(HttpMethod.PATCH, "/v1/pages/$PAGE", NotionFixtures.page(trashed = true))
        runBlocking { notion.delete(context, SOURCE, PAGE) }
        assertTrue(json.readTree(stub.requests.last().body).path("in_trash").asBoolean())
    }

    @Test
    fun `rate limits and auth failures map to the taxonomy`() {
        stub.on(HttpMethod.GET, "/v1/pages/$PAGE") { _, _ ->
            StubResponse.json("""{"object":"error","status":429,"code":"rate_limited","message":"slow"}""", 429, mapOf("Retry-After" to "2"))
        }
        val limited = assertThrows<ConnectorException.RateLimited> { runBlocking { notion.read(context, SOURCE, PAGE) } }
        assertEquals(2, limited.retryAfter!!.seconds)
        stub.onJson(HttpMethod.GET, "/v1/pages/$PAGE", """{"object":"error","status":401,"code":"unauthorized","message":"x"}""", 401)
        assertThrows<ConnectorException.AuthExpired> { runBlocking { notion.read(context, SOURCE, PAGE) } }
        stub.onJson(HttpMethod.GET, "/v1/pages/$PAGE", """{"object":"error","status":409,"code":"conflict_error","message":"x"}""", 409)
        assertThrows<ConnectorException.Transient> { runBlocking { notion.read(context, SOURCE, PAGE) } }
        stub.onJson(HttpMethod.GET, "/v1/pages/$PAGE", """{"object":"error","status":400,"code":"validation_error","message":"x"}""", 400)
        assertThrows<ConnectorException.Permanent> { runBlocking { notion.read(context, SOURCE, PAGE) } }
    }

    @Test
    fun `account is the workspace, from the token response or users me`() {
        stub.onJson(HttpMethod.GET, "/v1/users/me", """{"object":"user","id":"bot-1","type":"bot","bot":{"workspace_name":"Acme HQ"}}""")
        val grant = TokenGrant("t", null, null, emptyList(), mapOf("workspace_id" to "ws-9", "workspace_name" to "Acme HQ", "access_token" to "t"))
        val metadata = notion.connectionMetadata(grant)
        assertEquals(mapOf("workspace_id" to "ws-9", "workspace_name" to "Acme HQ"), metadata, "never the token")
        val account = runBlocking { notion.accountInfo(TestConnectorContext(metadata = metadata)) }
        assertEquals("ws-9", account.providerAccountId)
        assertEquals("Acme HQ", account.displayName)
    }
}
