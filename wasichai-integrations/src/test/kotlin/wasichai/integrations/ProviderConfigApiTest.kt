package wasichai.integrations

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.TestTenant.Companion.call

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProviderConfigApiTest : IntegrationsIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val tenant by lazy { TestTenant.provision(client) }
    private val other by lazy { TestTenant.provision(client) }

    private fun storedSecret(tenant: TestTenant): String? =
        runBlocking {
            db
                .sql("SELECT client_secret_sealed FROM ${schemas.metadata}.integration_provider_configs WHERE organization_id = :org")
                .bind("org", tenant.organizationId)
                .map { row, _ -> row.get("client_secret_sealed", String::class.java) ?: "" }
                .one()
                .awaitSingle()
        }

    @Test
    fun `the catalog lists installed connectors with their declared capabilities`() {
        val list = call(client, HttpMethod.GET, "/api/integrations/connectors", tenant.member(), null, 200)
        val ids = (0 until list.size()).map { list.get(it).get("id").asString() }
        assertTrue(ids.containsAll(listOf("test-oauth", "test-key")))
        val one = call(client, HttpMethod.GET, "/api/integrations/connectors/test-oauth", tenant.admin, null, 200)
        assertTrue(one.get("needsProviderConfig").asBoolean())
        assertEquals("TESTED", one.get("capabilities").get("AUTHENTICATION").asString())
        call(client, HttpMethod.GET, "/api/integrations/connectors/nope", tenant.admin, null, 404)
    }

    @Test
    fun `a provider config keeps its secret sealed and never answers it`() {
        // its own tenant: the other tests configure the same connector
        val tenant = TestTenant.provision(client)
        val created =
            call(
                client,
                HttpMethod.PUT,
                "/api/integrations/provider-configs/test-oauth",
                tenant.admin,
                mapOf("clientId" to "client-1", "clientSecret" to "very-secret-value", "scopes" to listOf("read")),
                200
            )
        assertTrue(created.get("clientSecretSet").asBoolean())
        assertFalse(created.toString().contains("very-secret-value"))
        assertTrue(storedSecret(tenant)!!.startsWith("v1.it1."))
        assertFalse(storedSecret(tenant)!!.contains("very-secret-value"))
        val catalog = call(client, HttpMethod.GET, "/api/integrations/connectors/test-oauth", tenant.admin, null, 200)
        assertTrue(catalog.get("providerConfigured").asBoolean())

        // a replace needs the version it read; without the secret the stored one stays
        call(client, HttpMethod.PUT, "/api/integrations/provider-configs/test-oauth", tenant.admin, mapOf("clientId" to "client-2"), 412)
        val replaced =
            call(
                client,
                HttpMethod.PUT,
                "/api/integrations/provider-configs/test-oauth",
                tenant.admin,
                mapOf("clientId" to "client-2"),
                200,
                mapOf("If-Match" to "\"1\"")
            )
        assertEquals("client-2", replaced.get("clientId").asString())
        assertEquals(2, replaced.get("version").asInt())
        assertTrue(replaced.get("clientSecretSet").asBoolean())
        call(client, HttpMethod.PUT, "/api/integrations/provider-configs/test-oauth", tenant.admin, mapOf("clientId" to "c"), 412, mapOf("If-Match" to "\"1\""))
    }

    @Test
    fun `another tenant neither sees nor changes it`() {
        val tenant = TestTenant.provision(client)
        call(client, HttpMethod.PUT, "/api/integrations/provider-configs/test-oauth", tenant.admin, mapOf("clientId" to "mine"), 200)
        val theirs = call(client, HttpMethod.GET, "/api/integrations/provider-configs", other.admin, null, 200)
        assertEquals(0, theirs.size())
        call(client, HttpMethod.GET, "/api/integrations/provider-configs/test-oauth", other.admin, null, 404)
        call(client, HttpMethod.DELETE, "/api/integrations/provider-configs/test-oauth", other.admin, null, 404)
        assertFalse(call(client, HttpMethod.GET, "/api/integrations/connectors/test-oauth", other.admin, null, 200).get("providerConfigured").asBoolean())
    }

    @Test
    fun `members may read the catalog but not manage provider apps`() {
        val member = tenant.member()
        call(client, HttpMethod.GET, "/api/integrations/provider-configs", member, null, 403)
        call(client, HttpMethod.PUT, "/api/integrations/provider-configs/test-oauth", member, mapOf("clientId" to "x"), 403)
        call(client, HttpMethod.GET, "/api/integrations/connectors", null, null, 401)
    }

    @Test
    fun `invalid configs name their fields`() {
        val bad =
            call(
                client,
                HttpMethod.PUT,
                "/api/integrations/provider-configs/test-oauth",
                tenant.admin,
                mapOf("clientId" to " ", "scopes" to listOf("a b"), "options" to mapOf("Bad-Key" to "x")),
                400
            )
        val fields =
            (0 until bad.get("errors").size())
                .map {
                    bad
                        .get("errors")
                        .get(it)
                        .get("field")
                        .asString()
                }.toSet()
        assertEquals(setOf("clientId", "scopes[0]", "options.Bad-Key"), fields)
        // an api key connector has no oauth app to configure
        call(client, HttpMethod.PUT, "/api/integrations/provider-configs/test-key", tenant.admin, mapOf("clientId" to "x"), 400)
        call(client, HttpMethod.PUT, "/api/integrations/provider-configs/nope", tenant.admin, mapOf("clientId" to "x"), 404)
    }
}
