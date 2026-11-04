package wasichai.integrations.sync

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import wasichai.integrations.JobRunner
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sync.SyncTenant.Companion.call

// issue #32: the provider created the record, the process died before the link was saved. the retried run
// must find that record again (upsert or marker) or show the pair to an operator, never create it twice.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreateRecoveryTest : SyncIntegrationTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var memA: MemConnector

    @Autowired
    private lateinit var memB: MemConnector

    @Autowired
    private lateinit var memU: MemUpserter

    private val tenant by lazy { SyncTenant.provision(client) }
    private val other by lazy { SyncTenant.provision(client) }
    private val res = "contacts"

    private fun text(s: String) = FieldValue.Text(s)

    private fun define(
        a: String,
        b: String,
        externalIdField: String?
    ): String {
        val body =
            mapOf(
                "name" to "Contacts ${SyncTenant.unique()}",
                "a" to mapOf("connectionId" to a, "resource" to res),
                "b" to mapOf("connectionId" to b, "resource" to res, "externalIdField" to externalIdField),
                "direction" to "A_TO_B",
                "deletePolicy" to "FLAG",
                "mappings" to listOf("name", "email").map { mapOf("aField" to it, "bField" to it, "owner" to "A") }
            )
        val id = call(client, HttpMethod.POST, "/api/integrations/sync-definitions", tenant.admin, body, 201).get("id").asString()
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/activate", tenant.admin, null, 200)
        return id
    }

    private fun start(definition: String): String =
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", tenant.admin, null, 202).get("runId").asString()

    // drains jobs until the run reaches one of the statuses: a crashed run is FAILED, then its job retries it
    private fun until(
        definition: String,
        runId: String,
        statuses: Set<String>
    ): JsonNode {
        repeat(100) {
            runBlocking { runner.drainOnce() }
            val runs = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/runs", tenant.admin, null, 200)
            val run = (0 until runs.size()).map { runs.get(it) }.first { it.get("id").asString() == runId }
            if (run.get("status").asString() in statuses) return run
            Thread.sleep(20)
        }
        throw AssertionError("run $runId never reached $statuses")
    }

    private fun sync(definition: String): JsonNode = until(definition, start(definition), DONE)

    private fun conflicts(
        definition: String,
        token: String = tenant.admin
    ): List<JsonNode> {
        val list = call(client, HttpMethod.GET, "/api/integrations/conflicts?definitionId=$definition&status=OPEN", token, null, 200)
        return (0 until list.size()).map { list.get(it) }
    }

    @Test
    fun `upsert mode - a create cut short is upserted again with the same token, no duplicate`() {
        val a = tenant.connect("mem-a")
        val b = tenant.connect("mem-u")
        val definition = define(a, b, "extId")
        val ana = memA.put(a, res, mapOf("name" to text("Ana"), "email" to text("ana@x.test")))
        memU.dieAfterNextCreate()
        val runId = start(definition)
        val crashed = until(definition, runId, setOf("FAILED"))
        assertEquals("transient", crashed.get("errorKind").asString(), crashed.toString())
        val stranded = memU.records(b, res).single()
        val token = (stranded.fields["extId"] as FieldValue.Text).value

        val recovered = until(definition, runId, DONE)
        assertEquals("SUCCEEDED", recovered.get("status").asString(), recovered.toString())
        val inB = memU.records(b, res).single()
        assertEquals(stranded.id, inB.id, "the upsert found the record the crash left behind")
        assertEquals(text(token), inB.fields["extId"])
        assertTrue(conflicts(definition).isEmpty())

        // the pair is linked: an edit flows to that record, and nothing is created again
        memA.put(a, res, mapOf("name" to text("Ana Maria")), ana)
        val next = sync(definition)
        assertEquals(0, next.get("created").asInt(), next.toString())
        assertEquals(text("Ana Maria"), memU.records(b, res).single().fields["name"])
    }

    @Test
    fun `upsert mode - an ordinary create upserts by the create token`() {
        val a = tenant.connect("mem-a")
        val b = tenant.connect("mem-u")
        val definition = define(a, b, "extId")
        memA.put(a, res, mapOf("name" to text("Bo")))
        val run = sync(definition)
        assertEquals(1, run.get("created").asInt(), run.toString())
        assertNotNull(memU.records(b, res).single().fields["extId"])
    }

    @Test
    fun `marker mode - a create cut short is found by its token and catches up, no duplicate`() {
        val a = tenant.connect("mem-a")
        val b = tenant.connect("mem-b")
        val definition = define(a, b, "marker")
        val ana = memA.put(a, res, mapOf("name" to text("Ana"), "email" to text("ana@x.test")))
        // a record of someone else's that carries no token is left alone
        memB.put(b, res, mapOf("name" to text("Unrelated")))
        memB.dieAfterNextCreate()
        val runId = start(definition)
        until(definition, runId, setOf("FAILED"))
        val stranded = memB.records(b, res).single { it.fields["name"] == text("Ana") }
        assertTrue(stranded.fields["marker"] is FieldValue.Text, stranded.fields.toString())
        // changed while the run was down: the recovery carries it to the found record
        memA.put(a, res, mapOf("email" to text("ana@new.test")), ana)

        val recovered = until(definition, runId, DONE)
        assertEquals("SUCCEEDED", recovered.get("status").asString(), recovered.toString())
        assertEquals(0, recovered.get("created").asInt(), recovered.toString())
        assertEquals(2, memB.records(b, res).size, "no duplicate of Ana")
        val inB = memB.records(b, res).single { it.id == stranded.id }
        assertEquals(text("ana@new.test"), inB.fields["email"])
        assertEquals(stranded.fields["marker"], inB.fields["marker"], "the token stays: the mapping never writes it")
        assertTrue(conflicts(definition).isEmpty())
        val quiet = sync(definition)
        assertEquals(0, quiet.get("created").asInt() + quiet.get("updated").asInt(), quiet.toString())
    }

    @Test
    fun `marker mode - a create that never reached the provider is created again with its token`() {
        val a = tenant.connect("mem-a")
        val b = tenant.connect("mem-b")
        val definition = define(a, b, "marker")
        memA.put(a, res, mapOf("name" to text("Cy")))
        memB.dieAfterNextCreate()
        val crashed = start(definition)
        until(definition, crashed, setOf("FAILED"))
        // the provider lost the write after all: nothing carries the token, so the retry creates it, once
        memB.records(b, res).forEach { memB.vanish(b, res, it.id) }

        val recovered = until(definition, crashed, DONE)
        assertEquals("SUCCEEDED", recovered.get("status").asString(), recovered.toString())
        assertEquals(1, recovered.get("created").asInt(), recovered.toString())
        assertEquals(1, memB.records(b, res).size)
    }

    @Test
    fun `nothing configured - the pair is ORPHANED with an open conflict, and resolving it unlinks`() {
        val a = tenant.connect("mem-a")
        val b = tenant.connect("mem-b")
        val definition = define(a, b, null)
        memA.put(a, res, mapOf("name" to text("Di")))
        memB.dieAfterNextCreate()
        val runId = start(definition)
        until(definition, runId, setOf("FAILED"))

        val recovered = until(definition, runId, DONE)
        assertEquals("PARTIAL", recovered.get("status").asString(), recovered.toString())
        assertEquals(1, recovered.get("failed").asInt(), recovered.toString())
        assertEquals(1, recovered.get("conflicts").asInt(), recovered.toString())
        assertEquals(1, memB.records(b, res).size, "no blind second create")
        val conflict = conflicts(definition).single()
        assertEquals("ORPHANED_CREATE", conflict.get("kind").asString())
        assertTrue(conflicts(definition, other.admin).isEmpty(), "another tenant sees nothing")
        val status = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/status", tenant.admin, null, 200)
        assertEquals(1, status.get("links").get("ORPHANED").asInt(), status.toString())

        val id = conflict.get("id").asString()
        call(client, HttpMethod.POST, "/api/integrations/conflicts/$id/resolve", other.admin, mapOf<String, Any>(), 404)
        call(client, HttpMethod.POST, "/api/integrations/conflicts/$id/resolve", tenant.member(), mapOf<String, Any>(), 403)
        val resolved = call(client, HttpMethod.POST, "/api/integrations/conflicts/$id/resolve", tenant.admin, mapOf<String, Any>(), 200)
        assertEquals("RESOLVED", resolved.get("status").asString())
        assertEquals("UNLINK", resolved.get("resolution").get("choice").asString())
        val after = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/status", tenant.admin, null, 200)
        assertEquals(0, after.get("links").get("ORPHANED").asInt(), after.toString())
    }

    companion object {
        private val DONE = setOf("SUCCEEDED", "PARTIAL")
    }
}
