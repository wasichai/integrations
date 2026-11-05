package wasichai.integrations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import wasichai.integrations.autoconfigure.IntegrationsProperties
import wasichai.integrations.sdk.ClientAuthentication
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.testing.StubProviderServer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TokenClientTest {
    private val stub = StubProviderServer()
    private val tokens = TokenClient(PlatformHttp(IntegrationsProperties.Http(allowPrivateAddresses = true)), JsonMapper.builder().build())
    private val client = OAuthClient("client-1", "secret-1", ClientAuthentication.BODY)

    @AfterAll
    fun stop() = stub.close()

    @BeforeEach
    fun reset() = stub.reset()

    private fun answer(
        body: String,
        status: Int = 200
    ) = stub.onJson(HttpMethod.POST, "/token", body, status)

    private fun refresh() = runBlocking { tokens.refresh("${stub.baseUrl}/token", client, "rt-1", " ") }

    private fun exchange() = runBlocking { tokens.exchangeCode("${stub.baseUrl}/token", client, "code-1", "https://app.example/cb", null, " ") }

    @Test
    fun `a 200 with an access token is a grant`() {
        answer("""{"access_token":"at-1","refresh_token":"rt-2","expires_in":3600,"scope":"read write"}""")
        val grant = refresh()
        assertEquals("at-1", grant.accessToken)
        assertEquals("rt-2", grant.refreshToken)
        assertEquals(3600L, grant.expiresInSeconds)
        assertEquals(listOf("read", "write"), grant.scopes)
    }

    @Test
    fun `a 200 without an access token that names an error is a refused refresh`() {
        answer("""{"ok":false,"error":"invalid_refresh_token"}""")
        assertEquals("invalid_refresh_token", assertThrows<TokenRefusedException> { refresh() }.error)
    }

    @Test
    fun `a 200 without an access token that names an error is a refused code`() {
        answer("""{"error":"bad_verification_code","error_description":"The code passed is incorrect or expired."}""")
        assertEquals("bad_verification_code", assertThrows<TokenRefusedException> { exchange() }.error)
    }

    @Test
    fun `a 200 without an access token and without an error stays a malformed answer`() {
        answer("""{"ok":false}""")
        assertThrows<ConnectorException.Permanent> { refresh() }
        answer("""{"ok":false,"error":""}""")
        assertThrows<ConnectorException.Permanent> { refresh() }
        answer("""{"ok":false,"error":{"code":"x"}}""")
        assertThrows<ConnectorException.Permanent> { refresh() }
        answer("not json")
        assertThrows<ConnectorException.Permanent> { refresh() }
    }

    @Test
    fun `a 400 invalid_grant is still a refused grant, and a 500 is still transient`() {
        answer("""{"error":"invalid_grant"}""", 400)
        assertEquals("invalid_grant", assertThrows<TokenRefusedException> { refresh() }.error)
        answer("""{"error":"server_error"}""", 500)
        assertThrows<ConnectorException.Transient> { refresh() }
    }
}
