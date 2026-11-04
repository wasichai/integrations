package wasichai.integrations.sync

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.JobRunner
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sync.SyncTenant.Companion.call
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncEngineTest : SyncIntegrationTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var memA: MemConnector

    @Autowired
    private lateinit var memB: MemConnector

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val tenant by lazy { SyncTenant.provision(client) }
    private val other by lazy { SyncTenant.provision(client) }
    private val res = "contacts"

    private inner class Pair(
        val a: String = tenant.connect("mem-a"),
        val b: String = tenant.connect("mem-b")
    )

    private fun mapping(
        field: String,
        owner: String = "BOTH",
        strategy: String = "MANUAL"
    ) = mapOf("aField" to field, "bField" to field, "owner" to owner, "conflictStrategy" to strategy)

    private fun define(
        pair: Pair,
        mappings: List<Map<String, Any?>> = listOf(mapping("name"), mapping("email")),
        direction: String = "BIDIRECTIONAL",
        deletePolicy: String = "FLAG",
        matchOn: Map<String, String>? = null
    ): String {
        val body =
            mutableMapOf(
                "name" to "Contacts ${SyncTenant.unique()}",
                "a" to mapOf("connectionId" to pair.a, "resource" to res),
                "b" to mapOf("connectionId" to pair.b, "resource" to res),
                "direction" to direction,
                "deletePolicy" to deletePolicy,
                "mappings" to mappings
            )
        matchOn?.let { body["matchOn"] = it }
        val id = call(client, HttpMethod.POST, "/api/integrations/sync-definitions", tenant.admin, body, 201).get("id").asString()
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/activate", tenant.admin, null, 200)
        return id
    }

    // runs once and drains until the run is over; returns the run
    private fun sync(definition: String): JsonNode {
        val runId = call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", tenant.admin, null, 202).get("runId").asString()
        repeat(60) {
            runBlocking { runner.drainOnce() }
            val runs = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/runs", tenant.admin, null, 200)
            val run = (0 until runs.size()).map { runs.get(it) }.first { it.get("id").asString() == runId }
            if (run.get("status").asString() !in setOf("QUEUED", "RUNNING")) return run
            Thread.sleep(20)
        }
        throw AssertionError("run $runId did not finish")
    }

    private fun text(s: String) = FieldValue.Text(s)

    private fun field(
        mem: MemConnector,
        connection: String,
        id: String,
        name: String
    ) = mem.records(connection, res).first { it.id == id }.fields[name]

    private fun conflicts(definition: String): List<JsonNode> {
        val list = call(client, HttpMethod.GET, "/api/integrations/conflicts?definitionId=$definition&status=OPEN", tenant.admin, null, 200)
        return (0 until list.size()).map { list.get(it) }
    }

    @Test
    fun `initial sync creates the records on the other side, and a second run writes nothing`() {
        val pair = Pair()
        memA.put(pair.a, res, mapOf("name" to text("Ana"), "email" to text("ana@x.test"), "amount" to FieldValue.Number(BigDecimal.TEN)))
        memA.put(pair.a, res, mapOf("name" to text("Bo"), "email" to text("bo@x.test")))
        val definition = define(pair)
        val first = sync(definition)
        assertEquals("SUCCEEDED", first.get("status").asString(), first.toString())
        assertEquals(2, first.get("created").asInt())
        val inB = memB.records(pair.b, res)
        assertEquals(setOf("Ana", "Bo"), inB.map { (it.fields["name"] as FieldValue.Text).value }.toSet())
        assertTrue(inB.none { it.fields.containsKey("amount") }, "only mapped fields travel")
        val writesA = memA.writes(pair.a)
        val writesB = memB.writes(pair.b)
        val second = sync(definition)
        assertEquals(0, second.get("created").asInt() + second.get("updated").asInt(), second.toString())
        assertEquals(writesA, memA.writes(pair.a), "our creates in B are not echoed back into A")
        assertEquals(writesB, memB.writes(pair.b))
    }

    @Test
    fun `updates flow both ways, once`() {
        val pair = Pair()
        val ana = memA.put(pair.a, res, mapOf("name" to text("Ana"), "email" to text("ana@x.test")))
        val definition = define(pair)
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        memB.put(pair.b, res, mapOf("email" to text("ana@new.test")), inB)
        assertEquals(1, sync(definition).get("updated").asInt())
        assertEquals(text("ana@new.test"), field(memA, pair.a, ana, "email"))
        memA.put(pair.a, res, mapOf("name" to text("Ana Maria")), ana)
        sync(definition)
        assertEquals(text("Ana Maria"), field(memB, pair.b, inB, "name"))
        val writesA = memA.writes(pair.a)
        val writesB = memB.writes(pair.b)
        val quiet = sync(definition)
        assertEquals(0, quiet.get("updated").asInt(), quiet.toString())
        assertEquals(writesA, memA.writes(pair.a))
        assertEquals(writesB, memB.writes(pair.b))
    }

    @Test
    fun `a run already waiting is not queued twice`() {
        val pair = Pair()
        val definition = define(pair)
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", tenant.admin, null, 202)
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", tenant.admin, null, 409)
        repeat(10) { runBlocking { runner.drainOnce() } }
    }

    @Test
    fun `a field changed on both sides is a conflict, nothing is overwritten, and a resolution applies`() {
        val pair = Pair()
        val ana = memA.put(pair.a, res, mapOf("name" to text("Ana"), "email" to text("ana@x.test")))
        val definition = define(pair)
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        memA.put(pair.a, res, mapOf("name" to text("Ana from A")), ana)
        memB.put(pair.b, res, mapOf("name" to text("Ana from B")), inB)
        val run = sync(definition)
        assertEquals(1, run.get("conflicts").asInt(), run.toString())
        assertEquals(text("Ana from A"), field(memA, pair.a, ana, "name"))
        assertEquals(text("Ana from B"), field(memB, pair.b, inB, "name"))
        val conflict = conflicts(definition).single()
        assertEquals("FIELDS", conflict.get("kind").asString())
        assertEquals(
            "name->name",
            conflict
                .get("fields")
                .get(0)
                .get("key")
                .asString()
        )
        // held: another run leaves it alone
        sync(definition)
        assertEquals(text("Ana from B"), field(memB, pair.b, inB, "name"))
        call(client, HttpMethod.POST, "/api/integrations/conflicts/${conflict.get("id").asString()}/resolve", tenant.member(), mapOf("choice" to "A"), 403)
        call(client, HttpMethod.POST, "/api/integrations/conflicts/${conflict.get("id").asString()}/resolve", other.admin, mapOf("choice" to "A"), 404)
        val resolved =
            call(client, HttpMethod.POST, "/api/integrations/conflicts/${conflict.get("id").asString()}/resolve", tenant.admin, mapOf("choice" to "A"), 200)
        assertEquals("RESOLVED", resolved.get("status").asString())
        assertEquals(text("Ana from A"), field(memB, pair.b, inB, "name"))
        call(client, HttpMethod.POST, "/api/integrations/conflicts/${conflict.get("id").asString()}/resolve", tenant.admin, mapOf("choice" to "A"), 409)
        val after = sync(definition)
        assertEquals(0, after.get("conflicts").asInt(), after.toString())
        assertTrue(conflicts(definition).isEmpty())
    }

    @Test
    fun `a write that keeps losing the race is held as a conflict a person can resolve`() {
        val pair = Pair()
        val ana = memA.put(pair.a, res, mapOf("name" to text("Ana"), "email" to text("ana@x.test")))
        val definition = define(pair)
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        memA.put(pair.a, res, mapOf("name" to text("Ana from A")), ana)
        memB.conflictNextUpdates(2)
        val run = sync(definition)
        assertEquals(1, run.get("conflicts").asInt(), run.toString())
        assertEquals(text("Ana"), field(memB, pair.b, inB, "name"))
        val conflict = conflicts(definition).single()
        val held = conflict.get("fields").get(0)
        assertEquals("name->name", held.get("key").asString())
        assertEquals("name", held.get("aField").asString())
        assertEquals("name", held.get("bField").asString())
        val resolved =
            call(client, HttpMethod.POST, "/api/integrations/conflicts/${conflict.get("id").asString()}/resolve", tenant.admin, mapOf("choice" to "A"), 200)
        assertEquals("RESOLVED", resolved.get("status").asString())
        assertEquals(text("Ana from A"), field(memB, pair.b, inB, "name"))
        val after = sync(definition)
        assertEquals(0, after.get("conflicts").asInt(), after.toString())
    }

    @Test
    fun `a per-field resolution brings B's values back through the inverse transforms`() {
        val pair = Pair()
        val tiers = mapOf("type" to "enumMap", "map" to mapOf("gold" to "GOLD", "silver" to "SILVER", "bronze" to "BRONZE"))
        val dates = mapOf("type" to "dateTime", "zone" to "UTC", "a" to "DATE", "b" to "DATETIME")
        val ana =
            memA.put(
                pair.a,
                res,
                mapOf("name" to text("Ana"), "tier" to FieldValue.Choice("gold"), "since" to FieldValue.Date(LocalDate.parse("2026-01-01")))
            )
        val definition =
            define(
                pair,
                listOf(
                    mapping("name"),
                    mapOf("aField" to "tier", "bField" to "tier", "transform" to tiers),
                    mapOf("aField" to "since", "bField" to "seenAt", "transform" to dates)
                )
            )
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        assertEquals(FieldValue.Choice("GOLD"), field(memB, pair.b, inB, "tier"))
        assertEquals(FieldValue.DateTime(Instant.parse("2026-01-01T00:00:00Z")), field(memB, pair.b, inB, "seenAt"))
        memA.put(
            pair.a,
            res,
            mapOf("name" to text("Ana A"), "tier" to FieldValue.Choice("silver"), "since" to FieldValue.Date(LocalDate.parse("2026-02-01"))),
            ana
        )
        memB.put(
            pair.b,
            res,
            mapOf("name" to text("Ana B"), "tier" to FieldValue.Choice("BRONZE"), "seenAt" to FieldValue.DateTime(Instant.parse("2026-03-01T10:00:00Z"))),
            inB
        )
        assertEquals(1, sync(definition).get("conflicts").asInt())
        val conflict = conflicts(definition).single().get("id").asString()
        val picks = mapOf("name->name" to "A", "tier->tier" to "B", "since->seenAt" to "B")
        // a pick that is neither side names the field
        val problem =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/conflicts/$conflict/resolve",
                tenant.admin,
                mapOf("choice" to "CUSTOM", "fields" to picks + ("tier->tier" to "C")),
                400
            )
        assertEquals(
            "fields.tier->tier",
            problem
                .get("errors")
                .get(0)
                .get("field")
                .asString(),
            problem.toString()
        )
        val resolved =
            call(client, HttpMethod.POST, "/api/integrations/conflicts/$conflict/resolve", tenant.admin, mapOf("choice" to "CUSTOM", "fields" to picks), 200)
        assertEquals("RESOLVED", resolved.get("status").asString())
        assertEquals(text("Ana A"), field(memB, pair.b, inB, "name"))
        assertEquals(FieldValue.Choice("bronze"), field(memA, pair.a, ana, "tier"))
        assertEquals(FieldValue.Choice("BRONZE"), field(memB, pair.b, inB, "tier"))
        assertEquals(FieldValue.Date(LocalDate.parse("2026-03-01")), field(memA, pair.a, ana, "since"))
        // B's date-time is the one A now holds, as B stores it
        assertEquals(FieldValue.DateTime(Instant.parse("2026-03-01T00:00:00Z")), field(memB, pair.b, inB, "seenAt"))
        val after = sync(definition)
        assertEquals(0, after.get("conflicts").asInt(), after.toString())
    }

    @Test
    fun `a custom resolution writes the given value to both sides`() {
        val pair = Pair()
        val ana = memA.put(pair.a, res, mapOf("name" to text("Ana")))
        val definition = define(pair, listOf(mapping("name")))
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        memA.put(pair.a, res, mapOf("name" to text("A")), ana)
        memB.put(pair.b, res, mapOf("name" to text("B")), inB)
        sync(definition)
        val id = conflicts(definition).single().get("id").asString()
        call(client, HttpMethod.POST, "/api/integrations/conflicts/$id/resolve", tenant.admin, mapOf("choice" to "CUSTOM"), 400)
        call(
            client,
            HttpMethod.POST,
            "/api/integrations/conflicts/$id/resolve",
            tenant.admin,
            mapOf("choice" to "CUSTOM", "values" to mapOf("name" to "Ana C")),
            200
        )
        assertEquals(text("Ana C"), field(memA, pair.a, ana, "name"))
        assertEquals(text("Ana C"), field(memB, pair.b, inB, "name"))
    }

    @Test
    fun `strategies and owners settle clashes without a person`() {
        val pair = Pair()
        val ana = memA.put(pair.a, res, mapOf("name" to text("Ana"), "email" to text("e@x.test")))
        val definition = define(pair, listOf(mapping("name", strategy = "B_WINS"), mapping("email", owner = "A")))
        sync(definition)
        val inB = memB.records(pair.b, res).single().id
        memA.put(pair.a, res, mapOf("name" to text("A name")), ana)
        memB.put(pair.b, res, mapOf("name" to text("B name"), "email" to text("b-edit@x.test")), inB)
        val run = sync(definition)
        assertEquals(0, run.get("conflicts").asInt(), run.toString())
        assertEquals(text("B name"), field(memA, pair.a, ana, "name"))
        // owned by A: B's edit never reaches A
        assertEquals(text("e@x.test"), field(memA, pair.a, ana, "email"))
    }

    @Test
    fun `deletes follow the definition's policy`() {
        val propagate = Pair()
        val gone = memA.put(propagate.a, res, mapOf("name" to text("Gone")))
        val propagating = define(propagate, deletePolicy = "PROPAGATE")
        sync(propagating)
        memA.remove(propagate.a, res, gone)
        sync(propagating)
        assertTrue(memB.records(propagate.b, res).isEmpty())

        val flag = Pair()
        val kept = memA.put(flag.a, res, mapOf("name" to text("Kept")))
        val flagging = define(flag, deletePolicy = "FLAG")
        sync(flagging)
        memA.remove(flag.a, res, kept)
        assertEquals(1, sync(flagging).get("conflicts").asInt())
        assertEquals(1, memB.records(flag.b, res).size, "flag never deletes")
        val conflict = conflicts(flagging).single()
        assertEquals("DELETED", conflict.get("kind").asString())
        call(client, HttpMethod.POST, "/api/integrations/conflicts/${conflict.get("id").asString()}/resolve", tenant.admin, mapOf("choice" to "A"), 200)
    }

    @Test
    fun `match on links existing records instead of duplicating them`() {
        val pair = Pair()
        memA.put(pair.a, res, mapOf("name" to text("Ana"), "email" to text("ana@x.test")))
        val existing = memB.put(pair.b, res, mapOf("name" to text("Ana (old)"), "email" to text("ana@x.test")))
        val definition = define(pair, matchOn = mapOf("aField" to "email", "bField" to "email"))
        val run = sync(definition)
        assertEquals(0, run.get("created").asInt(), run.toString())
        assertEquals(1, memB.records(pair.b, res).size)
        assertEquals(text("Ana"), field(memB, pair.b, existing, "name"))
    }

    @Test
    fun `a create left pending by a crash is marked ORPHANED, never repeated`() {
        val pair = Pair()
        val definition = define(pair)
        val ana = memA.put(pair.a, res, mapOf("name" to text("Ana")))
        runBlocking {
            db
                .sql(
                    "INSERT INTO ${schemas.metadata}.integration_record_links (organization_id, definition_id, a_id, state) " +
                        "VALUES (:org, :definition, :a, 'PENDING_CREATE')"
                ).bind("org", tenant.organizationId)
                .bind("definition", java.util.UUID.fromString(definition))
                .bind("a", ana)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val run = sync(definition)
        assertEquals("PARTIAL", run.get("status").asString(), run.toString())
        assertEquals(1, run.get("failed").asInt())
        assertTrue(memB.records(pair.b, res).isEmpty(), "no blind second create")
        // issue #32: an operator sees it
        assertEquals("ORPHANED_CREATE", conflicts(definition).single().get("kind").asString())
    }

    @Test
    fun `runs and conflicts stay within the tenant`() {
        val pair = Pair()
        val definition = define(pair)
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", other.admin, null, 404)
        call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/runs", other.admin, null, 404)
        assertEquals(0, call(client, HttpMethod.GET, "/api/integrations/conflicts", other.admin, null, 200).size())
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", tenant.member(), null, 403)
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/deactivate", tenant.admin, null, 200)
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", tenant.admin, null, 409)
    }
}
