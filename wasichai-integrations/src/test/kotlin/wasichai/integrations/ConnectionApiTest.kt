package wasichai.integrations

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import wasichai.integrations.sdk.HttpMethod as StubMethod

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConnectionApiTest : IntegrationsIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    @Autowired
    private lateinit var held: HeldConnections

    @Autowired
    private lateinit var connections: ConnectionRepository

    @Autowired
    private lateinit var contexts: ConnectorContexts

    private val stub = StubProvider.server
    private val tenant by lazy { TestTenant.provision(client).also { configure(it) } }
    private val other by lazy { TestTenant.provision(client) }
    private val tokenCalls = AtomicInteger()

    private fun configure(t: TestTenant) {
        call(
            client,
            HttpMethod.PUT,
            "/api/integrations/provider-configs/stub-oauth",
            t.admin,
            mapOf("clientId" to "client-1", "clientSecret" to "client-secret-1", "scopes" to listOf("write")),
            200
        )
    }

    @BeforeEach
    fun provider() {
        stub.reset()
        tokenCalls.set(0)
        stub.on(StubMethod.POST, "/token") { call, _ ->
            val n = tokenCalls.incrementAndGet()
            val form = form(call.bodyText())
            when (form["grant_type"]) {
                "authorization_code" ->
                    StubResponse.json(
                        """{"access_token":"at-$n","refresh_token":"rt-1","expires_in":3600,"scope":"read write","instance_url":"https://acme.example"}"""
                    )
                "refresh_token" -> StubResponse.json("""{"access_token":"at-refreshed-$n","expires_in":3600}""")
                else -> StubResponse.json("""{"error":"unsupported_grant_type"}""", 400)
            }
        }
        stub.on(StubMethod.GET, "/me") { call, _ ->
            val auth = call.header("Authorization") ?: call.header("X-Api-Key")?.let { "key $it" }
            if (auth == null || auth.contains("expired")) {
                StubResponse.json("""{"error":"unauthorized"}""", 401)
            } else {
                StubResponse.json("""{"account":"acct-42","name":"Acme"}""")
            }
        }
        stub.on(StubMethod.POST, "/revoke") { _, _ -> StubResponse(200) }
    }

    private fun form(body: String): Map<String, String> =
        body.split("&").filter { it.contains("=") }.associate {
            URLDecoder.decode(it.substringBefore("="), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8)
        }

    private fun query(uri: URI): Map<String, String> = form(uri.rawQuery ?: "")

    private fun callback(query: String): URI {
        val result =
            client
                .get()
                .uri("/api/integrations/oauth/callback?$query")
                .exchange()
                .expectStatus()
                .isFound
                .returnResult(String::class.java)
        return result.responseHeaders.location!!
    }

    private fun connect(t: TestTenant = tenant): Pair<UUID, Map<String, String>> {
        val start =
            call(client, HttpMethod.POST, "/api/integrations/connections/oauth/start", t.admin, mapOf("connectorId" to "stub-oauth", "name" to "Stub CRM"), 200)
        val authorize = URI.create(start.get("authorizationUrl").asString())
        val params = query(authorize)
        val back = callback("code=the-code&state=${params.getValue("state")}")
        val result = query(back)
        assertEquals("connected", result["status"], back.toString())
        return UUID.fromString(result.getValue("connectionId")) to params
    }

    private fun sealedCredentials(id: UUID): String =
        runBlocking {
            db
                .sql("SELECT sealed FROM ${schemas.metadata}.integration_connection_credentials WHERE connection_id = :id")
                .bind("id", id)
                .map { row, _ -> row.get("sealed", String::class.java)!! }
                .one()
                .awaitSingle()
        }

    @Test
    fun `oauth with pkce connects an account and keeps its tokens sealed`() {
        val (id, params) = connect()
        assertEquals("client-1", params["client_id"])
        assertEquals("https://app.example.test/api/integrations/oauth/callback", params["redirect_uri"])
        assertEquals("read write", params["scope"])
        assertEquals("S256", params["code_challenge_method"])
        assertEquals("consent", params["prompt"])

        // the code exchange sent the verifier matching the challenge, and the client secret in the body
        val exchange = form(stub.requests.first { it.path == "/token" }.bodyText())
        assertEquals("the-code", exchange["code"])
        assertEquals(params["code_challenge"], ConnectionService.challenge(exchange.getValue("code_verifier")))
        assertEquals("client-secret-1", exchange["client_secret"])

        val connection = call(client, HttpMethod.GET, "/api/integrations/connections/$id", tenant.member(), null, 200)
        assertEquals("ACTIVE", connection.get("status").asString())
        assertEquals("acct-42", connection.get("providerAccountId").asString())
        assertEquals("https://acme.example", connection.get("metadata").get("instance_url").asString())
        assertFalse(connection.toString().contains("at-1"))
        assertFalse(connection.toString().contains("rt-1"))
        val sealed = sealedCredentials(id)
        assertTrue(sealed.startsWith("v1."))
        assertFalse(sealed.contains("at-1") || sealed.contains("rt-1"))
    }

    @Test
    fun `a state is single use, and unknown or missing states are refused`() {
        val start = call(client, HttpMethod.POST, "/api/integrations/connections/oauth/start", tenant.admin, mapOf("connectorId" to "stub-oauth"), 200)
        val state = query(URI.create(start.get("authorizationUrl").asString())).getValue("state")
        assertEquals("connected", query(callback("code=c&state=$state"))["status"])
        assertEquals("invalid_state", query(callback("code=c&state=$state"))["error"])
        assertEquals("invalid_state", query(callback("code=c&state=made-up-state-value-123"))["error"])
        assertEquals("invalid_state", query(callback("code=c"))["error"])
        assertTrue(callback("code=c").toString().startsWith("https://ui.example.test/integrations/connections?"))
    }

    @Test
    fun `an expired state is refused`() {
        val start = call(client, HttpMethod.POST, "/api/integrations/connections/oauth/start", tenant.admin, mapOf("connectorId" to "stub-oauth"), 200)
        val state = query(URI.create(start.get("authorizationUrl").asString())).getValue("state")
        runBlocking {
            db
                .sql("UPDATE ${schemas.metadata}.integration_oauth_states SET expires_at = now() - interval '1 minute' WHERE state_hash = :h")
                .bind("h", OAuthStateRepository.hash(state))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        assertEquals("invalid_state", query(callback("code=c&state=$state"))["error"])
    }

    @Test
    fun `a denied authorization comes back as access_denied`() {
        val start = call(client, HttpMethod.POST, "/api/integrations/connections/oauth/start", tenant.admin, mapOf("connectorId" to "stub-oauth"), 200)
        val state = query(URI.create(start.get("authorizationUrl").asString())).getValue("state")
        assertEquals("access_denied", query(callback("error=access_denied&state=$state"))["error"])
    }

    @Test
    fun `connecting the same account again keeps one connection`() {
        val (first, _) = connect()
        val (second, _) = connect()
        assertEquals(first, second)
    }

    @Test
    fun `an expiring token is refreshed before the call, and the provider sees the new one`() {
        val (id, _) = connect()
        runBlocking {
            db
                .sql(
                    "UPDATE ${schemas.metadata}.integration_connection_credentials " +
                        "SET access_expires_at = now() - interval '1 minute' WHERE connection_id = :id"
                ).bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val tested = call(client, HttpMethod.POST, "/api/integrations/connections/$id/test", tenant.admin, null, 200)
        assertTrue(tested.get("ok").asBoolean(), tested.toString())
        val refresh =
            stub.requests
                .filter { it.path == "/token" }
                .map { form(it.bodyText()) }
                .last()
        assertEquals("refresh_token", refresh["grant_type"])
        assertEquals("rt-1", refresh["refresh_token"])
        assertTrue(
            stub.requests
                .last { it.path == "/me" }
                .header("Authorization")!!
                .startsWith("Bearer at-refreshed-")
        )
    }

    @Test
    fun `credential headers lent to another transport are the stored token, and a refresh renews it once`() {
        val (id, _) = connect()
        runBlocking {
            val http = contexts.forConnection(connections.find(tenant.organizationId, id)!!).http
            assertEquals(mapOf("Authorization" to "Bearer at-1"), http.credentialHeaders())
            val calls = tokenCalls.get()
            val fresh = http.credentialHeaders(refresh = true).getValue("Authorization")
            assertTrue(fresh.startsWith("Bearer at-refreshed-"), fresh)
            assertEquals(calls + 1, tokenCalls.get())
            assertEquals(fresh, http.credentialHeaders().getValue("Authorization"))
        }
    }

    @Test
    fun `a refused refresh puts the connection in NEEDS_REAUTH`() {
        val (id, _) = connect()
        stub.on(StubMethod.POST, "/token") { _, _ -> StubResponse.json("""{"error":"invalid_grant"}""", 400) }
        stub.on(StubMethod.GET, "/me") { _, _ -> StubResponse.json("""{"error":"unauthorized"}""", 401) }
        val tested = call(client, HttpMethod.POST, "/api/integrations/connections/$id/test", tenant.admin, null, 200)
        assertFalse(tested.get("ok").asBoolean())
        assertEquals("NEEDS_REAUTH", tested.get("connection").get("status").asString())
        assertEquals("FAILING", tested.get("connection").get("health").asString())
    }

    @Test
    fun `a refresh refused inside a 200 answer puts the connection in NEEDS_REAUTH`() {
        val (id, _) = connect()
        stub.on(StubMethod.POST, "/token") { _, _ -> StubResponse.json("""{"ok":false,"error":"invalid_refresh_token"}""") }
        stub.on(StubMethod.GET, "/me") { _, _ -> StubResponse.json("""{"error":"unauthorized"}""", 401) }
        val tested = call(client, HttpMethod.POST, "/api/integrations/connections/$id/test", tenant.admin, null, 200)
        assertFalse(tested.get("ok").asBoolean())
        assertFalse(tested.get("retryable").asBoolean())
        assertEquals("NEEDS_REAUTH", tested.get("connection").get("status").asString())
        assertEquals("FAILING", tested.get("connection").get("health").asString())
        assertTrue(tested.get("error").asString().startsWith("AuthExpired"), tested.toString())
    }

    @Test
    fun `a code refused inside a 200 answer comes back as token_refused, and no connection is made`() {
        stub.on(StubMethod.POST, "/token") { _, _ -> StubResponse.json("""{"ok":false,"error":"invalid_code"}""") }
        val start = call(client, HttpMethod.POST, "/api/integrations/connections/oauth/start", tenant.admin, mapOf("connectorId" to "stub-oauth"), 200)
        val state = query(URI.create(start.get("authorizationUrl").asString())).getValue("state")
        val back = query(callback("code=c&state=$state"))
        assertEquals("error", back["status"])
        assertEquals("token_refused", back["error"])
        assertTrue(stub.requests.none { it.path == "/me" })
    }

    @Test
    fun `credentials no configured key opens put the connection in NEEDS_REAUTH instead of failing`() {
        val (id, _) = connect()
        // as if sealed with a key that was removed since: the format is intact, the key is not configured
        val parts = sealedCredentials(id).split(".")
        runBlocking {
            db
                .sql("UPDATE ${schemas.metadata}.integration_connection_credentials SET sealed = :sealed WHERE connection_id = :id")
                .bind("sealed", listOf(parts[0], "removed-key", parts[2], parts[3]).joinToString("."))
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val tested = call(client, HttpMethod.POST, "/api/integrations/connections/$id/test", tenant.admin, null, 200)
        assertFalse(tested.get("ok").asBoolean(), tested.toString())
        assertFalse(tested.get("retryable").asBoolean())
        val connection = tested.get("connection")
        assertEquals("NEEDS_REAUTH", connection.get("status").asString())
        assertEquals("FAILING", connection.get("health").asString())
        assertTrue(connection.get("lastError").asString().contains("cannot be decrypted with the configured keys"), connection.toString())
        assertFalse(tested.toString().contains("removed-key"), "the key id stays in the log")
    }

    @Test
    fun `a connector that cannot check its account says so, and a test only reads the credentials back`() {
        val body = mapOf("connectorId" to "test-unchecked", "authType" to "API_KEY", "apiKey" to "unchecked-key", "name" to "Unchecked")
        val created = call(client, HttpMethod.POST, "/api/integrations/connections", tenant.admin, body, 201)
        assertFalse(created.get("accountChecked").asBoolean(), created.toString())
        assertTrue(created.path("providerAccountId").isMissingNode || created.path("providerAccountId").isNull)
        // nothing identifies the account: the same key connected again is a second connection
        val again = call(client, HttpMethod.POST, "/api/integrations/connections", tenant.admin, body, 201)
        assertTrue(created.get("id").asString() != again.get("id").asString())
        val id = created.get("id").asString()
        val tested = call(client, HttpMethod.POST, "/api/integrations/connections/$id/test", tenant.admin, null, 200)
        assertTrue(tested.get("ok").asBoolean(), tested.toString())
        assertFalse(tested.get("accountChecked").asBoolean())
        assertFalse(tested.get("connection").get("accountChecked").asBoolean())

        val (checked, _) = connect()
        val viaProvider = call(client, HttpMethod.POST, "/api/integrations/connections/$checked/test", tenant.admin, null, 200)
        assertTrue(viaProvider.get("ok").asBoolean(), viaProvider.toString())
        assertTrue(viaProvider.get("accountChecked").asBoolean())
        assertTrue(call(client, HttpMethod.GET, "/api/integrations/connections/$checked", tenant.admin, null, 200).get("accountChecked").asBoolean())
    }

    @Test
    fun `api key connections are checked against the provider before they are stored`() {
        val created =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/connections",
                tenant.admin,
                mapOf("connectorId" to "stub-key", "authType" to "API_KEY", "apiKey" to "key-123", "name" to "Key"),
                201
            )
        assertEquals("acct-42", created.get("providerAccountId").asString())
        assertEquals("key-123", stub.requests.last { it.path == "/me" }.header("X-Api-Key"))
        assertFalse(created.toString().contains("key-123"))
        val refused =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/connections",
                tenant.admin,
                mapOf("connectorId" to "stub-key", "authType" to "API_KEY", "apiKey" to "expired-key"),
                400
            )
        assertEquals(
            "credentials",
            refused
                .get("errors")
                .get(0)
                .get("field")
                .asString()
        )
        call(client, HttpMethod.POST, "/api/integrations/connections", tenant.admin, mapOf("connectorId" to "stub-key", "authType" to "BASIC"), 400)
    }

    @Test
    fun `other tenants see nothing and members cannot connect or disconnect`() {
        val (id, _) = connect()
        val theirs = call(client, HttpMethod.GET, "/api/integrations/connections", other.admin, null, 200)
        assertTrue((0 until theirs.size()).none { theirs.get(it).get("id").asString() == id.toString() })
        call(client, HttpMethod.GET, "/api/integrations/connections/$id", other.admin, null, 404)
        call(client, HttpMethod.POST, "/api/integrations/connections/$id/test", other.admin, null, 404)
        call(client, HttpMethod.DELETE, "/api/integrations/connections/$id", other.admin, null, 404)
        val member = tenant.member()
        call(client, HttpMethod.POST, "/api/integrations/connections/oauth/start", member, mapOf("connectorId" to "stub-oauth"), 403)
        call(client, HttpMethod.DELETE, "/api/integrations/connections/$id", member, null, 403)
        // a test calls the provider and changes status and health: a manager's action
        call(client, HttpMethod.POST, "/api/integrations/connections/$id/test", member, null, 403)
        // oauth needs the tenant's own provider app
        call(client, HttpMethod.POST, "/api/integrations/connections/oauth/start", other.admin, mapOf("connectorId" to "stub-oauth"), 409)
    }

    @Test
    fun `a connection still in use is not deleted, and nothing is revoked`() {
        val (id, _) = connect()
        held.hold(id, "workflow 'Nightly'")
        try {
            val refused = call(client, HttpMethod.DELETE, "/api/integrations/connections/$id", tenant.admin, null, 409)
            assertTrue(refused.get("detail").asString().contains("workflow 'Nightly'"), refused.toString())
            assertTrue(stub.requests.none { it.path == "/revoke" })
            call(client, HttpMethod.GET, "/api/integrations/connections/$id", tenant.admin, null, 200)
        } finally {
            held.release(id)
        }
        call(client, HttpMethod.DELETE, "/api/integrations/connections/$id", tenant.admin, null, 204)
    }

    @Test
    fun `a provider app is not deleted while connections use it`() {
        val t = TestTenant.provision(client).also { configure(it) }
        val (id, _) = connect(t)
        call(client, HttpMethod.DELETE, "/api/integrations/provider-configs/stub-oauth", t.admin, null, 409)
        call(client, HttpMethod.GET, "/api/integrations/provider-configs/stub-oauth", t.admin, null, 200)
        call(client, HttpMethod.DELETE, "/api/integrations/connections/$id", t.admin, null, 204)
        call(client, HttpMethod.DELETE, "/api/integrations/provider-configs/stub-oauth", t.admin, null, 204)
        call(client, HttpMethod.DELETE, "/api/integrations/provider-configs/stub-oauth", t.admin, null, 404)
    }

    @Test
    fun `disconnect revokes the token and removes the connection`() {
        val (id, _) = connect()
        call(client, HttpMethod.DELETE, "/api/integrations/connections/$id", tenant.admin, null, 204)
        assertNotNull(stub.requests.firstOrNull { it.path == "/revoke" })
        assertEquals("rt-1", form(stub.requests.first { it.path == "/revoke" }.bodyText())["token"])
        call(client, HttpMethod.GET, "/api/integrations/connections/$id", tenant.admin, null, 404)
    }
}
