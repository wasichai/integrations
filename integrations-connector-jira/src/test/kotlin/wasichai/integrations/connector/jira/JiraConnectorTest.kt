package wasichai.integrations.connector.jira

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
import wasichai.integrations.connector.jira.JiraFixtures.API
import wasichai.integrations.connector.jira.JiraFixtures.CLOUD
import wasichai.integrations.connector.jira.JiraFixtures.ISSUE
import wasichai.integrations.connector.jira.JiraFixtures.issue
import wasichai.integrations.connector.jira.JiraFixtures.jiraBasics
import wasichai.integrations.connector.jira.JiraFixtures.search
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.TokenGrant
import wasichai.integrations.sdk.testing.KitProviderHttp
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import wasichai.integrations.sdk.testing.TestConnectorContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JiraConnectorTest {
    private val stub = StubProviderServer()
    private val jira by lazy { JiraConnector(JiraConfig(authUrl = stub.baseUrl, gatewayUrl = stub.baseUrl)) }
    private val json = JsonMapper.builder().build()

    // a fresh connection id per test: cloud id, zone and schema caches are per connection
    private fun context() = TestConnectorContext(metadata = mapOf(JiraConnector.ROUTE to JiraConnector.GATEWAY), connectionId = UUID.randomUUID().toString())

    @BeforeEach
    fun reset() {
        stub.reset()
        stub.jiraBasics()
    }

    @AfterAll
    fun stop() = stub.close()

    private fun body(call: StubProviderServer.Recorded) = json.readTree(call.body)

    @Test
    fun `oauth is atlassian 3lo with offline access, and api tokens are basic`() {
        val oauth =
            jira.manifest.authMethods
                .filterIsInstance<AuthMethod.OAuth2AuthorizationCode>()
                .single()
        assertEquals("${stub.baseUrl}/authorize", oauth.authorizeUrl)
        assertEquals("${stub.baseUrl}/oauth/token", oauth.tokenUrl)
        assertTrue("offline_access" in oauth.defaultScopes)
        assertTrue(AuthMethod.Basic in jira.manifest.authMethods)
        assertEquals(mapOf("audience" to "api.atlassian.com", "prompt" to "consent"), jira.authorizationParameters())
    }

    @Test
    fun `an oauth grant is routed through the gateway, and no token is kept as metadata`() {
        val grant = TokenGrant("secret-access", "secret-refresh", 3600, emptyList(), mapOf("access_token" to "secret-access", "scope" to "read:jira-work"))
        assertEquals(mapOf("route" to "gateway"), jira.connectionMetadata(grant))
    }

    @Test
    fun `account is the jira site the grant reaches, confluence-only sites ignored`() {
        val account = runBlocking { jira.accountInfo(context()) }
        assertEquals(CLOUD, account.providerAccountId)
        assertEquals("acme", account.displayName)
        assertEquals("https://acme.atlassian.net", account.metadata["site_url"])
    }

    @Test
    fun `several jira sites without a pin are refused, never guessed, and a pin picks one`() {
        val other = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
        stub.onJson(
            HttpMethod.GET,
            "/oauth/token/accessible-resources",
            """[{"id":"$CLOUD","url":"https://a.atlassian.net","name":"a","scopes":["read:jira-work"]},
               |{"id":"$other","url":"https://b.atlassian.net","name":"b","scopes":["read:jira-work"]}]
            """.trimMargin()
        )
        assertThrows<ConnectorException.Permanent> { runBlocking { jira.accountInfo(context()) } }
        val pinned = JiraConnector(JiraConfig(authUrl = stub.baseUrl, gatewayUrl = stub.baseUrl, cloudId = other))
        assertEquals(other, runBlocking { pinned.accountInfo(context()) }.providerAccountId)
        val missing = JiraConnector(JiraConfig(gatewayUrl = stub.baseUrl, cloudId = "00000000-0000-4000-8000-000000000000"))
        assertThrows<ConnectorException.PermissionDenied> { runBlocking { missing.accountInfo(context()) } }
    }

    @Test
    fun `the cloud id is looked up once per connection`() {
        val ctx = context()
        runBlocking {
            jira.listResources(ctx)
            jira.listResources(ctx)
        }
        assertEquals(1, stub.requests.count { it.path == "/oauth/token/accessible-resources" })
        assertEquals(2, stub.requests.count { it.path == "$API/project/search" })
    }

    @Test
    fun `api token connections talk to the configured site`() {
        stub.onJson(HttpMethod.GET, "/rest/api/3/myself", """{"accountId":"557058:abc","displayName":"Ana"}""")
        stub.onJson(HttpMethod.GET, "/rest/api/3/project/search", JiraFixtures.projectSearch)
        val site = JiraConnector(JiraConfig(gatewayUrl = stub.baseUrl, siteUrl = stub.baseUrl))
        val ctx = TestConnectorContext(KitProviderHttp(null))
        val account = runBlocking { site.accountInfo(ctx) }
        assertEquals(stub.baseUrl, account.providerAccountId)
        assertEquals("557058:abc", account.metadata["account_id"])
        assertEquals(listOf("PROJ"), runBlocking { site.listResources(ctx) }.map { it.name })
        assertTrue(stub.requests.none { it.path.startsWith("/oauth") || it.path.startsWith("/ex/") })
        // without a site an api token connection cannot work: said plainly
        assertThrows<ConnectorException.Permanent> { runBlocking { jira.listResources(TestConnectorContext()) } }
    }

    @Test
    fun `projects are the resources, paged by startAt`() {
        stub.on(HttpMethod.GET, "$API/project/search") { call, _ ->
            if (call.query["startAt"] == "0") {
                StubResponse.json("""{"startAt":0,"maxResults":1,"total":2,"isLast":false,"values":[{"id":"1","key":"PROJ","name":"One"}]}""")
            } else {
                StubResponse.json("""{"startAt":1,"maxResults":1,"total":2,"isLast":true,"values":[{"id":"2","key":"OPS","name":"Ops"}]}""")
            }
        }
        val found = runBlocking { jira.listResources(context()) }
        assertEquals(listOf("PROJ" to "One", "OPS" to "Ops"), found.map { it.name to it.label })
        assertEquals("1", stub.requests.last { it.path == "$API/project/search" }.query["startAt"])
    }

    @Test
    fun `describe maps field schemas, custom fields included`() {
        val schema = runBlocking { jira.describe(context(), "PROJ") }
        assertEquals("PROJ", schema.resource)
        assertEquals("Project One", schema.label)
        assertEquals(FieldType.TEXT, schema.field("summary")!!.type)
        assertTrue(schema.field("summary")!!.required)
        assertEquals(FieldType.TEXT, schema.field("description")!!.type)
        assertEquals(FieldType.CHOICE, schema.field("issuetype")!!.type)
        assertEquals(listOf("Task", "Bug"), schema.field("issuetype")!!.options)
        assertTrue(schema.field("status")!!.readOnly, "status moves through transitions")
        assertTrue(schema.field("updated")!!.readOnly)
        assertEquals(FieldType.DATETIME, schema.field("updated")!!.type)
        assertEquals(FieldType.MULTI_CHOICE, schema.field("labels")!!.type)
        assertEquals(FieldType.REFERENCE, schema.field("assignee")!!.type)
        assertEquals(FieldType.DATE, schema.field("duedate")!!.type)
        assertEquals(FieldType.NUMBER, schema.field("customfield_10016")!!.type)
        assertEquals("com.atlassian.jira.plugin.system.customfieldtypes:float", schema.field("customfield_10016")!!.nativeType)
        assertEquals(FieldType.CHOICE, schema.field("customfield_10020")!!.type)
        assertEquals(FieldType.TEXT, schema.field("customfield_10030")!!.type)
        assertEquals(FieldType.JSON, schema.field("customfield_10040")!!.type)
        assertTrue(schema.field("customfield_10040")!!.readOnly)
        assertTrue(schema.field("key")!!.readOnly)
        assertNull(schema.field("issuekey"))
    }

    @Test
    fun `reads an issue as typed fields, rich text as plain text, versioned by updated`() {
        stub.onJson(HttpMethod.GET, "$API/issue/$ISSUE", issue())
        val record = runBlocking { jira.read(context(), "PROJ", ISSUE) }!!
        assertEquals(ISSUE, record.id)
        assertEquals("2026-11-04T12:00:00.000+0000", record.version)
        assertEquals(Instant.parse("2026-11-04T12:00:00Z"), record.modifiedAt)
        assertEquals(FieldValue.Text("PROJ-1"), record.fields["key"])
        assertEquals(FieldValue.Text("First"), record.fields["summary"])
        assertEquals(FieldValue.Text("Line one\nLine two\nsame block"), record.fields["description"])
        assertEquals(FieldValue.Choice("Task"), record.fields["issuetype"])
        assertEquals(FieldValue.Choice("In Progress"), record.fields["status"])
        assertEquals(FieldValue.MultiChoice(listOf("backend", "urgent")), record.fields["labels"])
        assertEquals(FieldValue.MultiChoice(listOf("API")), record.fields["components"])
        assertEquals(FieldValue.Reference("user", "5b10ac8d82e05b22cc7d4ef5"), record.fields["assignee"])
        assertEquals(FieldValue.Date(LocalDate.of(2026, 12, 1)), record.fields["duedate"])
        assertEquals(FieldValue.Number(BigDecimal(5)), record.fields["customfield_10016"])
        assertEquals(FieldValue.Choice("Core"), record.fields["customfield_10020"])
        assertEquals(FieldValue.Null, record.fields["customfield_10030"])
        assertEquals("*all", stub.requests.last().query["fields"])
    }

    @Test
    fun `missing issues, and issues moved to another project, read as absent`() {
        stub.onJson(
            HttpMethod.GET,
            "$API/issue/$ISSUE",
            """{"errorMessages":["Issue does not exist or you do not have permission to see it."],"errors":{}}""",
            404
        )
        assertNull(runBlocking { jira.read(context(), "PROJ", ISSUE) })
        stub.onJson(HttpMethod.GET, "$API/issue/$ISSUE", issue(project = "OPS"))
        assertNull(runBlocking { jira.read(context(), "PROJ", ISSUE) })
    }

    @Test
    fun `ids and keys that are not jira ids never reach a url or a query`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { jira.read(context(), "PROJ", "../myself") } }
        assertThrows<ConnectorException.Permanent> { runBlocking { jira.list(context(), ReadRequest("PROJ\" OR project != \"X")) } }
        assertTrue(stub.requests.none { it.path.contains("myself") || it.path.endsWith("/search/jql") })
    }

    @Test
    fun `list runs the enhanced jql search and follows nextPageToken`() {
        stub.on(HttpMethod.POST, "$API/search/jql") { call, _ ->
            if (json.readTree(call.body).has("nextPageToken")) {
                StubResponse.json(search(listOf(issue(id = "10002", key = "PROJ-2"))))
            } else {
                StubResponse.json(search(listOf(issue()), next = "tok-2"))
            }
        }
        val first = runBlocking { jira.list(context(), ReadRequest("PROJ", pageSize = 50)) }
        assertEquals(listOf(ISSUE), first.records.map { it.id })
        assertEquals("tok-2", first.nextCursor)
        val request = body(stub.requests.last { it.path == "$API/search/jql" })
        assertEquals("project = \"PROJ\" ORDER BY id ASC", request.path("jql").asString())
        assertEquals(50, request.path("maxResults").asInt())
        assertEquals("*all", request.path("fields")[0].asString())
        val second = runBlocking { jira.list(context(), ReadRequest("PROJ", cursor = first.nextCursor)) }
        assertEquals("tok-2", body(stub.requests.last { it.path == "$API/search/jql" }).path("nextPageToken").asString())
        assertEquals(listOf("10002"), second.records.map { it.id })
        assertNull(second.nextCursor)
    }

    @Test
    fun `change feed reads everything first, then from a minute before the newest edit, in the user's zone`() {
        val ctx = context()
        val jqls = mutableListOf<String>()
        stub.on(HttpMethod.POST, "$API/search/jql") { call, _ ->
            val request = json.readTree(call.body)
            jqls += request.path("jql").asString()
            when {
                jqls.size == 1 -> StubResponse.json(search(listOf(issue(updated = "2026-11-04T11:00:00.000+0000")), next = "tok-2"))
                jqls.size == 2 -> StubResponse.json(search(listOf(issue(id = "10002", updated = "2026-11-04T12:30:15.250+0000"))))
                else -> StubResponse.json(search(listOf(issue(id = "10002", updated = "2026-11-04T12:30:15.250+0000"))))
            }
        }
        val first = runBlocking { jira.changes(ctx, "PROJ", null) }
        assertTrue(first.hasMore)
        assertEquals("project = \"PROJ\" ORDER BY updated ASC, id ASC", jqls[0])
        val second = runBlocking { jira.changes(ctx, "PROJ", first.checkpoint) }
        assertFalse(second.hasMore)
        assertEquals(jqls[0], jqls[1], "a pass keeps its query while it pages")
        assertInstanceOf(Change.Upserted::class.java, second.changes.single())
        assertEquals("2026-11-04T12:30:15.250Z", json.readTree(second.checkpoint).path("since").asString())

        // 12:30:15Z, a minute back, in America/Santiago (utc-3 in november): 09:29
        val third = runBlocking { jira.changes(ctx, "PROJ", second.checkpoint) }
        assertEquals("project = \"PROJ\" AND updated >= \"2026-11-04 09:29\" ORDER BY updated ASC, id ASC", jqls[2])
        assertEquals(1, third.changes.size, "the boundary issue comes again: at least once")
        assertEquals(1, stub.requests.count { it.path == "$API/myself" })
    }

    @Test
    fun `an unreadable checkpoint is refused`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { jira.changes(context(), "PROJ", "not json") } }
    }

    @Test
    fun `create posts the fields in jira shapes and reads the version back`() {
        stub.onJson(HttpMethod.POST, "$API/issue", """{"id":"$ISSUE","key":"PROJ-1","self":"x"}""", 201)
        stub.onJson(HttpMethod.GET, "$API/issue/$ISSUE", """{"id":"$ISSUE","key":"PROJ-1","fields":{"updated":"2026-11-04T12:00:01.000+0000"}}""")
        val result =
            runBlocking {
                jira.create(
                    context(),
                    "PROJ",
                    mapOf(
                        "summary" to FieldValue.Text("New"),
                        "issuetype" to FieldValue.Choice("Bug"),
                        "description" to FieldValue.Text("a\n\nb"),
                        "labels" to FieldValue.MultiChoice(listOf("x", "y")),
                        "priority" to FieldValue.Choice("High"),
                        "assignee" to FieldValue.Reference("user", "5b10ac8d82e05b22cc7d4ef5"),
                        "customfield_10016" to FieldValue.Number(BigDecimal("3.5")),
                        "customfield_10020" to FieldValue.Choice("Core"),
                        "components" to FieldValue.MultiChoice(listOf("API")),
                        "duedate" to FieldValue.Date(LocalDate.of(2026, 12, 1))
                    )
                )
            }
        assertTrue(result.created)
        assertEquals(ISSUE, result.id)
        assertEquals("2026-11-04T12:00:01.000+0000", result.version)
        val fields = body(stub.requests.first { it.method == HttpMethod.POST }).path("fields")
        assertEquals("PROJ", fields.path("project").path("key").asString())
        assertEquals("New", fields.path("summary").asString())
        assertEquals("Bug", fields.path("issuetype").path("name").asString())
        assertEquals("High", fields.path("priority").path("name").asString())
        assertEquals("5b10ac8d82e05b22cc7d4ef5", fields.path("assignee").path("accountId").asString())
        assertEquals("y", fields.path("labels")[1].asString())
        assertEquals("API", fields.path("components")[0].path("name").asString())
        assertEquals("Core", fields.path("customfield_10020").path("value").asString())
        assertEquals(BigDecimal("3.5"), fields.path("customfield_10016").decimalValue())
        assertEquals("2026-12-01", fields.path("duedate").asString())
        val doc = fields.path("description")
        assertEquals("doc", doc.path("type").asString())
        assertEquals(3, doc.path("content").size())
        assertEquals(
            "b",
            doc
                .path("content")[2]
                .path("content")[0]
                .path("text")
                .asString()
        )
        assertFalse(doc.path("content")[1].has("content"), "an empty line is an empty paragraph")
        assertEquals("updated", stub.requests.last().query["fields"])
    }

    @Test
    fun `writing a read-only or unknown field is refused before any write`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { jira.create(context(), "PROJ", mapOf("status" to FieldValue.Choice("Done"))) } }
        assertThrows<ConnectorException.Permanent> { runBlocking { jira.create(context(), "PROJ", mapOf("customfield_99999" to FieldValue.Text("x"))) } }
        assertTrue(stub.requests.none { it.method == HttpMethod.POST })
    }

    @Test
    fun `update with a stale expected version is a conflict and writes nothing`() {
        stub.onJson(HttpMethod.GET, "$API/issue/$ISSUE", """{"id":"$ISSUE","fields":{"updated":"2026-11-04T13:00:00.000+0000"}}""")
        val error =
            assertThrows<ConnectorException.Conflict> {
                runBlocking { jira.update(context(), "PROJ", ISSUE, mapOf("summary" to FieldValue.Text("B")), "2026-11-04T12:00:00.000+0000") }
            }
        assertEquals("2026-11-04T13:00:00.000+0000", error.currentVersion)
        assertTrue(stub.requests.none { it.method == HttpMethod.PUT })
    }

    @Test
    fun `update with the current version edits the issue`() {
        var updated = "2026-11-04T12:00:00.000+0000"
        stub.on(HttpMethod.GET, "$API/issue/$ISSUE") { _, _ -> StubResponse.json("""{"id":"$ISSUE","fields":{"updated":"$updated"}}""") }
        stub.on(HttpMethod.PUT, "$API/issue/$ISSUE") { _, _ ->
            updated = "2026-11-04T12:05:00.000+0000"
            StubResponse(204)
        }
        val result =
            runBlocking { jira.update(context(), "PROJ", ISSUE, mapOf("customfield_10030" to FieldValue.Null), "2026-11-04T12:00:00.000+0000") }
        assertFalse(result.created)
        assertEquals("2026-11-04T12:05:00.000+0000", result.version)
        assertTrue(body(stub.requests.single { it.method == HttpMethod.PUT }).path("fields").path("customfield_10030").isNull)
    }

    @Test
    fun `delete calls the issue url`() {
        stub.on(HttpMethod.DELETE, "$API/issue/$ISSUE") { _, _ -> StubResponse(204) }
        runBlocking { jira.delete(context(), "PROJ", ISSUE) }
        assertEquals(HttpMethod.DELETE, stub.requests.last().method)
    }

    @Test
    fun `http statuses map to the taxonomy, with retry-after and jira's error details`() {
        fun failWith(
            status: Int,
            headers: Map<String, String> = emptyMap()
        ): ConnectorException {
            stub.on(HttpMethod.GET, "$API/issue/$ISSUE") { _, _ ->
                StubResponse.json("""{"errorMessages":["nope"],"errors":{"summary":"You must specify a summary."}}""", status, headers)
            }
            return assertThrows { runBlocking { jira.read(context(), "PROJ", ISSUE) } }
        }
        assertInstanceOf(ConnectorException.AuthExpired::class.java, failWith(401))
        assertInstanceOf(ConnectorException.PermissionDenied::class.java, failWith(403))
        assertInstanceOf(ConnectorException.Conflict::class.java, failWith(409))
        val limited = failWith(429, mapOf("Retry-After" to "7")) as ConnectorException.RateLimited
        assertEquals(7, limited.retryAfter!!.seconds)
        assertInstanceOf(ConnectorException.Transient::class.java, failWith(503))
        val permanent = failWith(400) as ConnectorException.Permanent
        assertEquals("nope", permanent.details["errorMessages"])
        assertEquals("You must specify a summary.", permanent.details["field.summary"])
        assertFalse(permanent.message!!.contains("summary"), "provider text stays out of the message")
        // a 404 on a list is not "absent": it is an error
        stub.onJson(HttpMethod.POST, "$API/search/jql", """{"errorMessages":["x"]}""", 404)
        assertThrows<ConnectorException.NotFound> { runBlocking { jira.list(context(), ReadRequest("PROJ")) } }
    }

    @Test
    fun `transition by name finds the id among the available ones`() {
        stub.onJson(
            HttpMethod.GET,
            "$API/issue/$ISSUE/transitions",
            """{"expand":"transitions","transitions":[{"id":"11","name":"To Do","to":{"name":"To Do"}},{"id":"31","name":"Done","to":{"name":"Done"}}]}"""
        )
        stub.on(HttpMethod.POST, "$API/issue/$ISSUE/transitions") { _, _ -> StubResponse(204) }
        val out = runBlocking { jira.execute(context(), JiraConnector.TRANSITION, json.readTree("""{"issueId":"$ISSUE","transition":"done"}""")) }
        assertEquals("31", out.path("transitionId").asString())
        assertEquals("Done", out.path("status").asString())
        assertEquals("31", body(stub.requests.last()).path("transition").path("id").asString())

        val error =
            assertThrows<ConnectorException.Permanent> {
                runBlocking { jira.execute(context(), JiraConnector.TRANSITION, json.readTree("""{"issueId":"$ISSUE","transition":"Archive"}""")) }
            }
        assertEquals("To Do, Done", error.details["available"])
        assertEquals(1, stub.requests.count { it.method == HttpMethod.POST })
    }

    @Test
    fun `dynamic webhooks register, refresh and delete through the gateway`() {
        stub.onJson(HttpMethod.POST, "$API/webhook", """{"webhookRegistrationResult":[{"createdWebhookId":1000}]}""")
        stub.onJson(HttpMethod.PUT, "$API/webhook/refresh", """{"expirationDate":"2026-12-04T12:00:00.000+0000"}""")
        stub.on(HttpMethod.DELETE, "$API/webhook") { _, _ -> StubResponse(202) }
        val ctx = context()
        val registered =
            runBlocking {
                jira.execute(
                    ctx,
                    JiraConnector.REGISTER_WEBHOOK,
                    json.readTree("""{"url":"https://app.example/webhooks/jira/e1","jqlFilter":"project = PROJ"}""")
                )
            }
        assertEquals(1000, registered.path("webhookId").asInt())
        val sent = body(stub.requests.last())
        assertEquals("project = PROJ", sent.path("webhooks")[0].path("jqlFilter").asString())
        assertEquals(3, sent.path("webhooks")[0].path("events").size())
        val refreshed = runBlocking { jira.execute(ctx, JiraConnector.REFRESH_WEBHOOKS, json.readTree("""{"webhookIds":[1000]}""")) }
        assertEquals("2026-12-04T12:00:00.000+0000", refreshed.path("expirationDate").asString())
        runBlocking { jira.execute(ctx, JiraConnector.DELETE_WEBHOOKS, json.readTree("""{"webhookIds":[1000]}""")) }
        assertEquals(1000, body(stub.requests.last()).path("webhookIds")[0].asInt())
    }

    @Test
    fun `a refused webhook registration is an error, not a silent success`() {
        stub.onJson(HttpMethod.POST, "$API/webhook", """{"webhookRegistrationResult":[{"errors":["The JQL filter is not supported."]}]}""")
        val error =
            assertThrows<ConnectorException.Permanent> {
                runBlocking {
                    jira.execute(
                        context(),
                        JiraConnector.REGISTER_WEBHOOK,
                        json.readTree("""{"url":"https://a.example/h","jqlFilter":"text ~ x"}""")
                    )
                }
            }
        assertEquals("The JQL filter is not supported.", error.details["errors"])
        // api token connections have no dynamic webhooks
        val site = JiraConnector(JiraConfig(siteUrl = stub.baseUrl))
        assertThrows<ConnectorException.Permanent> {
            runBlocking {
                site.execute(
                    TestConnectorContext(),
                    JiraConnector.REGISTER_WEBHOOK,
                    json.readTree("""{"url":"https://a.example/h","jqlFilter":"project = P"}""")
                )
            }
        }
        assertThrows<ConnectorException.Permanent> { runBlocking { jira.execute(context(), "drop_database", json.readTree("{}")) } }
    }
}
