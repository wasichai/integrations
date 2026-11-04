package wasichai.integrations.sync

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

@TestPropertySource(
    properties = [
        "wasichai.integrations.encryption.keys.it1=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        "wasichai.integrations.encryption.active-key=it1",
        // tests drive the job runner themselves
        "wasichai.integrations.jobs.worker-enabled=false",
        "wasichai.integrations.jobs.backoff=1ms",
        "wasichai.integrations.jobs.max-backoff=1ms"
    ]
)
abstract class SyncIntegrationTest : WasichaiIntegrationTest()

// a fresh organization per test class: tenant isolation is part of what is tested
class SyncTenant private constructor(
    private val client: WebTestClient,
    val organizationId: UUID,
    val slug: String,
    val admin: String
) {
    fun member(): String {
        val email = "member-${unique()}@$slug.local"
        call(
            client,
            HttpMethod.POST,
            "/api/users",
            admin,
            mapOf(
                "email" to email,
                "displayName" to "Member",
                "password" to PASSWORD,
                "roles" to emptyList<String>()
            ),
            201
        )
        return login(client, email, PASSWORD)
    }

    // an api-key connection to an in-memory provider; its id is also the provider's data partition
    fun connect(connectorId: String): String =
        call(
            client,
            HttpMethod.POST,
            "/api/integrations/connections",
            admin,
            mapOf("connectorId" to connectorId, "authType" to "API_KEY", "apiKey" to "k"),
            201
        ).get("id")
            .asString()

    companion object {
        const val PASSWORD = "supersecret"
        private val json = JsonMapper.builder().build()

        fun provision(client: WebTestClient): SyncTenant {
            val platform = login(client, WasichaiIntegrationTest.ADMIN_EMAIL, WasichaiIntegrationTest.ADMIN_PASSWORD)
            val slug = "sync-" + unique().take(10)
            val adminEmail = "admin@$slug.local"
            val organization =
                call(
                    client,
                    HttpMethod.POST,
                    "/api/organizations",
                    platform,
                    mapOf("name" to "Sync $slug", "slug" to slug, "adminEmail" to adminEmail, "adminPassword" to PASSWORD),
                    201
                )
            return SyncTenant(client, UUID.fromString(organization.get("id").asString()), slug, login(client, adminEmail, PASSWORD))
        }

        fun unique(): String =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)

        fun login(
            client: WebTestClient,
            email: String,
            password: String
        ): String =
            "Bearer " + call(client, HttpMethod.POST, "/api/auth/login", null, mapOf("email" to email, "password" to password), 200).get("token").asString()

        fun call(
            client: WebTestClient,
            method: HttpMethod,
            uri: String,
            token: String?,
            body: Any?,
            expected: Int,
            headers: Map<String, String> = emptyMap()
        ): JsonNode {
            val request =
                client
                    .method(method)
                    .uri(uri)
                    .headers { h ->
                        token?.let { h.set(HttpHeaders.AUTHORIZATION, it) }
                        headers.forEach { (k, v) -> h.set(k, v) }
                    }
            val result = (if (body == null) request else request.bodyValue(body)).exchange().expectBody(String::class.java).returnResult()
            if (result.status.value() != expected) {
                throw AssertionError("$method $uri answered ${result.status}, expected $expected: ${result.responseBody}")
            }
            return result.responseBody?.let { json.readTree(it) } ?: json.nullNode()
        }
    }
}
