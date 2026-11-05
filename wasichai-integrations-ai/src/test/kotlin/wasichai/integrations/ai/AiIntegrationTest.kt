package wasichai.integrations.ai

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
        // nothing runs in the background of these tests
        "wasichai.integrations.jobs.worker-enabled=false",
        // tests call the purge themselves
        "wasichai.integrations.retention.enabled=false"
    ]
)
abstract class AiIntegrationTest : WasichaiIntegrationTest()

// a fresh organization per test class: tenant isolation is part of what is tested
class AiTenant private constructor(
    private val client: WebTestClient,
    val organizationId: UUID,
    val slug: String,
    val admin: String
) {
    fun role(name: String) {
        call(client, HttpMethod.POST, "/api/roles", admin, mapOf("name" to name, "label" to name), 201)
    }

    fun member(vararg roles: String): String {
        val email = "member-${unique()}@$slug.local"
        call(
            client,
            HttpMethod.POST,
            "/api/users",
            admin,
            mapOf("email" to email, "displayName" to "Member", "password" to PASSWORD, "roles" to roles.toList()),
            201
        )
        return login(client, email, PASSWORD)
    }

    fun connect(
        connectorId: String,
        apiKey: String = "k"
    ): String =
        call(
            client,
            HttpMethod.POST,
            "/api/integrations/connections",
            admin,
            mapOf("connectorId" to connectorId, "authType" to "API_KEY", "apiKey" to apiKey),
            201
        ).get("id")
            .asString()

    companion object {
        const val PASSWORD = "supersecret"
        private val json = JsonMapper.builder().build()

        fun provision(client: WebTestClient): AiTenant {
            val platform = login(client, WasichaiIntegrationTest.ADMIN_EMAIL, WasichaiIntegrationTest.ADMIN_PASSWORD)
            val slug = "ai-" + unique().take(10)
            val adminEmail = "admin@$slug.local"
            val organization =
                call(
                    client,
                    HttpMethod.POST,
                    "/api/organizations",
                    platform,
                    mapOf("name" to "AI $slug", "slug" to slug, "adminEmail" to adminEmail, "adminPassword" to PASSWORD),
                    201
                )
            return AiTenant(client, UUID.fromString(organization.get("id").asString()), slug, login(client, adminEmail, PASSWORD))
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
