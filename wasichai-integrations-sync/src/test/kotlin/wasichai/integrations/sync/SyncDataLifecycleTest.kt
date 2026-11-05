package wasichai.integrations.sync

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.JobRunner
import wasichai.integrations.Retention
import wasichai.integrations.UsageMeter
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sync.SyncTenant.Companion.call
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

// issue #46 in the sync module: what a connection's export and erasure take from sync, the purge of runs and resolved
// conflicts, and the records-synced counter
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SyncDataLifecycleTest : SyncIntegrationTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var memA: MemConnector

    @Autowired
    private lateinit var memB: MemConnector

    @Autowired
    private lateinit var usage: UsageMeter

    @Autowired
    private lateinit var retention: Retention

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val tenant by lazy { SyncTenant.provision(client) }
    private val other by lazy { SyncTenant.provision(client) }
    private val res = "contacts"
    private val m by lazy { schemas.metadata }

    private inner class Pair(
        val t: SyncTenant = tenant,
        val a: String = t.connect("mem-a"),
        val b: String = t.connect("mem-b")
    )

    private fun define(pair: Pair): String {
        val body =
            mapOf(
                "name" to "Contacts ${SyncTenant.unique()}",
                "a" to mapOf("connectionId" to pair.a, "resource" to res),
                "b" to mapOf("connectionId" to pair.b, "resource" to res),
                "direction" to "BIDIRECTIONAL",
                "deletePolicy" to "FLAG",
                "mappings" to listOf("name", "email").map { mapOf("aField" to it, "bField" to it, "owner" to "BOTH") }
            )
        val id = call(client, HttpMethod.POST, "/api/integrations/sync-definitions", pair.t.admin, body, 201).get("id").asString()
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$id/activate", pair.t.admin, null, 200)
        return id
    }

    private fun sync(
        definition: String,
        t: SyncTenant = tenant
    ): JsonNode {
        call(client, HttpMethod.POST, "/api/integrations/sync-definitions/$definition/run", t.admin, mapOf("kind" to "INCREMENTAL"), 202)
        repeat(60) {
            runBlocking { runner.drainOnce() }
            val last = call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition/runs", t.admin, null, 200).get(0)
            if (last.get("status").asString() !in setOf("QUEUED", "RUNNING")) return last
            Thread.sleep(20)
        }
        throw AssertionError("the run of $definition did not finish")
    }

    private fun count(
        sql: String,
        id: String
    ): Long =
        runBlocking {
            db
                .sql(sql)
                .bind("id", UUID.fromString(id))
                .map { row, _ -> row.get(0, Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    private fun links(definition: String) = count("SELECT count(*) FROM $m.integration_record_links WHERE definition_id = :id", definition)

    private fun text(s: String) = FieldValue.Text(s)

    @Test
    fun `a sync run counts the records it wrote for its tenant`() {
        val pair = Pair()
        memA.put(pair.a, res, mapOf("name" to text("Ana")))
        memA.put(pair.a, res, mapOf("name" to text("Bo")))
        val definition = define(pair)
        runBlocking { usage.flush() }
        val before = synced(tenant.admin)
        assertEquals(2, sync(definition).get("created").asInt())
        runBlocking { usage.flush() }
        assertEquals(before + 2, synced(tenant.member()))
        assertEquals(0L, synced(SyncTenant.provision(client).admin), "a tenant that synced nothing")
    }

    private fun synced(token: String): Long {
        val today = LocalDate.now(ZoneOffset.UTC)
        val rows =
            call(client, HttpMethod.GET, "/api/integrations/usage?metric=${SyncEngine.RECORDS_SYNCED}&from=${today.minusDays(1)}&to=$today", token, null, 200)
        return (0 until rows.size()).sumOf { rows.get(it).get("count").asLong() }
    }

    @Test
    fun `an export of either connection holds the definition, its links, snapshots and runs`() {
        val pair = Pair()
        memA.put(pair.a, res, mapOf("name" to text("Cy")))
        val definition = define(pair)
        sync(definition)
        for (connection in listOf(pair.a, pair.b)) {
            val sections = call(client, HttpMethod.GET, "/api/integrations/connections/$connection/export", tenant.admin, null, 200).get("sections")
            assertEquals(
                definition,
                sections
                    .get("syncDefinitions")
                    .get(0)
                    .get("id")
                    .asString()
            )
            assertEquals(1, sections.get("recordLinks").size())
            assertEquals(1, sections.get("recordSnapshots").size())
            assertEquals(1, sections.get("syncRuns").size())
            assertTrue(sections.get("syncCursors").size() >= 1)
            assertTrue(sections.get("recordSnapshots").toString().contains("Cy"))
        }
        call(client, HttpMethod.GET, "/api/integrations/connections/${pair.a}/export", other.admin, null, 404)
    }

    @Test
    fun `erasing a record forgets its side, the pair stays ORPHANED and is neither re-created nor re-paired`() {
        val pair = Pair()
        val ana = memA.put(pair.a, res, mapOf("name" to text("Ana")))
        val bo = memA.put(pair.a, res, mapOf("name" to text("Bo")))
        val definition = define(pair)
        sync(definition)
        val anaInB = memB.records(pair.b, res).first { (it.fields["name"] as FieldValue.Text).value == "Ana" }.id
        val erased =
            call(
                client,
                HttpMethod.POST,
                "/api/integrations/connections/${pair.a}/erase-record",
                tenant.admin,
                mapOf("resource" to res, "recordId" to ana),
                200
            )
        assertEquals(1, erased.get("erased").get("integration_record_links").asInt(), erased.toString())
        assertEquals(1, erased.get("erased").get("integration_record_snapshots").asInt())
        assertEquals(0, count("SELECT count(*) FROM $m.integration_record_links WHERE definition_id = :id AND a_id = '$ana'", definition))
        assertEquals(1, count("SELECT count(*) FROM $m.integration_record_links WHERE definition_id = :id AND a_id IS NULL AND state = 'ORPHANED'", definition))
        assertEquals(1, count("SELECT count(*) FROM $m.integration_record_links WHERE definition_id = :id AND a_id = '$bo' AND state = 'LINKED'", definition))
        // the record was deleted at the provider (the person's request); its pair changes and nothing comes back
        memA.vanish(pair.a, res, ana)
        memB.put(pair.b, res, mapOf("name" to text("Ana B")), anaInB)
        val after = sync(definition)
        assertEquals(0, after.get("created").asInt(), after.toString())
        assertEquals(1, memA.records(pair.a, res).size)
        assertEquals(2, memB.records(pair.b, res).size, "the paired record is never touched")
        assertEquals(2, links(definition))
    }

    @Test
    fun `erasing a used connection takes every definition on it and its state, and nothing of others`() {
        val pair = Pair()
        memA.put(pair.a, res, mapOf("name" to text("Di")))
        val definition = define(pair)
        sync(definition)
        val kept = Pair()
        memA.put(kept.a, res, mapOf("name" to text("Ed")))
        val keptDefinition = define(kept)
        sync(keptDefinition)
        val elsewhere = Pair(other)
        memA.put(elsewhere.a, res, mapOf("name" to text("Fa")))
        val otherDefinition = define(elsewhere)
        sync(otherDefinition, other)

        call(client, HttpMethod.DELETE, "/api/integrations/connections/${pair.b}", tenant.admin, null, 409)
        call(client, HttpMethod.POST, "/api/integrations/connections/${pair.b}/erase", tenant.member(), null, 403)
        val erased = call(client, HttpMethod.POST, "/api/integrations/connections/${pair.b}/erase", tenant.admin, null, 200).get("erased")
        assertEquals(1, erased.get("integration_sync_definitions").asInt(), erased.toString())
        assertEquals(1, erased.get("integration_record_links").asInt())
        assertEquals(1, erased.get("integration_record_snapshots").asInt())
        assertEquals(1, erased.get("integration_sync_runs").asInt())
        assertTrue(erased.get("integration_sync_cursors").asInt() >= 1)
        call(client, HttpMethod.GET, "/api/integrations/sync-definitions/$definition", tenant.admin, null, 404)
        assertEquals(0, links(definition))
        assertEquals(0, count("SELECT count(*) FROM $m.integration_sync_runs WHERE definition_id = :id", definition))
        // the other side's connection stays, unused now
        call(client, HttpMethod.GET, "/api/integrations/connections/${pair.a}", tenant.admin, null, 200)
        call(client, HttpMethod.DELETE, "/api/integrations/connections/${pair.a}", tenant.admin, null, 204)
        assertEquals(1, links(keptDefinition))
        assertEquals(1, links(otherDefinition))
    }

    @Test
    fun `retention purges old finished runs and resolved conflicts, keeps the latest run and open conflicts`() {
        val pair = Pair()
        val definition = define(pair)
        repeat(3) { sync(definition) }
        val old = Instant.now().minus(Duration.ofDays(200))
        runBlocking {
            db
                .sql("UPDATE $m.integration_sync_runs SET finished_at = :old WHERE definition_id = :id")
                .bind("old", old.atOffset(ZoneOffset.UTC))
                .bind("id", UUID.fromString(definition))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        val otherPair = Pair(other)
        val otherDefinition = define(otherPair)
        repeat(2) { sync(otherDefinition, other) }
        val resolvedOld = conflict(tenant.organizationId, definition, "RESOLVED", old)
        val openOld = conflict(tenant.organizationId, definition, "OPEN", null, old)
        val resolvedRecent = conflict(other.organizationId, otherDefinition, "RESOLVED", Instant.now())

        val purged = runBlocking { retention.purge(Instant.now()) }
        assertTrue(purged.getValue("sync.runs") >= 2, purged.toString())
        // the latest run of the definition stays: the scheduler and the status read it
        assertEquals(1, count("SELECT count(*) FROM $m.integration_sync_runs WHERE definition_id = :id", definition))
        assertEquals(2, count("SELECT count(*) FROM $m.integration_sync_runs WHERE definition_id = :id", otherDefinition))
        assertEquals(0, count("SELECT count(*) FROM $m.integration_sync_conflicts WHERE id = :id", resolvedOld))
        assertEquals(1, count("SELECT count(*) FROM $m.integration_sync_conflicts WHERE id = :id", openOld))
        assertEquals(1, count("SELECT count(*) FROM $m.integration_sync_conflicts WHERE id = :id", resolvedRecent))
        assertNull(purged["unknown"])
        assertFalse(purged.isEmpty())
    }

    private fun conflict(
        org: UUID,
        definition: String,
        status: String,
        resolvedAt: Instant?,
        createdAt: Instant = Instant.now()
    ): String =
        runBlocking {
            db
                .sql(
                    "INSERT INTO $m.integration_sync_conflicts (organization_id, definition_id, kind, status, created_at, resolved_at) " +
                        "VALUES (:org, :definition, 'FIELDS', :status, :created, :resolved) RETURNING id"
                ).bind("org", org)
                .bind("definition", UUID.fromString(definition))
                .bind("status", status)
                .bind("created", createdAt.atOffset(ZoneOffset.UTC))
                .let { s ->
                    resolvedAt?.let { s.bind("resolved", it.atOffset(ZoneOffset.UTC)) } ?: s.bindNull("resolved", java.time.OffsetDateTime::class.java)
                }.map { row, _ -> row.get("id", UUID::class.java)!!.toString() }
                .one()
                .awaitSingle()
        }
}
