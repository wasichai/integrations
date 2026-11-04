package wasichai.integrations.connector.salesforce

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
import wasichai.integrations.connector.salesforce.Fixtures.API
import wasichai.integrations.connector.salesforce.Fixtures.ID1
import wasichai.integrations.connector.salesforce.Fixtures.ID2
import wasichai.integrations.connector.salesforce.Fixtures.account
import wasichai.integrations.connector.salesforce.Fixtures.query
import wasichai.integrations.connector.salesforce.Fixtures.salesforceBasics
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.TokenGrant
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import wasichai.integrations.sdk.testing.TestConnectorContext
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SalesforceConnectorTest {
    private val stub = StubProviderServer()
    private val connector = SalesforceConnector()
    private val json = JsonMapper.builder().build()
    private val now = Instant.parse("2026-11-04T13:00:00Z")

    // a fresh connection id per test: version and describe caches are per connection
    private fun context() =
        TestConnectorContext(
            metadata = mapOf("instance_url" to stub.baseUrl),
            connectionId = UUID.randomUUID().toString(),
            clock = Clock.fixed(now, ZoneOffset.UTC)
        )

    @BeforeEach
    fun reset() {
        stub.reset()
        stub.salesforceBasics()
    }

    @AfterAll
    fun stop() = stub.close()

    @Test
    fun `keeps instance url and identity url from the token response, never tokens`() {
        val grant =
            TokenGrant(
                "secret-access",
                "secret-refresh",
                null,
                emptyList(),
                mapOf("instance_url" to "https://acme.my.salesforce.com", "id" to "https://login.salesforce.com/id/00D/005", "access_token" to "x")
            )
        assertEquals(
            mapOf("instance_url" to "https://acme.my.salesforce.com", "id" to "https://login.salesforce.com/id/00D/005"),
            connector.connectionMetadata(grant)
        )
    }

    @Test
    fun `account is the organization id`() {
        stub.onJson(
            HttpMethod.GET,
            "/services/oauth2/userinfo",
            """{"organization_id":"00D000000000001","user_id":"005000000000001","preferred_username":"ana@acme.com"}"""
        )
        val account = runBlocking { connector.accountInfo(context()) }
        assertEquals("00D000000000001", account.providerAccountId)
        assertEquals("ana@acme.com", account.displayName)
        assertEquals("005000000000001", account.metadata["user_id"])
    }

    @Test
    fun `picks the newest api version once per connection`() {
        val ctx = context()
        runBlocking {
            connector.listResources(ctx)
            connector.listResources(ctx)
        }
        assertEquals(1, stub.requests.count { it.path == "/services/data/" })
        assertTrue(stub.requests.any { it.path == "$API/sobjects/" })
    }

    @Test
    fun `a fixed api version skips discovery`() {
        stub.onJson(HttpMethod.GET, "/services/data/v62.0/sobjects/", Fixtures.describeGlobal)
        val ctx = context().copy(metadata = mapOf("instance_url" to stub.baseUrl, "api_version" to "62.0"))
        runBlocking { connector.listResources(ctx) }
        assertTrue(stub.requests.none { it.path == "/services/data/" })
    }

    @Test
    fun `lists queryable and retrieveable objects`() {
        val names = runBlocking { connector.listResources(context()) }.map { it.name }
        assertEquals(listOf("Account", "Contact"), names)
    }

    @Test
    fun `describe maps types, flags and active picklist values`() {
        val schema = runBlocking { connector.describe(context(), "Account") }
        assertEquals(FieldType.TEXT, schema.field("Name")!!.type)
        assertTrue(schema.field("Name")!!.required)
        assertTrue(schema.field("Id")!!.readOnly)
        assertEquals(FieldType.CHOICE, schema.field("Industry")!!.type)
        assertEquals(listOf("Banking"), schema.field("Industry")!!.options)
        assertEquals(FieldType.NUMBER, schema.field("AnnualRevenue")!!.type)
        assertEquals(FieldType.BOOLEAN, schema.field("Active__c")!!.type)
        assertFalse(schema.field("Active__c")!!.required)
        assertTrue(schema.field("Ext_Id__c")!!.externalId)
        assertEquals(FieldType.REFERENCE, schema.field("ParentId")!!.type)
        assertEquals(FieldType.DATETIME, schema.field("SystemModstamp")!!.type)
        assertEquals("currency", schema.field("AnnualRevenue")!!.nativeType)
    }

    @Test
    fun `reads one record with typed values and its version`() {
        stub.onJson(HttpMethod.GET, "$API/sobjects/Account/$ID1", account(ID1, "Acme"))
        val record = runBlocking { connector.read(context(), "Account", ID1) }!!
        assertEquals(FieldValue.Text("Acme"), record.fields["Name"])
        assertEquals(FieldValue.Choice("Banking"), record.fields["Industry"])
        assertEquals(FieldValue.Number(BigDecimal("1500.5")), record.fields["AnnualRevenue"])
        assertEquals(FieldValue.Bool(true), record.fields["Active__c"])
        assertEquals(FieldValue.Null, record.fields["ParentId"])
        assertFalse("attributes" in record.fields)
        assertEquals("2026-11-04T12:00:00.000+0000", record.version)
        assertEquals(Instant.parse("2026-11-04T12:00:00Z"), record.modifiedAt)
    }

    @Test
    fun `a missing record reads as null`() {
        stub.on(HttpMethod.GET, "$API/sobjects/Account/$ID1") { _, _ ->
            StubResponse.json("""[{"errorCode":"NOT_FOUND","message":"The requested resource does not exist"}]""", 404)
        }
        assertNull(runBlocking { connector.read(context(), "Account", ID1) })
    }

    @Test
    fun `lists by soql and follows nextRecordsUrl, without compound fields`() {
        val next = "$API/query/01gD0000002HU6KIAW-2000"
        stub.onJson(HttpMethod.GET, "$API/query/", query(listOf(account(ID1, "A")), next))
        stub.onJson(HttpMethod.GET, next, query(listOf(account(ID2, "B"))))
        val first = runBlocking { connector.list(context(), ReadRequest("Account", pageSize = 50)) }
        assertEquals(listOf(ID1), first.records.map { it.id })
        assertEquals(next, first.nextCursor)
        val soql = stub.requests.last { it.path == "$API/query/" }.query["q"]!!
        assertTrue(soql.startsWith("SELECT Id, Name"), soql)
        assertFalse("BillingAddress" in soql)
        assertTrue(soql.endsWith("FROM Account ORDER BY Id"), soql)
        assertEquals("batchSize=200", stub.requests.last { it.path == "$API/query/" }.header("Sforce-Query-Options"))
        val second = runBlocking { connector.list(context(), ReadRequest("Account", cursor = first.nextCursor)) }
        assertEquals(listOf(ID2), second.records.map { it.id })
        assertNull(second.nextCursor)
    }

    @Test
    fun `a cursor that is not a query path is refused`() {
        assertThrows<ConnectorException.Permanent> {
            runBlocking { connector.list(context(), ReadRequest("Account", cursor = "https://evil.example/steal")) }
        }
    }

    @Test
    fun `change feed reads everything first, then changes since the newest stamp, then deletions`() {
        val ctx = context()
        stub.onJson(
            HttpMethod.GET,
            "$API/query/",
            query(listOf(account(ID1, "A", "2026-11-04T11:00:00.000+0000"), account(ID2, "B", "2026-11-04T12:30:15.250+0000")))
        )
        val initial = runBlocking { connector.changes(ctx, "Account", null) }
        assertEquals(2, initial.changes.size)
        assertFalse(initial.hasMore)
        assertFalse(
            stub.requests
                .last()
                .query["q"]!!
                .contains("WHERE")
        )
        val checkpoint = json.readTree(initial.checkpoint)
        assertEquals("2026-11-04T12:30:15.250Z", checkpoint.get("since").asString())
        assertEquals(now.toString(), checkpoint.get("deletedSince").asString())

        // a later poll: SystemModstamp >= the truncated newest stamp, and the deleted window since the last poll
        val later = Clock.fixed(now.plusSeconds(600), ZoneOffset.UTC)
        stub.onJson(HttpMethod.GET, "$API/query/", query(listOf(account(ID2, "B2", "2026-11-04T13:05:00.000+0000"))))
        stub.onJson(
            HttpMethod.GET,
            "$API/sobjects/Account/deleted/",
            """{"deletedRecords":[{"id":"$ID1","deletedDate":"2026-11-04T13:02:00.000+0000"}],
              |"earliestDateAvailable":"2026-10-01T00:00:00.000+0000","latestDateCovered":"2026-11-04T13:09:00.000+0000"}
            """.trimMargin()
        )
        val page = runBlocking { connector.changes(ctx.copy(clock = later), "Account", initial.checkpoint) }
        val soql = stub.requests.last { it.path == "$API/query/" }.query["q"]!!
        assertTrue(soql.contains("WHERE SystemModstamp >= 2026-11-04T12:30:15Z ORDER BY SystemModstamp, Id"), soql)
        val deletedCall = stub.requests.last { it.path.endsWith("/deleted/") }
        assertEquals("2026-11-04T13:00:00+00:00", deletedCall.query["start"])
        assertEquals("2026-11-04T13:10:00+00:00", deletedCall.query["end"])
        assertEquals(listOf(ID2), page.changes.filterIsInstance<Change.Upserted>().map { it.id })
        assertEquals(listOf(ID1), page.changes.filterIsInstance<Change.Deleted>().map { it.id })
        val next = json.readTree(page.checkpoint)
        assertEquals("2026-11-04T13:05:00Z", next.get("since").asString())
        assertEquals("2026-11-04T13:09:00Z", next.get("deletedSince").asString())
    }

    @Test
    fun `change feed pages keep the cycle start until the last page`() {
        val ctx = context()
        val nextUrl = "$API/query/01gD0000002HU6KIAW-200"
        stub.onJson(HttpMethod.GET, "$API/query/", query(listOf(account(ID1, "A", "2026-11-04T11:00:00.000+0000")), nextUrl))
        stub.onJson(HttpMethod.GET, nextUrl, query(listOf(account(ID2, "B", "2026-11-04T12:00:00.000+0000"))))
        val first = runBlocking { connector.changes(ctx, "Account", null) }
        assertTrue(first.hasMore)
        val mid = json.readTree(first.checkpoint)
        assertEquals(nextUrl, mid.get("next").asString())
        assertTrue(mid.path("since").isMissingNode)
        val last = runBlocking { connector.changes(ctx, "Account", first.checkpoint) }
        assertFalse(last.hasMore)
        assertEquals("2026-11-04T12:00:00Z", json.readTree(last.checkpoint).get("since").asString())
    }

    @Test
    fun `create posts the fields and returns the new version`() {
        stub.onJson(HttpMethod.POST, "$API/sobjects/Account/", """{"id":"$ID1","success":true,"errors":[]}""", 201)
        stub.onJson(HttpMethod.GET, "$API/sobjects/Account/$ID1", """{"Id":"$ID1","SystemModstamp":"2026-11-04T12:00:01.000+0000"}""")
        val result =
            runBlocking {
                connector.create(
                    context(),
                    "Account",
                    mapOf("Name" to FieldValue.Text("Acme"), "Tags__c" to FieldValue.MultiChoice(listOf("a", "b")), "ParentId" to FieldValue.Null)
                )
            }
        assertTrue(result.created)
        assertEquals(ID1, result.id)
        assertEquals("2026-11-04T12:00:01.000+0000", result.version)
        val body = json.readTree(stub.requests.first { it.method == HttpMethod.POST }.body)
        assertEquals("Acme", body.get("Name").asString())
        assertEquals("a;b", body.get("Tags__c").asString())
        assertTrue(body.get("ParentId").isNull)
        assertEquals("Id,SystemModstamp", stub.requests.last().query["fields"])
    }

    @Test
    fun `update sends If-Unmodified-Since rounded up, and a 412 is a conflict`() {
        stub.on(HttpMethod.PATCH, "$API/sobjects/Account/$ID1") { _, _ -> StubResponse(204) }
        stub.onJson(HttpMethod.GET, "$API/sobjects/Account/$ID1", """{"Id":"$ID1","SystemModstamp":"2026-11-04T12:00:05.000+0000"}""")
        val result =
            runBlocking {
                connector.update(context(), "Account", ID1, mapOf("Name" to FieldValue.Text("New")), "2026-11-04T12:00:00.250+0000")
            }
        assertFalse(result.created)
        assertEquals("2026-11-04T12:00:05.000+0000", result.version)
        assertEquals("Wed, 4 Nov 2026 12:00:01 GMT", stub.requests.first { it.method == HttpMethod.PATCH }.header("If-Unmodified-Since"))

        stub.on(HttpMethod.PATCH, "$API/sobjects/Account/$ID1") { _, _ -> StubResponse(412) }
        assertThrows<ConnectorException.Conflict> {
            runBlocking { connector.update(context(), "Account", ID1, mapOf("Name" to FieldValue.Text("x")), "2026-11-04T12:00:00.000+0000") }
        }
    }

    @Test
    fun `delete calls the record url`() {
        stub.on(HttpMethod.DELETE, "$API/sobjects/Account/$ID1") { _, _ -> StubResponse(204) }
        runBlocking { connector.delete(context(), "Account", ID1) }
        assertEquals(HttpMethod.DELETE, stub.requests.last().method)
    }

    @Test
    fun `upsert by external id tells created from updated`() {
        stub.onJson(HttpMethod.PATCH, "$API/sobjects/Account/Ext_Id__c/ext%20one", """{"id":"$ID1","success":true,"created":true}""", 201)
        stub.onJson(HttpMethod.GET, "$API/sobjects/Account/$ID1", """{"Id":"$ID1","SystemModstamp":"2026-11-04T12:00:00.000+0000"}""")
        val created =
            runBlocking {
                connector.upsert(context(), "Account", "Ext_Id__c", "ext one", mapOf("Name" to FieldValue.Text("A"), "Ext_Id__c" to FieldValue.Text("ext one")))
            }
        assertTrue(created.created)
        assertEquals(ID1, created.id)
        assertFalse(json.readTree(stub.requests.first { it.method == HttpMethod.PATCH }.body).has("Ext_Id__c"))

        // an update answers 204 without a body: the id is found by the external id
        stub.reset()
        stub.salesforceBasics()
        stub.on(HttpMethod.PATCH, "$API/sobjects/Account/Ext_Id__c/ext%20one") { _, _ -> StubResponse(204) }
        stub.onJson(HttpMethod.GET, "$API/query/", query(listOf("""{"Id":"$ID1"}""")))
        stub.onJson(HttpMethod.GET, "$API/sobjects/Account/$ID1", """{"Id":"$ID1","SystemModstamp":"2026-11-04T12:00:00.000+0000"}""")
        val updated = runBlocking { connector.upsert(context(), "Account", "Ext_Id__c", "ext one", mapOf("Name" to FieldValue.Text("B"))) }
        assertFalse(updated.created)
        assertEquals(ID1, updated.id)
        assertEquals("SELECT Id FROM Account WHERE Ext_Id__c = 'ext one' LIMIT 1", stub.requests.first { it.path == "$API/query/" }.query["q"])
    }

    @Test
    fun `salesforce error codes map to the taxonomy`() {
        fun failWith(
            status: Int,
            code: String
        ): ConnectorException {
            stub.on(HttpMethod.GET, "$API/sobjects/Account/$ID1") { _, _ -> StubResponse.json("""[{"errorCode":"$code","message":"m"}]""", status) }
            return assertThrows { runBlocking { connector.read(context(), "Account", ID1) } }
        }
        assertInstanceOf(ConnectorException.AuthExpired::class.java, failWith(401, "INVALID_SESSION_ID"))
        assertInstanceOf(ConnectorException.RateLimited::class.java, failWith(403, "REQUEST_LIMIT_EXCEEDED"))
        assertInstanceOf(ConnectorException.PermissionDenied::class.java, failWith(403, "INSUFFICIENT_ACCESS_OR_READONLY"))
        assertInstanceOf(ConnectorException.Transient::class.java, failWith(503, "SERVER_UNAVAILABLE"))
        assertInstanceOf(ConnectorException.NotFound::class.java, failWith(400, "ENTITY_IS_DELETED"))
        val permanent = failWith(400, "INVALID_FIELD") as ConnectorException.Permanent
        assertEquals("INVALID_FIELD", permanent.details["errorCode"])
    }

    @Test
    fun `record ids and object names are validated before they reach a url`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { connector.read(context(), "Account", "../../limits") } }
        assertThrows<IllegalArgumentException> { runBlocking { connector.describe(context(), "Account/../x") } }
    }

    @Test
    fun `a connection without an instance url asks for a reconnect`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { connector.listResources(TestConnectorContext()) } }
    }
}
