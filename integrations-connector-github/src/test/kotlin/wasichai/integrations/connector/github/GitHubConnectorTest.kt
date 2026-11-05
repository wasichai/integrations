package wasichai.integrations.connector.github

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
import wasichai.integrations.connector.github.GitHubFixtures.ISSUES
import wasichai.integrations.connector.github.GitHubFixtures.REPO
import wasichai.integrations.connector.github.GitHubFixtures.array
import wasichai.integrations.connector.github.GitHubFixtures.githubBasics
import wasichai.integrations.connector.github.GitHubFixtures.issue
import wasichai.integrations.connector.github.GitHubFixtures.repository
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import wasichai.integrations.sdk.testing.TestConnectorContext
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitHubConnectorTest {
    private val stub = StubProviderServer()
    private val github by lazy { GitHubConnector(GitHubConfig(webUrl = stub.baseUrl, apiUrl = stub.baseUrl)) }
    private val json = JsonMapper.builder().build()
    private val now = Instant.parse("2026-11-05T10:00:00Z")
    private val context = TestConnectorContext(clock = Clock.fixed(now, ZoneOffset.UTC))

    @BeforeEach
    fun reset() {
        stub.reset()
        stub.githubBasics()
    }

    @AfterAll
    fun stop() = stub.close()

    private fun body(call: StubProviderServer.Recorded) = json.readTree(call.body)

    private fun link(
        path: String,
        rel: String = "next"
    ) = mapOf("Link" to "<${stub.baseUrl}$path>; rel=\"$rel\", <${stub.baseUrl}$path&last=1>; rel=\"last\"")

    @Test
    fun `oauth is github's web flow with pkce and the repo scope, and personal tokens are bearer api keys`() {
        val oauth =
            github.manifest.authMethods
                .filterIsInstance<AuthMethod.OAuth2AuthorizationCode>()
                .single()
        assertEquals("${stub.baseUrl}/login/oauth/authorize", oauth.authorizeUrl)
        assertEquals("${stub.baseUrl}/login/oauth/access_token", oauth.tokenUrl)
        assertEquals(listOf("repo"), oauth.defaultScopes)
        assertTrue(oauth.pkce)
        assertNull(oauth.revokeUrl, "github's revocation is not rfc 7009")
        assertTrue(AuthMethod.ApiKey("Authorization", "Bearer ") in github.manifest.authMethods)
        // github enterprise server: the web flow on the host, the api under /api/v3
        val ghes = GitHubConnector(GitHubConfig(webUrl = "https://ghe.example.com", apiUrl = "https://ghe.example.com/api/v3"))
        val ghesOauth =
            ghes.manifest.authMethods
                .filterIsInstance<AuthMethod.OAuth2AuthorizationCode>()
                .single()
        assertEquals("https://ghe.example.com/login/oauth/access_token", ghesOauth.tokenUrl)
    }

    @Test
    fun `the account is the user id, and every call names the api version and a user agent`() {
        val account = runBlocking { github.accountInfo(context) }
        assertEquals("1", account.providerAccountId)
        assertEquals("octocat", account.displayName)
        val call = stub.requests.single()
        assertEquals("application/vnd.github+json", call.header("Accept"))
        assertEquals("2022-11-28", call.header("X-GitHub-Api-Version"))
        assertEquals("wasichai-integrations", call.header("User-Agent"))
    }

    @Test
    fun `repositories are the resources, paged by Link, those without issues left out`() {
        stub.on(HttpMethod.GET, "/user/repos") { call, _ ->
            if (call.query["page"] == "2") {
                StubResponse.json(array(listOf(repository("octo-org/wiki-only", hasIssues = false), repository("octo-org/api"))))
            } else {
                StubResponse.json(array(listOf(repository())), headers = link("/user/repos?per_page=100&sort=full_name&page=2"))
            }
        }
        val found = runBlocking { github.listResources(context) }
        assertEquals(listOf(REPO, "octo-org/api"), found.map { it.name })
        assertEquals("100", stub.requests.first().query["per_page"])
    }

    @Test
    fun `a next link outside the configured api is never followed`() {
        stub.on(HttpMethod.GET, "/user/repos") { _, _ ->
            StubResponse.json(array(listOf(repository())), headers = mapOf("Link" to "<https://evil.example/user/repos?page=2>; rel=\"next\""))
        }
        assertThrows<ConnectorException.Permanent> { runBlocking { github.listResources(context) } }
        assertEquals(1, stub.requests.size)
    }

    @Test
    fun `describe gives the issue fields, and a repository without issues or out of reach says so`() {
        val schema = runBlocking { github.describe(context, REPO) }
        assertEquals(REPO, schema.resource)
        assertTrue(schema.field("title")!!.required)
        assertEquals(FieldType.TEXT, schema.field("body")!!.type)
        assertEquals(listOf("open", "closed"), schema.field("state")!!.options)
        assertEquals(FieldType.MULTI_CHOICE, schema.field("labels")!!.type)
        assertEquals(FieldType.MULTI_CHOICE, schema.field("assignees")!!.type)
        assertEquals(FieldType.REFERENCE, schema.field("milestone")!!.type)
        assertTrue(schema.field("number")!!.readOnly)
        assertTrue(schema.field("updated_at")!!.readOnly)
        stub.onJson(HttpMethod.GET, "/repos/$REPO", repository(hasIssues = false))
        assertThrows<ConnectorException.Permanent> { runBlocking { github.describe(context, REPO) } }
        stub.onJson(HttpMethod.GET, "/repos/$REPO", """{"message":"Not Found","documentation_url":"https://docs.github.com/rest"}""", 404)
        assertThrows<ConnectorException.NotFound> { runBlocking { github.describe(context, REPO) } }
    }

    @Test
    fun `reads an issue as typed fields, versioned by updated_at`() {
        stub.onJson(HttpMethod.GET, "$ISSUES/1347", issue())
        val record = runBlocking { github.read(context, REPO, "1347") }!!
        assertEquals("1347", record.id)
        assertEquals("2026-11-04T12:00:00Z", record.version)
        assertEquals(Instant.parse("2026-11-04T12:00:00Z"), record.modifiedAt)
        assertEquals(FieldValue.Number(BigDecimal(1347)), record.fields["number"])
        assertEquals(FieldValue.Text("Found a bug"), record.fields["title"])
        assertEquals(FieldValue.Text("I'm having a problem with this."), record.fields["body"])
        assertEquals(FieldValue.Choice("open"), record.fields["state"])
        assertEquals(FieldValue.Null, record.fields["state_reason"])
        assertEquals(FieldValue.MultiChoice(listOf("bug")), record.fields["labels"])
        assertEquals(FieldValue.MultiChoice(listOf("octocat")), record.fields["assignees"])
        assertEquals(FieldValue.Reference("milestone", "1"), record.fields["milestone"])
        assertEquals(FieldValue.Reference("user", "octocat"), record.fields["user"])
        assertEquals(FieldValue.Bool(true), record.fields["locked"])
        assertEquals(FieldValue.Null, record.fields["closed_at"])
        assertEquals(FieldValue.DateTime(Instant.parse("2026-11-01T09:00:00Z")), record.fields["created_at"])
    }

    @Test
    fun `pull requests, deleted and transferred issues read as absent, lost access does not`() {
        stub.onJson(HttpMethod.GET, "$ISSUES/1347", issue(pullRequest = true))
        assertNull(runBlocking { github.read(context, REPO, "1347") })
        stub.onJson(HttpMethod.GET, "$ISSUES/1347", """{"message":"This issue was deleted"}""", 410)
        assertNull(runBlocking { github.read(context, REPO, "1347") })
        stub.on(HttpMethod.GET, "$ISSUES/1347") { _, _ ->
            StubResponse(301, headers = mapOf("Location" to "${stub.baseUrl}/repositories/42/issues/7"))
        }
        assertNull(runBlocking { github.read(context, REPO, "1347") })
        stub.onJson(HttpMethod.GET, "$ISSUES/1347", """{"message":"Not Found"}""", 404)
        assertNull(runBlocking { github.read(context, REPO, "1347") })
        // the repository itself is gone or no longer reachable: an error, not a deletion of every issue
        stub.onJson(HttpMethod.GET, "/repos/$REPO", """{"message":"Not Found"}""", 404)
        assertThrows<ConnectorException.NotFound> { runBlocking { github.read(context, REPO, "1347") } }
        assertTrue(stub.requests.none { it.path.startsWith("/repositories/") }, "a redirect is not followed")
    }

    @Test
    fun `names and numbers that are not github's never reach a url`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { github.read(context, REPO, "../../user") } }
        assertThrows<ConnectorException.Permanent> { runBlocking { github.read(context, "octocat/..", "1") } }
        assertThrows<ConnectorException.Permanent> { runBlocking { github.list(context, ReadRequest("octocat/x/../../user")) } }
        assertThrows<ConnectorException.Permanent> { runBlocking { github.describe(context, "no-slash") } }
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `list reads every state oldest first, leaves pull requests out and follows Link as the cursor`() {
        // github's next links name the repository by id
        stub.on(HttpMethod.GET, ISSUES) { _, _ ->
            StubResponse.json(array(listOf(issue(1), issue(2, pullRequest = true))), headers = link("/repositories/1296269/issues?per_page=50&page=2"))
        }
        stub.onJson(HttpMethod.GET, "/repositories/1296269/issues", array(listOf(issue(3, state = "closed"))))
        val first = runBlocking { github.list(context, ReadRequest(REPO, pageSize = 50)) }
        assertEquals(listOf("1"), first.records.map { it.id })
        assertEquals("${stub.baseUrl}/repositories/1296269/issues?per_page=50&page=2", first.nextCursor)
        val query = stub.requests.single().query
        assertEquals(mapOf("state" to "all", "sort" to "created", "direction" to "asc", "per_page" to "50"), query)
        val second = runBlocking { github.list(context, ReadRequest(REPO, cursor = first.nextCursor)) }
        assertEquals(listOf("3"), second.records.map { it.id })
        assertNull(second.nextCursor)
        assertEquals("2", stub.requests.last().query["page"])
        // a cursor is followed only on the configured api: the token never leaves it
        assertThrows<ConnectorException.Permanent> { runBlocking { github.list(context, ReadRequest(REPO, cursor = "https://evil.example/x")) } }
        assertThrows<ConnectorException.Permanent> {
            runBlocking { github.list(context, ReadRequest(REPO, cursor = "http://user@127.0.0.1:1/repos")) }
        }
        assertEquals(2, stub.requests.size)
    }

    @Test
    fun `change feed queries afresh from a second before the newest update, at least once`() {
        val queries = mutableListOf<Map<String, String>>()
        stub.on(HttpMethod.GET, ISSUES) { call, _ ->
            queries += call.query
            when (queries.size) {
                1 ->
                    StubResponse.json(
                        array(listOf(issue(1, updated = "2026-11-04T10:00:00Z"), issue(2, updated = "2026-11-04T11:00:00Z", pullRequest = true))),
                        headers = link("$ISSUES?page=2")
                    )
                2 -> StubResponse.json(array(listOf(issue(3, updated = "2026-11-04T12:30:15Z"))))
                else -> StubResponse.json(array(listOf(issue(3, updated = "2026-11-04T12:30:15Z"))))
            }
        }
        val first = runBlocking { github.changes(context, REPO, null, 2) }
        assertTrue(first.hasMore)
        assertEquals(listOf("1"), first.changes.map { it.id }, "the pull request counts for progress, it is not a change")
        assertEquals(mapOf("state" to "all", "sort" to "updated", "direction" to "asc", "per_page" to "2"), queries[0])

        // the next page is a fresh query from the newest seen (the pull request's) minus a second, not page 2
        val second = runBlocking { github.changes(context, REPO, first.checkpoint, 2) }
        assertEquals("2026-11-04T10:59:59Z", queries[1]["since"])
        assertNull(queries[1]["page"])
        assertFalse(second.hasMore)
        assertEquals("2026-11-04T12:30:14Z", json.readTree(second.checkpoint).path("since").asString())

        val third = runBlocking { github.changes(context, REPO, second.checkpoint, 2) }
        assertEquals("2026-11-04T12:30:14Z", queries[2]["since"])
        assertEquals(listOf("3"), third.changes.map { it.id }, "the boundary issue comes again: at least once")
        assertEquals(second.checkpoint, third.checkpoint)
    }

    @Test
    fun `a full page within one second pages by number until since can move`() {
        val queries = mutableListOf<Map<String, String>>()
        stub.on(HttpMethod.GET, ISSUES) { call, _ ->
            queries += call.query
            when (call.query["page"]) {
                null -> StubResponse.json(array(listOf(issue(1), issue(2))), headers = link("$ISSUES?page=2"))
                "2" -> StubResponse.json(array(listOf(issue(3), issue(4))), headers = link("$ISSUES?page=3"))
                else -> StubResponse.json(array(listOf(issue(5, updated = "2026-11-04T12:00:09Z"))))
            }
        }
        val start = GitHubCheckpoint(Instant.parse("2026-11-04T11:59:59Z")).render()
        val first = runBlocking { github.changes(context, REPO, start, 2) }
        assertTrue(first.hasMore)
        val second = runBlocking { github.changes(context, REPO, first.checkpoint, 2) }
        assertEquals("2", queries[1]["page"])
        assertEquals("2026-11-04T11:59:59Z", queries[1]["since"])
        val third = runBlocking { github.changes(context, REPO, second.checkpoint, 2) }
        assertEquals("3", queries[2]["page"])
        assertFalse(third.hasMore)
        assertEquals(GitHubCheckpoint(Instant.parse("2026-11-04T12:00:08Z")).render(), third.checkpoint)
    }

    @Test
    fun `an unreadable checkpoint is refused`() {
        assertThrows<ConnectorException.Permanent> { runBlocking { github.changes(context, REPO, "not json", 10) } }
        assertThrows<ConnectorException.Permanent> { runBlocking { github.changes(context, REPO, """{"page":0}""", 10) } }
    }

    @Test
    fun `create posts the issue, then sets its state by an edit`() {
        stub.onJson(HttpMethod.POST, ISSUES, issue(1348, title = "New", updated = "2026-11-05T09:00:00Z"), 201)
        stub.onJson(HttpMethod.PATCH, "$ISSUES/1348", issue(1348, title = "New", updated = "2026-11-05T09:00:01Z", state = "closed"))
        val result =
            runBlocking {
                github.create(
                    context,
                    REPO,
                    mapOf(
                        "title" to FieldValue.Text("New"),
                        "body" to FieldValue.Text("**markdown** stays"),
                        "labels" to FieldValue.MultiChoice(listOf("bug", "ui")),
                        "assignees" to FieldValue.MultiChoice(listOf("octocat")),
                        "milestone" to FieldValue.Reference("milestone", "1"),
                        "state" to FieldValue.Choice("closed"),
                        "state_reason" to FieldValue.Choice("not_planned")
                    )
                )
            }
        assertTrue(result.created)
        assertEquals("1348", result.id)
        assertEquals("2026-11-05T09:00:01Z", result.version)
        val posted = body(stub.requests.single { it.method == HttpMethod.POST })
        assertEquals("New", posted.path("title").asString())
        assertEquals("**markdown** stays", posted.path("body").asString())
        assertEquals("ui", posted.path("labels")[1].asString())
        assertEquals("octocat", posted.path("assignees")[0].asString())
        assertEquals(1, posted.path("milestone").asInt())
        assertFalse(posted.has("state"), "state is not a create parameter")
        val patched = body(stub.requests.single { it.method == HttpMethod.PATCH })
        assertEquals("closed", patched.path("state").asString())
        assertEquals("not_planned", patched.path("state_reason").asString())
        assertEquals(setOf("state", "state_reason"), patched.properties().map { it.key }.toSet())
    }

    @Test
    fun `a create without state is one request`() {
        stub.onJson(HttpMethod.POST, ISSUES, issue(1348), 201)
        val result = runBlocking { github.create(context, REPO, mapOf("title" to FieldValue.Text("x"))) }
        assertEquals("2026-11-04T12:00:00Z", result.version)
        assertEquals(1, stub.requests.size)
    }

    @Test
    fun `a created issue whose state edit fails is reported with its number, never hidden`() {
        stub.onJson(HttpMethod.POST, ISSUES, issue(1348), 201)
        stub.onJson(HttpMethod.PATCH, "$ISSUES/1348", """{"message":"Server Error"}""", 502)
        val error =
            assertThrows<ConnectorException.Permanent> {
                runBlocking { github.create(context, REPO, mapOf("title" to FieldValue.Text("x"), "state" to FieldValue.Choice("closed"))) }
            }
        assertEquals("1348", error.details["createdId"])
    }

    @Test
    fun `read-only, unknown or invalid fields are refused before any write`() {
        fun refused(fields: Map<String, FieldValue>) = assertThrows<ConnectorException.Permanent> { runBlocking { github.create(context, REPO, fields) } }
        refused(mapOf("updated_at" to FieldValue.Text("x")))
        refused(mapOf("priority" to FieldValue.Text("x")))
        refused(mapOf("title" to FieldValue.Null))
        refused(mapOf("title" to FieldValue.Text("x"), "state" to FieldValue.Choice("archived")))
        refused(mapOf("title" to FieldValue.Text("x"), "milestone" to FieldValue.Reference("milestone", "v1")))
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `update with a stale expected version is a conflict and writes nothing`() {
        stub.onJson(HttpMethod.GET, "$ISSUES/1347", issue(updated = "2026-11-04T13:00:00Z"))
        val error =
            assertThrows<ConnectorException.Conflict> {
                runBlocking { github.update(context, REPO, "1347", mapOf("title" to FieldValue.Text("B")), "2026-11-04T12:00:00Z") }
            }
        assertEquals("2026-11-04T13:00:00Z", error.currentVersion)
        assertTrue(stub.requests.none { it.method == HttpMethod.PATCH })
    }

    @Test
    fun `update with the current version patches only the given fields`() {
        stub.onJson(HttpMethod.GET, "$ISSUES/1347", issue())
        stub.onJson(HttpMethod.PATCH, "$ISSUES/1347", issue(updated = "2026-11-04T12:05:00Z"))
        val result =
            runBlocking {
                github.update(
                    context,
                    REPO,
                    "1347",
                    mapOf("labels" to FieldValue.Null, "milestone" to FieldValue.Null, "body" to FieldValue.Null),
                    "2026-11-04T12:00:00Z"
                )
            }
        assertFalse(result.created)
        assertEquals("2026-11-04T12:05:00Z", result.version)
        val patched = body(stub.requests.single { it.method == HttpMethod.PATCH })
        assertEquals(0, patched.path("labels").size(), "no labels is an empty list: github replaces them")
        assertTrue(patched.path("milestone").isNull)
        assertTrue(patched.path("body").isNull)
        assertEquals(3, patched.size())
    }

    @Test
    fun `delete is refused plainly, the rest api cannot delete an issue`() {
        val error = assertThrows<ConnectorException.Permanent> { runBlocking { github.delete(context, REPO, "1347") } }
        assertTrue(error.message!!.contains("close it"))
        assertTrue(stub.requests.isEmpty())
    }

    @Test
    fun `http statuses and rate limit headers map to the taxonomy`() {
        fun failWith(
            status: Int,
            body: String = """{"message":"nope"}""",
            headers: Map<String, String> = emptyMap()
        ): ConnectorException {
            stub.on(HttpMethod.GET, "$ISSUES/1347") { _, _ -> StubResponse.json(body, status, headers) }
            return assertThrows { runBlocking { github.read(context, REPO, "1347") } }
        }
        assertInstanceOf(ConnectorException.AuthExpired::class.java, failWith(401, """{"message":"Bad credentials"}"""))
        assertInstanceOf(ConnectorException.PermissionDenied::class.java, failWith(403, """{"message":"Resource not accessible by integration"}"""))
        // primary limit: remaining 0, wait until the reset epoch
        val primary =
            failWith(
                403,
                """{"message":"API rate limit exceeded for user ID 1."}""",
                mapOf("x-ratelimit-limit" to "5000", "x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${now.epochSecond + 120}")
            ) as ConnectorException.RateLimited
        assertEquals(Duration.ofSeconds(120), primary.retryAfter)
        // secondary limit with retry-after
        val secondary =
            failWith(403, """{"message":"You have exceeded a secondary rate limit."}""", mapOf("retry-after" to "30")) as ConnectorException.RateLimited
        assertEquals(Duration.ofSeconds(30), secondary.retryAfter)
        // secondary limit naming no wait: at least a minute
        val bare = failWith(403, """{"message":"You have exceeded a secondary rate limit. Please wait a few minutes."}""") as ConnectorException.RateLimited
        assertEquals(Duration.ofMinutes(1), bare.retryAfter)
        val tooMany = failWith(429, """{"message":"x"}""", mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to "${now.epochSecond - 5}"))
        assertEquals(Duration.ZERO, (tooMany as ConnectorException.RateLimited).retryAfter)
        assertInstanceOf(ConnectorException.Conflict::class.java, failWith(409))
        assertInstanceOf(ConnectorException.Transient::class.java, failWith(503))
        val invalid =
            failWith(
                422,
                """{"message":"Validation Failed","errors":[{"resource":"Issue","field":"title","code":"missing_field"}],
                   |"documentation_url":"https://docs.github.com/rest"}
                """.trimMargin()
            ) as ConnectorException.Permanent
        assertEquals("Validation Failed", invalid.details["message"])
        assertEquals("missing_field", invalid.details["field.title"])
        assertFalse(invalid.message!!.contains("Validation"), "provider text stays out of the message")
        // a 404 on a list is not "absent": it is an error
        stub.onJson(HttpMethod.GET, ISSUES, """{"message":"Not Found"}""", 404)
        assertThrows<ConnectorException.NotFound> { runBlocking { github.list(context, ReadRequest(REPO)) } }
    }
}
