package wasichai.integrations.sync

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import tools.jackson.databind.JsonNode
import wasichai.integrations.JobRunner
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sync.SyncTenant.Companion.call
import java.time.Instant
import java.util.UUID

// issue #12: the scheduler, what a reconcile finds that the feeds never said, a refused checkpoint, and the status
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncMonitoringTest : SyncIntegrationTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var scheduler: SyncScheduler

    @Autowired
    private lateinit var memA: MemConnector

    @Autowired
    private lateinit var memB: MemConnector

    private val tenant by lazy { SyncTenant.provision(client) }
    private val other by lazy { SyncTenant.provision(client) }
    private val res = "contacts"

    private inner class Pair(
        val a: String = tenant.connect("mem-a"),
        val b: String = tenant.connect("mem-b")
    )

    private fun define(
        pair: Pair,
        direction: String = "BIDIRECTIONAL",
        deletePolicy: String = "FLAG",
        scheduleSeconds: Int? = null,
        reconcileSeconds: Int? = null
    ): String {
        val body =
            mutableMapOf<String, Any?>(
                "name" to "Contacts ${SyncTenant.unique()}",
                "a" to mapOf("connectionId" to pair.a, "resource" to res),
                "b" to mapOf("connectionId" to pair.b, "resource" to res),
                "direction" to direction,
                "deletePolicy" to deletePolicy,
                "mappings" to listOf(mapOf("aField" to "name", "bField" to "name"), mapOf("aField" to "email", "bField" to "email"))
            )
        scheduleSeconds?.let { body["scheduleSeconds"] = it }
        reconcileSeconds?.let { body["reconcileSeconds"] = it }
        val id = call(client, HttpMethod.POST, "/api/integrations/sync-definitions", tenant.admin, body, 201).get("id").asString()
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/activate", tenant.admin, null, 200)
        return id
    }

    private fun run(definition: String): JsonNode {
        val runs = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/runs", tenant.admin, null, 200)
        return runs.get(0)
    }

    private fun drain(definition: String): JsonNode {
        repeat(60) {
            runBlocking { runner.drainOnce() }
            val last = run(definition)
            if (last.get("status").asString() !in setOf("QUEUED", "RUNNING")) return last
            Thread.sleep(20)
        }
        throw AssertionError("the run of $definition did not finish")
    }

    private fun sync(
        definition: String,
        kind: String = "INCREMENTAL"
    ): JsonNode {
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", tenant.admin, mapOf("kind" to kind), 202)
        return drain(definition)
    }

    private fun text(s: String) = FieldValue.Text(s)

    private fun status(
        definition: String,
        token: String = tenant.admin
    ) = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/status", token, null, 200)

    private fun mine(
        enqueued: List<kotlin.Pair<ScheduleCandidate, RunTrigger>>,
        definition: String
    ) = enqueued.filter { it.first.definitionId == UUID.fromString(definition) }.map { it.second }

    @Test
    fun `a tick enqueues a due run once, and nothing until the interval passes again`() {
        val pair = Pair()
        val definition = define(pair, scheduleSeconds = 60)
        val now = Instant.now()
        assertEquals(listOf(RunTrigger.SCHEDULE), mine(runBlocking { scheduler.tick(now) }, definition))
        // still waiting: the job's dedupe key keeps it to one
        assertEquals(emptyList<RunTrigger>(), mine(runBlocking { scheduler.tick(now) }, definition))
        assertEquals("SCHEDULE", run(definition).get("trigger").asString())
        assertEquals("SUCCEEDED", drain(definition).get("status").asString())
        assertEquals(emptyList<RunTrigger>(), mine(runBlocking { scheduler.tick(Instant.now()) }, definition))
        assertEquals(listOf(RunTrigger.SCHEDULE), mine(runBlocking { scheduler.tick(Instant.now().plusSeconds(61)) }, definition))
        drain(definition)
        val runs = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/runs", tenant.admin, null, 200)
        assertEquals(2, runs.size(), runs.toString())
    }

    @Test
    fun `a reconcile interval starts reconcile runs, and paused definitions are left alone`() {
        val pair = Pair()
        val definition = define(pair, scheduleSeconds = 60, reconcileSeconds = 300)
        assertEquals(listOf(RunTrigger.RECONCILE), mine(runBlocking { scheduler.tick(Instant.now()) }, definition))
        assertEquals(emptyList<RunTrigger>(), mine(runBlocking { scheduler.tick(Instant.now()) }, definition))
        drain(definition)
        // the reconcile does not count as the incremental run: that one is still due
        assertEquals(listOf(RunTrigger.SCHEDULE), mine(runBlocking { scheduler.tick(Instant.now()) }, definition))
        drain(definition)
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/deactivate", tenant.admin, null, 200)
        assertEquals(emptyList<RunTrigger>(), mine(runBlocking { scheduler.tick(Instant.now().plusSeconds(3600)) }, definition))
    }

    @Test
    fun `a reconcile finds a record deleted behind the feed's back and flags it`() {
        val pair = Pair()
        memA.put(pair.a, res, mapOf("name" to text("Ana")))
        val definition = define(pair)
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        memB.vanish(pair.b, res, inB)
        val quiet = sync(definition)
        assertEquals(0, quiet.get("conflicts").asInt(), "the feed says nothing: an incremental run cannot see it")
        val reconcile = sync(definition, "RECONCILE")
        assertEquals(1, reconcile.get("missing").asInt(), reconcile.toString())
        assertEquals(1, reconcile.get("conflicts").asInt(), reconcile.toString())
        val conflicts = call(client, HttpMethod.GET, "/api/integrations/conflicts?definitionId=$definition&status=OPEN", tenant.admin, null, 200)
        assertEquals("DELETED", conflicts.get(0).get("kind").asString())
        assertEquals(1, memA.records(pair.a, res).size, "FLAG never deletes")
    }

    @Test
    fun `a reconcile propagates a silent deletion when the policy says so`() {
        val pair = Pair()
        memA.put(pair.a, res, mapOf("name" to text("Bo")))
        val definition = define(pair, deletePolicy = "PROPAGATE")
        sync(definition)
        memB.vanish(pair.b, res, memB.records(pair.b, res).single().id)
        val reconcile = sync(definition, "RECONCILE")
        assertEquals(1, reconcile.get("missing").asInt(), reconcile.toString())
        assertTrue(memA.records(pair.a, res).isEmpty())
        assertEquals(0L, status(definition).get("links").get("LINKED").asLong())
    }

    @Test
    fun `a reconcile merges values that drifted with no change seen`() {
        val pair = Pair()
        val ana = memA.put(pair.a, res, mapOf("name" to text("Ana"), "email" to text("ana@x.test")))
        val definition = define(pair)
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        memB.silentPut(pair.b, res, inB, mapOf("email" to text("ana@drift.test")))
        assertEquals(0, sync(definition).get("updated").asInt())
        val reconcile = sync(definition, "RECONCILE")
        assertEquals(1, reconcile.get("diverged").asInt(), reconcile.toString())
        assertEquals(text("ana@drift.test"), memA.records(pair.a, res).first { it.id == ana }.fields["email"])
        // in step again: the next reconcile finds nothing
        val again = sync(definition, "RECONCILE")
        assertEquals(0, again.get("diverged").asInt() + again.get("missing").asInt() + again.get("updated").asInt(), again.toString())
    }

    @Test
    fun `a reconcile sees drift on a side that does not flow, and keeps it until the source changes that field`() {
        val pair = Pair()
        val cy = memA.put(pair.a, res, mapOf("name" to text("Cy"), "email" to text("cy@x.test")))
        val definition = define(pair, direction = "A_TO_B")
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        memB.silentPut(pair.b, res, inB, mapOf("email" to text("cy@hand-edited.test")))
        val reconcile = sync(definition, "RECONCILE")
        assertEquals(1, reconcile.get("diverged").asInt(), reconcile.toString())
        // never a silent overwrite: the edit in B stays, pending, until A changes that field
        assertEquals(text("cy@hand-edited.test"), memB.records(pair.b, res).single().fields["email"])
        assertEquals(0, sync(definition, "RECONCILE").get("diverged").asInt(), "seen once, not again")
        memA.put(pair.a, res, mapOf("email" to text("cy@new.test")), cy)
        sync(definition)
        assertEquals(text("cy@new.test"), memB.records(pair.b, res).single().fields["email"])
    }

    @Test
    fun `a refused checkpoint is reset and that side is read again`() {
        val pair = Pair()
        memA.put(pair.a, res, mapOf("name" to text("Di")))
        val definition = define(pair)
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        memB.vanish(pair.b, res, inB)
        memA.refuseCheckpoints = true
        memB.refuseCheckpoints = true
        val run =
            try {
                sync(definition)
            } finally {
                memA.refuseCheckpoints = false
                memB.refuseCheckpoints = false
            }
        assertEquals("SUCCEEDED", run.get("status").asString(), run.toString())
        assertEquals(2, run.get("cursorResets").asInt(), run.toString())
        assertEquals(1, run.get("missing").asInt(), "a side read whole again is checked like a reconcile")
        assertEquals(1, memA.records(pair.a, res).size)
    }

    @Test
    fun `the status tells how the definition is doing, within the tenant`() {
        val pair = Pair()
        val ana = memA.put(pair.a, res, mapOf("name" to text("Ana")))
        val definition = define(pair, scheduleSeconds = 120, reconcileSeconds = 600)
        val before = status(definition)
        assertEquals(definition, before.get("definitionId").asString())
        assertEquals("ACTIVE", before.get("status").asString())
        assertTrue(before.get("lastRun").isNull)
        assertFalse(before.get("queuedOrRunning").asBoolean())
        assertNotNull(before.get("nextScheduledAt").asString())
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        memA.put(pair.a, res, mapOf("name" to text("Ana A")), ana)
        memB.put(pair.b, res, mapOf("name" to text("Ana B")), inB)
        sync(definition)
        val after = status(definition, tenant.member())
        assertEquals("SUCCEEDED", after.get("lastRun").get("status").asString())
        assertNotNull(after.get("lastSuccessAt").asString())
        assertTrue(after.get("lastError").isNull)
        assertEquals(1L, after.get("pendingConflicts").asLong())
        assertEquals(1L, after.get("links").get("CONFLICT").asLong())
        assertEquals(0L, after.get("links").get("ORPHANED").asLong())
        assertEquals(
            setOf("A", "B"),
            (0 until after.get("cursors").size())
                .map {
                    after
                        .get("cursors")
                        .get(it)
                        .get("side")
                        .asString()
                }.toSet()
        )
        val last = Instant.parse(after.get("lastRun").get("startedAt").asString())
        assertEquals(last.plusSeconds(120), Instant.parse(after.get("nextScheduledAt").asString()))
        assertTrue(after.get("errorKind").isNull)
        call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/status", other.admin, null, 404)
        call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/status", null, null, 401)
    }
}
