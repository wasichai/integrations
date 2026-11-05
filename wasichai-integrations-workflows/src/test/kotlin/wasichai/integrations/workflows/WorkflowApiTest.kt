package wasichai.integrations.workflows

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.http.HttpMethod
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.JsonNode
import wasichai.integrations.workflows.WorkflowTenant.Companion.call

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowApiTest : WorkflowsIntegrationTest() {
    private val tenant by lazy { WorkflowTenant.provision(client) }
    private val other by lazy { WorkflowTenant.provision(client) }
    private val records by lazy { tenant.connect("mem-a") }
    private val messenger by lazy { tenant.connect("mem-m") }
    private val base = "/api/integrations/workflows"

    // event -> create a contact -> notify
    private fun graph(
        name: Any? = mapOf("ref" to "trigger.payload.name"),
        resource: String = "contacts",
        connection: String = records
    ) = mapOf(
        "trigger" to mapOf("type" to "event", "connectionId" to messenger, "eventType" to "message.created", "next" to "create"),
        "steps" to
            listOf(
                mapOf(
                    "id" to "create",
                    "type" to "action",
                    "connectionId" to connection,
                    "write" to mapOf("resource" to resource, "operation" to "create"),
                    "input" to mapOf("name" to name),
                    "next" to "notify"
                ),
                mapOf(
                    "id" to "notify",
                    "type" to "action",
                    "connectionId" to messenger,
                    "action" to "send_message",
                    "input" to mapOf("text" to mapOf("ref" to "steps.create.output.id")),
                    "next" to "done"
                ),
                mapOf("id" to "done", "type" to "end")
            )
    )

    private fun create(
        graph: Any? = graph(),
        name: String = "New contacts"
    ): JsonNode = call(client, HttpMethod.POST, base, tenant.admin, mapOf("name" to name, "graph" to graph), 201)

    private fun fields(problem: JsonNode): Set<String> {
        val errors = problem.get("errors")
        return (0 until errors.size()).map { errors.get(it).get("field").asString() }.toSet()
    }

    @Test
    fun `a draft is created, read, listed, updated and deleted`() {
        val created = create()
        val id = created.get("id").asString()
        assertEquals("DRAFT", created.get("status").asString())
        assertTrue(created.get("unpublishedChanges").asBoolean())
        assertTrue(created.get("publishedVersion").isNull)
        assertEquals(
            "create",
            created
                .get("graph")
                .get("steps")
                .get(0)
                .get("id")
                .asString()
        )
        val read = call(client, HttpMethod.GET, "$base/$id", tenant.member(), null, 200)
        assertEquals("New contacts", read.get("name").asString())
        val listed = call(client, HttpMethod.GET, base, tenant.member(), null, 200)
        assertTrue((0 until listed.size()).any { listed.get(it).get("id").asString() == id })
        val updated =
            call(client, HttpMethod.PUT, "$base/$id", tenant.admin, mapOf("name" to " Renamed ", "graph" to graph()), 200, mapOf("If-Match" to "\"1\""))
        assertEquals("Renamed", updated.get("name").asString())
        assertEquals(2, updated.get("version").asInt())
        call(client, HttpMethod.DELETE, "$base/$id", tenant.admin, null, 204)
        call(client, HttpMethod.GET, "$base/$id", tenant.admin, null, 404)
    }

    @Test
    fun `a half-built draft is saved, only its name and json shape are checked`() {
        val partial = create(graph = mapOf("steps" to listOf(mapOf("type" to "action"))))
        assertEquals("DRAFT", partial.get("status").asString())
        assertTrue(create(graph = null).get("graph").isObject)
        val problem = call(client, HttpMethod.POST, base, tenant.admin, mapOf("name" to "", "graph" to listOf(1)), 400)
        assertEquals(setOf("name", "graph"), fields(problem))
    }

    @Test
    fun `update needs the version it read`() {
        val id = create().get("id").asString()
        call(client, HttpMethod.PUT, "$base/$id", tenant.admin, mapOf("name" to "x", "graph" to graph()), 412)
        call(client, HttpMethod.PUT, "$base/$id", tenant.admin, mapOf("name" to "x", "graph" to graph()), 200, mapOf("If-Match" to "\"1\""))
        call(client, HttpMethod.PUT, "$base/$id", tenant.admin, mapOf("name" to "y", "graph" to graph()), 412, mapOf("If-Match" to "\"1\""))
        call(client, HttpMethod.PUT, "$base/$id", tenant.admin, mapOf("name" to "y", "graph" to graph()), 400, mapOf("If-Match" to "one"))
    }

    @Test
    fun `validate reports field-located violations, including the provider's schema`() {
        val good = create().get("id").asString()
        val valid = call(client, HttpMethod.POST, "$base/$good/validate", tenant.admin, null, 200)
        assertTrue(valid.get("valid").asBoolean(), valid.toString())
        val broken = create(graph = graph(name = mapOf("value" to 42))).get("id").asString()
        val result = call(client, HttpMethod.POST, "$base/$broken/validate", tenant.admin, null, 200)
        assertFalse(result.get("valid").asBoolean())
        assertEquals(setOf("graph.steps[0].input.name.value"), fields(result))
        val gone = create(graph = graph(resource = "ghosts")).get("id").asString()
        assertEquals(setOf("graph.steps[0].write.resource"), fields(call(client, HttpMethod.POST, "$base/$gone/validate", tenant.admin, null, 200)))
        val problem = call(client, HttpMethod.POST, "$base/$broken/publish", tenant.admin, null, 422)
        assertEquals(setOf("graph.steps[0].input.name.value"), fields(problem))
    }

    @Test
    fun `publishing makes immutable numbered versions`() {
        val id = create().get("id").asString()
        val v1 = call(client, HttpMethod.POST, "$base/$id/publish", tenant.admin, null, 201)
        assertEquals(1, v1.get("number").asInt())
        val published = call(client, HttpMethod.GET, "$base/$id", tenant.admin, null, 200)
        assertEquals("PUBLISHED", published.get("status").asString())
        assertEquals(1, published.get("publishedVersion").asInt())
        assertFalse(published.get("unpublishedChanges").asBoolean())
        // nothing new to publish
        call(client, HttpMethod.POST, "$base/$id/publish", tenant.admin, null, 409)

        val version = published.get("version").asInt()
        val changed = graph(name = mapOf("value" to "Ada"))
        val draft =
            call(
                client,
                HttpMethod.PUT,
                "$base/$id",
                tenant.admin,
                mapOf("name" to "New contacts", "graph" to changed),
                200,
                mapOf("If-Match" to "\"$version\"")
            )
        assertTrue(draft.get("unpublishedChanges").asBoolean())
        assertEquals(1, draft.get("publishedVersion").asInt())
        // version 1 still holds what was published
        val first = call(client, HttpMethod.GET, "$base/$id/versions/1", tenant.member(), null, 200)
        assertEquals(
            "trigger.payload.name",
            first
                .get("graph")
                .get("steps")
                .get(0)
                .get("input")
                .get("name")
                .get("ref")
                .asString()
        )

        call(client, HttpMethod.POST, "$base/$id/publish", tenant.admin, null, 412, mapOf("If-Match" to "\"$version\""))
        val v2 = call(client, HttpMethod.POST, "$base/$id/publish", tenant.admin, null, 201, mapOf("If-Match" to "\"${draft.get("version").asInt()}\""))
        assertEquals(2, v2.get("number").asInt())
        assertEquals(
            "Ada",
            v2
                .get("graph")
                .get("steps")
                .get(0)
                .get("input")
                .get("name")
                .get("value")
                .asString()
        )
        val versions = call(client, HttpMethod.GET, "$base/$id/versions", tenant.member(), null, 200)
        assertEquals(listOf(2, 1), (0 until versions.size()).map { versions.get(it).get("number").asInt() })
        assertEquals(
            "trigger.payload.name",
            call(client, HttpMethod.GET, "$base/$id/versions/1", tenant.admin, null, 200)
                .get("graph")
                .get("steps")
                .get(0)
                .get("input")
                .get("name")
                .get("ref")
                .asString()
        )
        call(client, HttpMethod.GET, "$base/$id/versions/3", tenant.admin, null, 404)
    }

    @Test
    fun `a connection a workflow uses is not deleted under it`() {
        val used = tenant.connect("mem-a")
        val id = create(graph = graph(connection = used), name = "Uses it").get("id").asString()
        val refused = call(client, HttpMethod.DELETE, "/api/integrations/connections/$used", tenant.admin, null, 409)
        assertTrue(refused.get("detail").asString().contains("workflow 'Uses it'"), refused.toString())
        call(client, HttpMethod.DELETE, "$base/$id", tenant.admin, null, 204)
        call(client, HttpMethod.DELETE, "/api/integrations/connections/$used", tenant.admin, null, 204)
    }

    @Test
    fun `another tenant's workflow is not found, and its connections are unknown`() {
        val id = create().get("id").asString()
        call(client, HttpMethod.POST, "$base/$id/publish", tenant.admin, null, 201)
        call(client, HttpMethod.GET, "$base/$id", other.admin, null, 404)
        call(client, HttpMethod.PUT, "$base/$id", other.admin, mapOf("name" to "x", "graph" to graph()), 404, mapOf("If-Match" to "\"1\""))
        call(client, HttpMethod.POST, "$base/$id/validate", other.admin, null, 404)
        call(client, HttpMethod.POST, "$base/$id/publish", other.admin, null, 404)
        call(client, HttpMethod.GET, "$base/$id/versions", other.admin, null, 404)
        call(client, HttpMethod.GET, "$base/$id/versions/1", other.admin, null, 404)
        call(client, HttpMethod.DELETE, "$base/$id", other.admin, null, 404)
        val listed = call(client, HttpMethod.GET, base, other.admin, null, 200)
        assertFalse((0 until listed.size()).any { listed.get(it).get("id").asString() == id })
        // the same graph in the other tenant names connections it does not have
        val theirs = call(client, HttpMethod.POST, base, other.admin, mapOf("name" to "Borrowed", "graph" to graph()), 201).get("id").asString()
        val result = call(client, HttpMethod.POST, "$base/$theirs/validate", other.admin, null, 200)
        assertEquals(setOf("graph.trigger.connectionId", "graph.steps[0].connectionId", "graph.steps[1].connectionId"), fields(result))
    }

    @Test
    fun `a member reads but does not manage`() {
        val member = tenant.member()
        val id = create().get("id").asString()
        call(client, HttpMethod.GET, "$base/$id", member, null, 200)
        call(client, HttpMethod.POST, base, member, mapOf("name" to "x", "graph" to graph()), 403)
        call(client, HttpMethod.PUT, "$base/$id", member, mapOf("name" to "x", "graph" to graph()), 403, mapOf("If-Match" to "\"1\""))
        call(client, HttpMethod.POST, "$base/$id/validate", member, null, 403)
        call(client, HttpMethod.POST, "$base/$id/publish", member, null, 403)
        call(client, HttpMethod.DELETE, "$base/$id", member, null, 403)
        call(client, HttpMethod.GET, base, null, null, 401)
    }
}

// the module switched off: its routes are not there
@TestPropertySource(properties = ["wasichai.integrations.workflows.enabled=false"])
class WorkflowsDisabledTest : WorkflowsIntegrationTest() {
    @Test
    fun `no workflow routes`() {
        val tenant = WorkflowTenant.provision(client)
        call(client, HttpMethod.GET, "/api/integrations/workflows", tenant.admin, null, 404)
    }
}
