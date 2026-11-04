package wasichai.integrations

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest
import java.util.UUID

// a fresh organization per test class: the database is shared, tenant isolation is what is under test.
class TestTenant private constructor(
    private val client: WebTestClient,
    val organizationId: UUID,
    val slug: String,
    // "Bearer ..." of the tenant's ADMIN
    val admin: String
) {
    // a plain member: signed in, no role, so no MANAGE_ORGANIZATION
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

    companion object {
        const val PASSWORD = "supersecret"
        private val json = JsonMapper.builder().build()

        fun provision(client: WebTestClient): TestTenant {
            val platform = login(client, WasichaiIntegrationTest.ADMIN_EMAIL, WasichaiIntegrationTest.ADMIN_PASSWORD)
            val slug = "int-" + unique().take(10)
            val adminEmail = "admin@$slug.local"
            val organization =
                call(
                    client,
                    HttpMethod.POST,
                    "/api/organizations",
                    platform,
                    mapOf("name" to "Integrations $slug", "slug" to slug, "adminEmail" to adminEmail, "adminPassword" to PASSWORD),
                    201
                )
            return TestTenant(client, UUID.fromString(organization.get("id").asString()), slug, login(client, adminEmail, PASSWORD))
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
            // never the request body in the message: a login carries a password
            if (result.status.value() != expected) {
                throw AssertionError("$method $uri answered ${result.status}, expected $expected: ${result.responseBody}")
            }
            return result.responseBody?.let { json.readTree(it) } ?: json.nullNode()
        }
    }
}
