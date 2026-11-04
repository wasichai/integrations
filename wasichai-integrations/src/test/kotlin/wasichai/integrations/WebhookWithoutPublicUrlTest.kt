package wasichai.integrations

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.TestPropertySource
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.TestTenant.Companion.call
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// an endpoint made while a public base url was set, then the property removed: the endpoint is still
// listed, its url is null instead of a 500, and no new endpoint can be made
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(
    properties = [
        "wasichai.integrations.encryption.keys.it1=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        "wasichai.integrations.encryption.active-key=it1",
        "wasichai.integrations.jobs.worker-enabled=false"
    ]
)
class WebhookWithoutPublicUrlTest : WasichaiIntegrationTest() {
    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val tenant by lazy { TestTenant.provision(client) }

    @Test
    fun `an endpoint without a public base url lists with a null url`() {
        val connectionId =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/connections",
                tenant.admin,
                mapOf("connectorId" to "stub-hooks", "authType" to "API_KEY", "apiKey" to "k"),
                201
            ).get("id").asString()
        call(client, HttpMethod.POST, "/api/integrations/connections/$connectionId/webhook-endpoints", tenant.admin, emptyMap<String, String>(), 409)
        val endpointId = UUID.randomUUID()
        runBlocking {
            db
                .sql(
                    "INSERT INTO ${schemas.metadata}.integration_webhook_endpoints (id, organization_id, connection_id, connector_id) " +
                        "VALUES (:id, :org, :connection, 'stub-hooks')"
                ).bind("id", endpointId)
                .bind("org", tenant.organizationId)
                .bind("connection", UUID.fromString(connectionId))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val listed = call(client, HttpMethod.GET, "/api/integrations/connections/$connectionId/webhook-endpoints", tenant.admin, null, 200)
        assertEquals(1, listed.size(), listed.toString())
        val endpoint = listed.get(0)
        assertEquals(endpointId.toString(), endpoint.get("id").asString())
        assertTrue(endpoint.get("url") == null || endpoint.get("url").isNull, endpoint.toString())
    }
}
