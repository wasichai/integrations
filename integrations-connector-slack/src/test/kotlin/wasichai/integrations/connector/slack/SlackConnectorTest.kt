package wasichai.integrations.connector.slack

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
import wasichai.integrations.connector.slack.SlackFixtures.CHANNEL
import wasichai.integrations.connector.slack.SlackFixtures.channel
import wasichai.integrations.connector.slack.SlackFixtures.channels
import wasichai.integrations.connector.slack.SlackFixtures.error
import wasichai.integrations.connector.slack.SlackFixtures.history
import wasichai.integrations.connector.slack.SlackFixtures.message
import wasichai.integrations.connector.slack.SlackFixtures.slackBasics
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import wasichai.integrations.sdk.testing.TestConnectorContext
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackConnectorTest {
    private val stub = StubProviderServer()
    private val slack by lazy { SlackConnector(SlackConfig(webUrl = stub.baseUrl, apiUrl = stub.baseUrl)) }
    private val json = JsonMapper.builder().build()
    private val context = TestConnectorContext()

    @BeforeEach
    fun reset() {
        stub.reset()
        stub.slackBasics()
    }

    @AfterAll
    fun stop() = stub.close()

    private fun body(call: StubProviderServer.Recorded) = json.readTree(call.body)

    private fun histories() = stub.requests.filter { it.path == "/conversations.history" }

    @Test
    fun `oauth is slack's v2 flow with bot scopes, comma separated, and bot tokens are bearer api keys`() {
        val oauth =
            slack.manifest.authMethods
                .filterIsInstance<AuthMethod.OAuth2AuthorizationCode>()
                .single()
        assertEquals("${stub.baseUrl}/oauth/v2/authorize", oauth.authorizeUrl)
        assertEquals("${stub.baseUrl}/oauth.v2.access", oauth.tokenUrl)
        assertEquals(",", oauth.scopeSeparator)
        assertTrue("chat:write" in oauth.defaultScopes && "channels:history" in oauth.defaultScopes)
        assertFalse(oauth.pkce)
        assertNull(oauth.revokeUrl, "auth.revoke is not rfc 7009")
        assertTrue(AuthMethod.ApiKey("Authorization", "Bearer ") in slack.manifest.authMethods)
        val production =
            SlackConnector()
                .manifest.authMethods
                .filterIsInstance<AuthMethod.OAuth2AuthorizationCode>()
                .single()
        assertEquals("https://slack.com/oauth/v2/authorize", production.authorizeUrl)
        assertEquals("https://slack.com/api/oauth.v2.access", production.tokenUrl)
    }

    @Test
    fun `the account is the workspace, prefixed by its enterprise on a grid`() {
        val account = runBlocking { slack.accountInfo(context) }
        assertEquals("T12345678", account.providerAccountId)
        assertEquals("Subarachnoid Workspace", account.displayName)
        assertEquals("W12345678", account.metadata["bot_user_id"])
        assertEquals("Bearer test-token", stub.requests.single().header("Authorization"))
        stub.onJson(HttpMethod.GET, "/auth.test", """{"ok":true,"team":"Acme East","team_id":"T0EAST","enterprise_id":"E0ACME","user_id":"U0BOT"}""")
        val grid = runBlocking { slack.accountInfo(context) }
        assertEquals("E0ACME:T0EAST", grid.providerAccountId)
        assertEquals("E0ACME", grid.metadata["enterprise_id"])
        stub.onJson(HttpMethod.GET, "/auth.test", error("invalid_auth"))
        assertThrows<ConnectorException.AuthExpired> { runBlocking { slack.accountInfo(context) } }
    }

    @Test
    fun `channels the bot is in are the resources, paged by next_cursor`() {
        stub.on(HttpMethod.GET, "/conversations.list") { call, _ ->
            if (call.query["cursor"] == "dGVhbTpDMDYxRkE1UEI=") {
                StubResponse.json(channels(listOf(channel("C0RANDOM", "random"), channel("C0OTHER", "not-joined", member = false))))
            } else {
                StubResponse.json(channels(listOf(channel()), next = "dGVhbTpDMDYxRkE1UEI="))
            }
        }
        val found = runBlocking { slack.listResources(context) }
        assertEquals(listOf(CHANNEL, "C0RANDOM"), found.map { it.name })
        assertEquals("#general", found.first().label)
        assertEquals("This channel is for team-wide communication.", found.first().description)
        val first = stub.requests.first().query
        assertEquals("public_channel,private_channel", first["types"])
        assertEquals("true", first["exclude_archived"])
        assertNull(first["cursor"])
        assertEquals(2, stub.requests.size)
    }

    @Test
    fun `describe gives the message fields, an unknown channel says so`() {
        val schema = runBlocking { slack.describe(context, CHANNEL) }
        assertEquals(CHANNEL, schema.resource)
        assertEquals("#general", schema.label)
        assertTrue(schema.field("text")!!.required)
        assertTrue(schema.field("ts")!!.readOnly)
        assertEquals(FieldType.REFERENCE, schema.field("user")!!.type)
        assertEquals(FieldType.JSON, schema.field("blocks")!!.type)
        assertEquals(CHANNEL, stub.requests.single().query["channel"])
        stub.onJson(HttpMethod.GET, "/conversations.info", error("channel_not_found"))
        assertThrows<ConnectorException.NotFound> { runBlocking { slack.describe(context, CHANNEL) } }
    }

    @Test
    fun `reads one message by the history between its ts and itself, versioned by edited ts`() {
        stub.onJson(HttpMethod.GET, "/conversations.history", history(listOf(message(edited = "1512086000.000000", replies = 2, thread = "1512085950.000216"))))
        val record = runBlocking { slack.read(context, CHANNEL, "1512085950.000216") }!!
        assertEquals("1512085950.000216", record.id)
        assertEquals("1512086000.000000", record.version)
        assertEquals(Instant.ofEpochSecond(1512086000), record.modifiedAt)
        assertEquals(FieldValue.Text("Hello world"), record.fields["text"])
        assertEquals(FieldValue.Reference("user", "U012AB3CDE"), record.fields["user"])
        assertEquals(FieldValue.Number(BigDecimal(2)), record.fields["reply_count"])
        assertEquals(FieldValue.Null, record.fields["subtype"])
        assertEquals(FieldValue.DateTime(Instant.parse("2017-11-30T23:52:30.000216Z")), record.fields["posted_at"])
        assertEquals(FieldValue.DateTime(Instant.ofEpochSecond(1512086000)), record.fields["edited_at"])
        assertInstanceOf(FieldValue.Json::class.java, record.fields["blocks"])
        val query = stub.requests.single().query
        assertEquals(
            mapOf("channel" to CHANNEL, "latest" to "1512085950.000216", "oldest" to "1512085950.000216", "inclusive" to "true", "limit" to "1"),
            query
        )
        // never edited: the ts is the version
        stub.onJson(HttpMethod.GET, "/conversations.history", history(listOf(message())))
        assertEquals("1512085950.000216", runBlocking { slack.read(context, CHANNEL, "1512085950.000216") }!!.version)
    }

    @Test
    fun `a deleted message or a tombstone reads as absent, lost access does not`() {
        stub.onJson(HttpMethod.GET, "/conversations.history", history(emptyList()))
        assertNull(runBlocking { slack.read(context, CHANNEL, "1512085950.000216") })
        stub.onJson(HttpMethod.GET, "/conversations.history", history(listOf(message(subtype = "tombstone", text = "This message was deleted."))))
        assertNull(runBlocking { slack.read(context, CHANNEL, "1512085950.000216") })
        stub.onJson(HttpMethod.GET, "/conversations.history", error("not_in_channel"))
        assertThrows<ConnectorException.PermissionDenied> { runBlocking { slack.read(context, CHANNEL, "1512085950.000216") } }
        stub.onJson(HttpMethod.GET, "/conversations.history", error("channel_not_found"))
        assertThrows<ConnectorException.NotFound> { runBlocking { slack.read(context, CHANNEL, "1512085950.000216") } }
    }

    @Test
    fun `ids that are not slack's never reach a call`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { slack.read(context, CHANNEL, "../x") } }
        assertThrows<ConnectorException.Permanent> { runBlocking { slack.read(context, "general", "1512085950.000216") } }
        assertThrows<ConnectorException.Permanent> { runBlocking { slack.list(context, ReadRequest("C012&token=x")) } }
        assertThrows<ConnectorException.Permanent> { runBlocking { slack.delete(context, CHANNEL, "1512085950") } }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `list pages the history by cursor and leaves tombstones out`() {
        stub.on(HttpMethod.GET, "/conversations.history") { call, _ ->
            if (call.query["cursor"] == "bmV4dF90czoxNTEyMDg1ODYxMDAwNTQz") {
                StubResponse.json(history(listOf(message("1512085800.000100", subtype = "channel_join"))))
            } else {
                StubResponse.json(
                    history(
                        listOf(message("1512085950.000216"), message("1512085900.000200", subtype = "tombstone")),
                        next = "bmV4dF90czoxNTEyMDg1ODYxMDAwNTQz"
                    )
                )
            }
        }
        val first = runBlocking { slack.list(context, ReadRequest(CHANNEL, pageSize = 50)) }
        assertEquals(listOf("1512085950.000216"), first.records.map { it.id })
        assertEquals("bmV4dF90czoxNTEyMDg1ODYxMDAwNTQz", first.nextCursor)
        assertEquals(mapOf("channel" to CHANNEL, "limit" to "50"), histories().single().query)
        val second = runBlocking { slack.list(context, ReadRequest(CHANNEL, cursor = first.nextCursor, pageSize = 1000)) }
        assertEquals(listOf("1512085800.000100"), second.records.map { it.id })
        assertEquals(FieldValue.Text("channel_join"), second.records.single().fields["subtype"])
        assertNull(second.nextCursor)
        assertEquals("999", histories().last().query["limit"])
    }

    @Test
    fun `change feed pages one pass by cursor, then starts the next pass ten seconds before the newest ts`() {
        val queries = mutableListOf<Map<String, String>>()
        stub.on(HttpMethod.GET, "/conversations.history") { call, _ ->
            queries += call.query
            when (queries.size) {
                1 -> StubResponse.json(history(listOf(message("1512086050.000300"), message("1512086000.000250")), next = "c2Vjb25k"))
                2 -> StubResponse.json(history(listOf(message("1512085950.000216"), message("1512085900.000100", subtype = "tombstone"))))
                else -> StubResponse.json(history(listOf(message("1512086050.000300", edited = "1512086055.000000"))))
            }
        }
        val first = runBlocking { slack.changes(context, CHANNEL, null, 2) }
        assertTrue(first.hasMore)
        assertEquals(listOf("1512086050.000300", "1512086000.000250"), first.changes.map { it.id })
        assertEquals(mapOf("channel" to CHANNEL, "limit" to "2"), queries[0])

        val second = runBlocking { slack.changes(context, CHANNEL, first.checkpoint, 2) }
        assertEquals("c2Vjb25k", queries[1]["cursor"])
        assertFalse(second.hasMore)
        assertInstanceOf(Change.Deleted::class.java, second.changes[1], "a tombstone is a deletion polling can see")
        assertEquals(SlackCheckpoint("1512086040.000300").render(), second.checkpoint)

        val third = runBlocking { slack.changes(context, CHANNEL, second.checkpoint, 2) }
        assertEquals("1512086040.000300", queries[2]["oldest"])
        assertEquals("true", queries[2]["inclusive"])
        assertNull(queries[2]["cursor"])
        assertEquals(listOf("1512086050.000300"), third.changes.map { it.id }, "the overlap reads the newest again: at least once")
        assertEquals(second.checkpoint, third.checkpoint)
    }

    @Test
    fun `an empty pass keeps its oldest`() {
        stub.onJson(HttpMethod.GET, "/conversations.history", history(emptyList()))
        val start = SlackCheckpoint("1512086040.000300").render()
        val page = runBlocking { slack.changes(context, CHANNEL, start, 10) }
        assertTrue(page.changes.isEmpty())
        assertEquals(start, page.checkpoint)
        assertThrows<ConnectorException.Permanent> { runBlocking { slack.changes(context, CHANNEL, "not json", 10) } }
    }

    @Test
    fun `create posts the text as json with a charset`() {
        stub.onJson(
            HttpMethod.POST,
            "/chat.postMessage",
            """{"ok":true,"channel":"$CHANNEL","ts":"1503435956.000247","message":{"text":"Here's a message for you","username":"ecto1","bot_id":"B123ABC456",
               |"type":"message","subtype":"bot_message","ts":"1503435956.000247"}}
            """.trimMargin()
        )
        val result = runBlocking { slack.create(context, CHANNEL, mapOf("text" to FieldValue.Text("Here's a message for you"))) }
        assertTrue(result.created)
        assertEquals("1503435956.000247", result.id)
        assertEquals("1503435956.000247", result.version)
        val call = stub.requests.single()
        assertEquals("application/json; charset=utf-8", call.header("Content-Type"))
        assertEquals(CHANNEL, body(call).path("channel").asString())
        assertEquals("Here's a message for you", body(call).path("text").asString())
    }

    @Test
    fun `read-only, unknown or empty fields are refused before any write`() {
        fun refused(fields: Map<String, FieldValue>) = assertThrows<ConnectorException.Permanent> { runBlocking { slack.create(context, CHANNEL, fields) } }
        refused(mapOf("text" to FieldValue.Text("x"), "user" to FieldValue.Reference("user", "U1")))
        refused(mapOf("text" to FieldValue.Text("x"), "channel" to FieldValue.Text("C0OTHER")))
        refused(mapOf("text" to FieldValue.Null))
        refused(mapOf("text" to FieldValue.Text(" ")))
        refused(emptyMap())
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `update with a stale expected version is a conflict and writes nothing`() {
        stub.onJson(HttpMethod.GET, "/conversations.history", history(listOf(message(edited = "1512086100.000000"))))
        val error =
            assertThrows<ConnectorException.Conflict> {
                runBlocking { slack.update(context, CHANNEL, "1512085950.000216", mapOf("text" to FieldValue.Text("B")), "1512085950.000216") }
            }
        assertEquals("1512086100.000000", error.currentVersion)
        assertTrue(stub.requests.none { it.method == HttpMethod.POST })
        stub.onJson(HttpMethod.GET, "/conversations.history", history(emptyList()))
        assertThrows<ConnectorException.NotFound> {
            runBlocking { slack.update(context, CHANNEL, "1512085950.000216", mapOf("text" to FieldValue.Text("B")), "1512085950.000216") }
        }
    }

    @Test
    fun `update edits the text and reads the message back for its version`() {
        var edited = false
        stub.on(HttpMethod.GET, "/conversations.history") { _, _ ->
            StubResponse.json(history(listOf(if (edited) message(text = "B", edited = "1512086200.000000") else message())))
        }
        stub.on(HttpMethod.POST, "/chat.update") { _, _ ->
            edited = true
            StubResponse.json("""{"ok":true,"channel":"$CHANNEL","ts":"1512085950.000216","text":"B","message":{"text":"B","user":"U012AB3CDE"}}""")
        }
        val result = runBlocking { slack.update(context, CHANNEL, "1512085950.000216", mapOf("text" to FieldValue.Text("B")), "1512085950.000216") }
        assertFalse(result.created)
        assertEquals("1512086200.000000", result.version)
        val posted = body(stub.requests.single { it.method == HttpMethod.POST })
        assertEquals(setOf("channel", "ts", "text"), posted.properties().map { it.key }.toSet())
        assertEquals("1512085950.000216", posted.path("ts").asString())
    }

    @Test
    fun `delete removes the message, someone else's is refused`() {
        stub.onJson(HttpMethod.POST, "/chat.delete", """{"ok":true,"channel":"$CHANNEL","ts":"1512085950.000216"}""")
        runBlocking { slack.delete(context, CHANNEL, "1512085950.000216") }
        assertEquals("1512085950.000216", body(stub.requests.single()).path("ts").asString())
        stub.onJson(HttpMethod.POST, "/chat.delete", error("cant_delete_message"))
        assertThrows<ConnectorException.PermissionDenied> { runBlocking { slack.delete(context, CHANNEL, "1512085950.000216") } }
        stub.onJson(HttpMethod.POST, "/chat.delete", error("message_not_found"))
        assertThrows<ConnectorException.NotFound> { runBlocking { slack.delete(context, CHANNEL, "1512085950.000216") } }
    }

    @Test
    fun `ok false codes and http statuses map to the taxonomy`() {
        fun failWith(
            body: String,
            status: Int = 200,
            headers: Map<String, String> = emptyMap()
        ): ConnectorException {
            stub.on(HttpMethod.POST, "/chat.postMessage") { _, _ -> StubResponse.json(body, status, headers) }
            return assertThrows { runBlocking { slack.create(context, CHANNEL, mapOf("text" to FieldValue.Text("x"))) } }
        }
        listOf("invalid_auth", "not_authed", "token_revoked", "token_expired", "account_inactive").forEach {
            assertInstanceOf(ConnectorException.AuthExpired::class.java, failWith(error(it)), it)
        }
        listOf("not_in_channel", "missing_scope", "cant_update_message", "is_archived", "restricted_action").forEach {
            assertInstanceOf(ConnectorException.PermissionDenied::class.java, failWith(error(it)), it)
        }
        listOf("channel_not_found", "message_not_found").forEach { assertInstanceOf(ConnectorException.NotFound::class.java, failWith(error(it)), it) }
        assertInstanceOf(ConnectorException.Transient::class.java, failWith(error("internal_error")))
        val limited = failWith(error("ratelimited"), 429, mapOf("Retry-After" to "30")) as ConnectorException.RateLimited
        assertEquals(Duration.ofSeconds(30), limited.retryAfter)
        val bare = failWith(error("ratelimited")) as ConnectorException.RateLimited
        assertEquals(Duration.ofMinutes(1), bare.retryAfter)
        assertInstanceOf(ConnectorException.Transient::class.java, failWith("<html>bad gateway</html>", 502))
        assertInstanceOf(ConnectorException.Transient::class.java, failWith("<html>ok?</html>"))
        val invalid =
            failWith(error("msg_too_long", ""","warning":"missing_charset","response_metadata":{"messages":["[WARN] missing charset"]}""")) as
                ConnectorException.Permanent
        assertEquals("msg_too_long", invalid.details["error"])
        assertEquals("missing_charset", invalid.details["warning"])
        assertEquals("[WARN] missing charset", invalid.details["messages"])
        val scope = failWith(error("missing_scope", ""","needed":"chat:write","provided":"channels:read"""")) as ConnectorException.PermissionDenied
        assertFalse(scope.message!!.contains("channels:read"), "provider text stays out of the message")
    }
}
