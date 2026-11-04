package wasichai.integrations.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import wasichai.integrations.sync.SyncTenant.Companion.call

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncDefinitionApiTest : SyncIntegrationTest() {
    private val tenant by lazy { SyncTenant.provision(client) }
    private val other by lazy { SyncTenant.provision(client) }
    private val a by lazy { tenant.connect("mem-a") }
    private val b by lazy { tenant.connect("mem-b") }

    private fun body(
        mappings: List<Map<String, Any?>> = listOf(mapOf("aField" to "name", "bField" to "name")),
        direction: String = "BIDIRECTIONAL",
        aConnection: String = a,
        resource: String = "contacts"
    ) = mapOf(
        "name" to "Contacts",
        "a" to mapOf("connectionId" to aConnection, "resource" to resource),
        "b" to mapOf("connectionId" to b, "resource" to "contacts"),
        "direction" to direction,
        "deletePolicy" to "FLAG",
        "mappings" to mappings
    )

    private fun fields(problem: tools.jackson.databind.JsonNode): Set<String> {
        val errors = problem.get("errors")
        return (0 until errors.size()).map { errors.get(it).get("field").asString() }.toSet()
    }

    @Test
    fun `a definition is created as a draft, validated, activated and paused`() {
        val created = call(client, HttpMethod.POST, "/api/integrations/sync-definitions", tenant.admin, body(), 201)
        val id = created.get("id").asString()
        assertEquals("DRAFT", created.get("status").asString())
        val valid = call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/validate", tenant.admin, null, 200)
        assertTrue(valid.get("valid").asBoolean(), valid.toString())
        assertEquals(
            "ACTIVE",
            call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/activate", tenant.admin, null, 200).get("status").asString()
        )
        assertEquals(
            "PAUSED",
            call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/deactivate", tenant.admin, null, 200).get("status").asString()
        )
        val listed = call(client, HttpMethod.GET, "/api/integrations/sync-definitions", tenant.member(), null, 200)
        assertTrue((0 until listed.size()).any { listed.get(it).get("id").asString() == id })
    }

    @Test
    fun `shape problems are 400 naming each field`() {
        val problem =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/sync-definitions",
                tenant.admin,
                body(
                    direction = "A_TO_B",
                    mappings =
                        listOf(
                            mapOf("aField" to "name", "bField" to "name", "owner" to "BOTH"),
                            mapOf("aField" to "email", "bField" to "name", "owner" to "B"),
                            mapOf("aField" to "tier", "bField" to "tier", "transform" to mapOf("type" to "eval"))
                        )
                ),
                400
            )
        assertTrue(
            fields(problem).containsAll(setOf("mappings[0].owner", "mappings[1].owner", "mappings[2].transform.type", "mappings")),
            fields(problem).toString()
        )
    }

    @Test
    fun `schema problems block activation with 422`() {
        val id =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/sync-definitions",
                tenant.admin,
                body(
                    mappings =
                        listOf(
                            mapOf("aField" to "nope", "bField" to "name"),
                            mapOf("aField" to "name", "bField" to "computed"),
                            mapOf("aField" to "amount", "bField" to "email"),
                            mapOf("aField" to "tier", "bField" to "tier", "transform" to mapOf("type" to "enumMap", "map" to mapOf("gold" to "PLATINUM")))
                        )
                ),
                201
            ).get("id").asString()
        val result = call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/validate", tenant.admin, null, 200)
        assertFalse(result.get("valid").asBoolean())
        val problem = call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/activate", tenant.admin, null, 422)
        assertTrue(
            fields(problem).containsAll(setOf("mappings[0].aField", "mappings[1].bField", "mappings[2].transform", "mappings[3].transform.map")),
            fields(problem).toString()
        )
        val missing = call(client, HttpMethod.POST, "/api/integrations/sync-definitions", tenant.admin, body(resource = "ghosts"), 201).get("id").asString()
        assertTrue(
            fields(call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$missing/activate", tenant.admin, null, 422)).contains("a.resource")
        )
    }

    @Test
    fun `externalIdField round trips through create, read and replace`() {
        val created =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/sync-definitions",
                tenant.admin,
                body().plus("b" to mapOf("connectionId" to b, "resource" to "contacts", "externalIdField" to " marker ")),
                201
            )
        val id = created.get("id").asString()
        assertEquals("marker", created.get("b").get("externalIdField").asString())
        assertTrue(created.get("a").path("externalIdField").isNull || created.get("a").path("externalIdField").isMissingNode)
        val read = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$id", tenant.member(), null, 200)
        assertEquals("marker", read.get("b").get("externalIdField").asString())
        assertTrue(call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/validate", tenant.admin, null, 200).get("valid").asBoolean())
        val replaced = call(client, HttpMethod.PUT, "/api/integrations/sync-definitions/$id", tenant.admin, body(), 200, mapOf("If-Match" to "\"1\""))
        assertTrue(replaced.get("b").path("externalIdField").isNull || replaced.get("b").path("externalIdField").isMissingNode)
    }

    @Test
    fun `externalIdField must sit where records are created and outside the mappings`() {
        val problem =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/sync-definitions",
                tenant.admin,
                body(direction = "A_TO_B", mappings = listOf(mapOf("aField" to "name", "bField" to "name", "owner" to "A")))
                    .plus("a" to mapOf("connectionId" to a, "resource" to "contacts", "externalIdField" to "marker"))
                    .plus("b" to mapOf("connectionId" to b, "resource" to "contacts", "externalIdField" to "name")),
                400
            )
        assertTrue(fields(problem).containsAll(setOf("a.externalIdField", "b.externalIdField")), fields(problem).toString())
    }

    @Test
    fun `externalIdField must fit the target schema and connector`() {
        val upserting = tenant.connect("mem-u")

        fun problems(
            connection: String,
            field: String
        ): Set<String> {
            val id =
                call(
                    client,
                    HttpMethod.POST,
                    "/api/integrations/sync-definitions",
                    tenant.admin,
                    body().plus("b" to mapOf("connectionId" to connection, "resource" to "contacts", "externalIdField" to field)),
                    201
                ).get("id").asString()
            val result = call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/validate", tenant.admin, null, 200)
            return if (result.get("valid").asBoolean()) emptySet() else fields(result)
        }
        // marker mode: a writable TEXT field
        assertEquals(setOf("b.externalIdField"), problems(b, "nope"))
        assertEquals(setOf("b.externalIdField"), problems(b, "amount"))
        assertEquals(setOf("b.externalIdField"), problems(b, "computed"))
        assertEquals(emptySet<String>(), problems(b, "marker"))
        // upsert mode: one of the connector's external id fields
        assertEquals(setOf("b.externalIdField"), problems(upserting, "marker"))
        assertEquals(emptySet<String>(), problems(upserting, "extId"))
    }

    @Test
    fun `replace needs the version it read`() {
        val created = call(client, HttpMethod.POST, "/api/integrations/sync-definitions", tenant.admin, body(), 201)
        val id = created.get("id").asString()
        call(client, HttpMethod.PUT, "/api/integrations/sync-definitions/$id", tenant.admin, body(), 412)
        val replaced = call(client, HttpMethod.PUT, "/api/integrations/sync-definitions/$id", tenant.admin, body(), 200, mapOf("If-Match" to "\"1\""))
        assertEquals(2, replaced.get("version").asInt())
        call(client, HttpMethod.PUT, "/api/integrations/sync-definitions/$id", tenant.admin, body(), 412, mapOf("If-Match" to "\"1\""))
    }

    @Test
    fun `other tenants neither see definitions nor use our connections`() {
        val id = call(client, HttpMethod.POST, "/api/integrations/sync-definitions", tenant.admin, body(), 201).get("id").asString()
        call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$id", other.admin, null, 404)
        call(client, HttpMethod.DELETE, "/api/integrations/sync-definitions/$id", other.admin, null, 404)
        assertEquals(0, call(client, HttpMethod.GET, "/api/integrations/sync-definitions", other.admin, null, 200).size())
        val theirs = other.connect("mem-b")
        val problem =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/sync-definitions",
                other.admin,
                body(aConnection = a).plus("b" to mapOf("connectionId" to theirs, "resource" to "contacts")),
                400
            )
        assertTrue(fields(problem).contains("a.connectionId"))
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions", tenant.member(), body(), 403)
    }
}
