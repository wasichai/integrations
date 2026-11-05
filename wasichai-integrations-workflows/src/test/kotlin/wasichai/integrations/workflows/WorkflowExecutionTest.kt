package wasichai.integrations.workflows

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.reactive.awaitSingle
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
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.EventJobs
import wasichai.integrations.EventRepository
import wasichai.integrations.EventSource
import wasichai.integrations.EventStatus
import wasichai.integrations.IntegrationEvent
import wasichai.integrations.IntegrationsMeters
import wasichai.integrations.JobRepository
import wasichai.integrations.JobRunner
import wasichai.integrations.Transactions
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.workflows.WorkflowTenant.Companion.call
import wasichai.integrations.workflows.autoconfigure.WorkflowsProperties
import java.time.Clock
import java.time.Instant
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorkflowExecutionTest : WorkflowsIntegrationTest() {
    @Autowired
    private lateinit var runner: JobRunner

    @Autowired
    private lateinit var memA: MemConnector

    @Autowired
    private lateinit var memU: MemUpserter

    @Autowired
    private lateinit var memM: MemMessenger

    @Autowired
    private lateinit var memN: MemMessenger

    @Autowired
    private lateinit var events: EventRepository

    @Autowired
    private lateinit var jobs: JobRepository

    @Autowired
    private lateinit var executions: ExecutionRepository

    @Autowired
    private lateinit var engine: WorkflowEngine

    @Autowired
    private lateinit var scheduler: WorkflowScheduler

    @Autowired
    private lateinit var clusterLock: ClusterLock

    @Autowired
    private lateinit var integrationsTransactions: Transactions

    @Autowired
    private lateinit var meters: IntegrationsMeters

    @Autowired
    private lateinit var db: DatabaseClient

    @Autowired
    private lateinit var schemas: WasichaiSchemas

    private val json = JsonMapper.builder().build()
    private val tenant by lazy { WorkflowTenant.provision(client) }
    private val other by lazy { WorkflowTenant.provision(client) }
    private val base = "/api/integrations/workflows"
    private val terminal = setOf("SUCCEEDED", "FAILED", "CANCELLED")

    private fun manual(next: String) = mapOf("type" to "manual", "next" to next)

    private fun create(
        id: String,
        connection: String,
        name: Any,
        next: String,
        onError: String? = null
    ) = mapOf(
        "id" to id,
        "type" to "action",
        "connectionId" to connection,
        "write" to mapOf("resource" to "contacts", "operation" to "create"),
        "input" to mapOf("name" to (if (name is String) mapOf("value" to name) else name)),
        "next" to next,
        "onError" to onError
    )

    private fun send(
        id: String,
        connection: String,
        text: Any,
        next: String,
        onError: String? = null
    ) = mapOf(
        "id" to id,
        "type" to "action",
        "connectionId" to connection,
        "action" to "send_message",
        "input" to mapOf("text" to (if (text is String) mapOf("value" to text) else text)),
        "next" to next,
        "onError" to onError
    )

    private fun end(id: String = "done") = mapOf("id" to id, "type" to "end")

    // a published workflow's id
    private fun publish(
        trigger: Map<String, Any?>,
        vararg steps: Map<String, Any?>
    ): String {
        val graph = mapOf("trigger" to trigger, "steps" to steps.toList())
        val id =
            call(
                client,
                HttpMethod.POST,
                base,
                tenant.admin,
                mapOf("name" to "Run ${WorkflowTenant.unique()}", "graph" to graph),
                201
            ).get("id").asString()
        call(client, HttpMethod.POST, "$base/$id/publish", tenant.admin, null, 201)
        return id
    }

    private fun run(
        workflow: String,
        payload: Map<String, Any?> = emptyMap()
    ): String = call(client, HttpMethod.POST, "$base/$workflow/run", tenant.admin, mapOf("payload" to payload), 202).get("id").asString()

    private fun detail(execution: String): JsonNode = call(client, HttpMethod.GET, "$base/executions/$execution", tenant.admin, null, 200)

    private fun status(detail: JsonNode) = detail.get("execution").get("status").asString()

    // drives the job queue (its worker is off in tests) until the run is where the test wants it
    private fun drain(
        execution: String,
        until: (JsonNode) -> Boolean = { status(it) in terminal }
    ): JsonNode {
        repeat(200) {
            runBlocking { runner.drainOnce() }
            val detail = detail(execution)
            if (until(detail)) return detail
            Thread.sleep(10)
        }
        throw AssertionError("execution $execution never got there: ${detail(execution)}")
    }

    private fun waiting(detail: JsonNode) = status(detail) == "WAITING"

    private fun steps(detail: JsonNode): Map<String, JsonNode> {
        val list = detail.get("steps")
        return (0 until list.size()).associate { list.get(it).get("stepId").asString() to list.get(it) }
    }

    private fun stepStatus(
        detail: JsonNode,
        step: String
    ) = steps(detail)[step]?.get("status")?.asString()

    private fun texts(
        mem: MemMessenger,
        connection: String
    ) = mem.calls(connection).map { it.get("text").asString() }

    private fun names(connection: String) = memA.writes(connection).map { (it.third["name"] as FieldValue.Text).value }

    private fun runs(workflow: String): List<JsonNode> {
        val page = call(client, HttpMethod.GET, "$base/$workflow/executions", tenant.admin, null, 200).get("content")
        return (0 until page.size()).map { page.get(it) }
    }

    @Test
    fun `an event on one provider writes on a second and acts on a third, once per event`() {
        val messenger = tenant.connect("mem-m")
        val crm = tenant.connect("mem-u")
        val chat = tenant.connect("mem-n")
        val workflow =
            publish(
                mapOf("type" to "event", "connectionId" to messenger, "eventType" to "message.created", "next" to "upsert"),
                mapOf(
                    "id" to "upsert",
                    "type" to "action",
                    "connectionId" to crm,
                    "write" to
                        mapOf(
                            "resource" to "contacts",
                            "operation" to "upsert",
                            "externalIdField" to "extId",
                            "externalId" to mapOf("ref" to "trigger.payload.id")
                        ),
                    "input" to mapOf("email" to mapOf("ref" to "trigger.payload.email"), "name" to mapOf("value" to "Ada")),
                    "next" to "notify"
                ),
                send("notify", chat, mapOf("ref" to "steps.upsert.output.id"), "done"),
                end()
            )
        val before = meters.registry.counter(WorkflowEngine.EXECUTIONS, "trigger", "event", "outcome", "succeeded").count()
        val event = event(messenger, mapOf("id" to "m-1", "email" to "ada@x.test"))
        val eventJob = runBlocking { jobs.enqueue(tenant.organizationId, EventJobs.KIND, mapOf("eventId" to event.toString()), "event:$event", Instant.now()) }
        assertNotNull(eventJob)
        runBlocking { runner.drainOnce() }
        val started = runs(workflow)
        assertEquals(1, started.size)
        assertEquals("event", started[0].get("triggerType").asString())
        assertEquals("message.created", started[0].get("trigger").get("eventType").asString())
        val done = drain(started[0].get("id").asString())
        assertEquals("SUCCEEDED", status(done), done.toString())
        assertEquals(listOf("upsert", "notify", "done"), steps(done).keys.toList())
        val upserts = memU.writes(crm)
        assertEquals(1, upserts.size)
        assertEquals(FieldValue.Text("ada@x.test"), upserts[0].third["email"])
        assertEquals(FieldValue.Text("m-1"), upserts[0].third["extId"])
        assertEquals(listOf("m-1"), texts(memN, chat))
        assertEquals(EventStatus.PROCESSED, runBlocking { events.find(tenant.organizationId, event)!!.status })
        assertEquals(before + 1, meters.registry.counter(WorkflowEngine.EXECUTIONS, "trigger", "event", "outcome", "succeeded").count())

        // the event processed again (a replay, a redelivered job) starts nothing new
        call(client, HttpMethod.POST, "/api/integrations/events/$event/replay", tenant.admin, null, 200)
        repeat(3) { runBlocking { runner.drainOnce() } }
        assertEquals(1, runs(workflow).size)
        assertEquals(1, memU.writes(crm).size)
        // an event of another type is not this workflow's
        val ignored = event(messenger, mapOf("id" to "m-2"), type = "message.deleted")
        runBlocking { jobs.enqueue(tenant.organizationId, EventJobs.KIND, mapOf("eventId" to ignored.toString()), "event:$ignored", Instant.now()) }
        runBlocking { runner.drainOnce() }
        assertEquals(1, runs(workflow).size)
        assertEquals(EventStatus.IGNORED, runBlocking { events.find(tenant.organizationId, ignored)!!.status })
    }

    private fun event(
        connection: String,
        payload: Map<String, Any?>,
        type: String = "message.created"
    ): UUID {
        val id = UUID.randomUUID()
        val stored =
            runBlocking {
                events.insert(
                    IntegrationEvent(
                        id = id,
                        organizationId = tenant.organizationId,
                        connectionId = UUID.fromString(connection),
                        connectorId = "mem-m",
                        endpointId = null,
                        providerEventId = id.toString(),
                        dedupeKey = "id:$id",
                        eventType = type,
                        resourceType = null,
                        resourceId = null,
                        occurredAt = null,
                        receivedAt = Instant.now(),
                        source = EventSource.WEBHOOK,
                        schemaVersion = 1,
                        correlationId = UUID.randomUUID().toString(),
                        causationId = null,
                        payload = json.valueToTree(payload),
                        metadata = json.createObjectNode(),
                        status = EventStatus.RECEIVED,
                        processedAt = null,
                        error = null
                    )
                )
            }
        return stored!!
    }

    @Test
    fun `a branch goes one way or the other`() {
        val records = tenant.connect("mem-a")
        val workflow =
            publish(
                manual("check"),
                mapOf(
                    "id" to "check",
                    "type" to "branch",
                    "condition" to mapOf("op" to "gt", "ref" to "trigger.payload.amount", "value" to 100),
                    "then" to "big",
                    "else" to "small"
                ),
                create("big", records, "big", "done"),
                create("small", records, "small", "done"),
                end()
            )
        val big = drain(run(workflow, mapOf("amount" to 500)))
        assertEquals("SUCCEEDED", status(big))
        assertEquals(true, steps(big)["check"]!!.get("output").get("result").asBoolean())
        assertNull(stepStatus(big, "small"), "the branch not taken never runs")
        val small = drain(run(workflow, mapOf("amount" to 5)))
        assertEquals("SUCCEEDED", status(small))
        assertNull(stepStatus(small, "big"))
        assertEquals(listOf("big", "small"), names(records))
    }

    @Test
    fun `a condition that does not hold ends the run`() {
        val records = tenant.connect("mem-a")
        val workflow =
            publish(
                manual("gate"),
                mapOf("id" to "gate", "type" to "condition", "condition" to mapOf("op" to "exists", "ref" to "trigger.payload.email"), "next" to "write"),
                create("write", records, "x", "done"),
                end()
            )
        val stopped = drain(run(workflow))
        assertEquals("SUCCEEDED", status(stopped))
        assertEquals(listOf("gate"), steps(stopped).keys.toList())
        assertEquals(emptyList<String>(), names(records))
    }

    @Test
    fun `parallel branches meet at the join, which runs exactly once`() {
        val records = tenant.connect("mem-a")
        val chat = tenant.connect("mem-m")
        val workflow =
            publish(
                manual("fan"),
                mapOf("id" to "fan", "type" to "parallel", "branches" to listOf("a", "b", "c"), "join" to "notify"),
                create("a", records, "A", "notify"),
                create("b", records, "B", "notify"),
                mapOf("id" to "c", "type" to "delay", "duration" to "PT2S", "next" to "notify"),
                send("notify", chat, mapOf("ref" to "steps.a.output.id"), "done"),
                end()
            )
        val ids = (1..4).map { run(workflow) }
        // the delayed branch arrives last: the join waits for it
        val early = drain(ids[0]) { stepStatus(it, "a") == "SUCCEEDED" && stepStatus(it, "b") == "SUCCEEDED" }
        assertEquals("PENDING", stepStatus(early, "notify"))
        Thread.sleep(2100)
        ids.forEach { id ->
            val done = drain(id)
            assertEquals("SUCCEEDED", status(done), done.toString())
            assertEquals("SUCCEEDED", stepStatus(done, "notify"))
            assertEquals(1, steps(done)["notify"]!!.get("attempt").asInt())
        }
        assertEquals(4, memM.calls(chat).size, "one notification per run")
        assertEquals(8, names(records).size)
        // a late, repeated arrival changes nothing
        val again = runBlocking { executions.arrive(tenant.organizationId, UUID.fromString(ids[0]), "notify", "action", 0, emptyList(), Instant.now()) }
        assertNull(again)
    }

    @Test
    fun `a delay waits its duration on the queue`() {
        val records = tenant.connect("mem-a")
        val workflow =
            publish(
                manual("wait"),
                mapOf("id" to "wait", "type" to "delay", "duration" to "PT1S", "next" to "write"),
                create("write", records, "later", "done"),
                end()
            )
        val execution = run(workflow)
        val parked = drain(execution, ::waiting)
        assertEquals("WAITING", stepStatus(parked, "wait"))
        assertNotNull(steps(parked)["wait"]!!.get("waitUntil").asString())
        runBlocking { runner.drainOnce() }
        assertEquals(emptyList<String>(), names(records), "nothing before the delay is over")
        Thread.sleep(1100)
        assertEquals("SUCCEEDED", status(drain(execution)))
        assertEquals(listOf("later"), names(records))
    }

    private fun approvalWorkflow(chat: String) =
        publish(
            manual("ok"),
            mapOf("id" to "ok", "type" to "approval", "title" to "Send it?", "timeout" to "PT1H", "next" to "yes", "rejected" to "no"),
            send("yes", chat, "approved", "done"),
            send("no", chat, mapOf("ref" to "steps.ok.output.approved"), "done"),
            end()
        )

    @Test
    fun `an approval waits for an administrator of the tenant, and approving goes on`() {
        val chat = tenant.connect("mem-m")
        val workflow = approvalWorkflow(chat)
        val execution = run(workflow)
        val parked = drain(execution, ::waiting)
        assertEquals("WAITING", stepStatus(parked, "ok"))
        val approve = "$base/executions/$execution/steps/ok/approve"
        call(client, HttpMethod.POST, approve, tenant.member(), null, 403)
        call(client, HttpMethod.POST, approve, other.admin, null, 404)
        call(client, HttpMethod.GET, "$base/executions/$execution", other.admin, null, 404)
        call(client, HttpMethod.POST, "$base/executions/$execution/steps/yes/approve", tenant.admin, null, 404)
        val decided = call(client, HttpMethod.POST, approve, tenant.admin, mapOf("comment" to "fine"), 200)
        assertEquals("SUCCEEDED", stepStatus(decided, "ok"))
        assertEquals("fine", steps(decided)["ok"]!!.get("output").get("comment").asString())
        assertNotNull(steps(decided)["ok"]!!.get("decidedBy").asString())
        call(client, HttpMethod.POST, approve, tenant.admin, null, 409)
        assertEquals("SUCCEEDED", status(drain(execution)))
        assertEquals(listOf("approved"), texts(memM, chat))
        // the timeout job left behind finds the step decided and does nothing
        timeoutNow(execution, "ok")
        repeat(2) { runBlocking { runner.drainOnce() } }
        assertEquals(listOf("approved"), texts(memM, chat))
    }

    @Test
    fun `rejecting takes the rejected edge, and so does a timeout`() {
        val chat = tenant.connect("mem-m")
        val workflow = approvalWorkflow(chat)
        val rejected = run(workflow)
        drain(rejected, ::waiting)
        call(client, HttpMethod.POST, "$base/executions/$rejected/steps/ok/reject", tenant.admin, null, 200)
        val done = drain(rejected)
        assertEquals("SUCCEEDED", status(done))
        assertNull(stepStatus(done, "yes"))
        assertEquals(listOf("false"), texts(memM, chat))

        val late = run(workflow)
        drain(late, ::waiting)
        timeoutNow(late, "ok")
        val timedOut = drain(late)
        assertEquals("SUCCEEDED", status(timedOut))
        assertTrue(steps(timedOut)["ok"]!!.get("output").get("timedOut").asBoolean())
        assertEquals(listOf("false", "false"), texts(memM, chat))
    }

    @Test
    fun `an approval that times out without a rejected edge fails the run`() {
        val chat = tenant.connect("mem-m")
        val workflow =
            publish(
                manual("ok"),
                mapOf("id" to "ok", "type" to "approval", "title" to "Go?", "timeout" to "PT1M", "next" to "yes"),
                send("yes", chat, "x", "done"),
                end()
            )
        val execution = run(workflow)
        drain(execution, ::waiting)
        timeoutNow(execution, "ok")
        val failed = drain(execution)
        assertEquals("FAILED", status(failed))
        assertEquals("FAILED", stepStatus(failed, "ok"))
        assertTrue(
            failed
                .get("execution")
                .get("error")
                .asString()
                .contains("timed out")
        )
        assertEquals(emptyList<String>(), texts(memM, chat))
    }

    // the approval's timeout job, due now instead of in an hour
    private fun timeoutNow(
        execution: String,
        step: String
    ) = runBlocking {
        db
            .sql("UPDATE ${schemas.metadata}.integration_jobs SET next_attempt_at = now() WHERE dedupe_key = :key")
            .bind("key", WorkflowEngine.dedupeKey(UUID.fromString(execution), step))
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    @Test
    fun `a failed step follows onError, and without one the run fails`() {
        val records = tenant.connect("mem-a")
        val chat = tenant.connect("mem-m")
        val handled =
            publish(manual("write"), create("write", records, "fail:permanent", "done", onError = "alert"), send("alert", chat, "it failed", "done"), end())
        val recovered = drain(run(handled))
        assertEquals("SUCCEEDED", status(recovered))
        assertEquals("FAILED", stepStatus(recovered, "write"))
        assertTrue(steps(recovered)["write"]!!.get("error").asString().contains("refused by the provider"))
        assertEquals(listOf("it failed"), texts(memM, chat))

        val unhandled = publish(manual("write"), create("write", records, "fail:permanent", "after"), send("after", chat, "never", "done"), end())
        val failed = drain(run(unhandled))
        assertEquals("FAILED", status(failed))
        assertTrue(
            failed
                .get("execution")
                .get("error")
                .asString()
                .contains("step 'write' failed")
        )
        assertNull(stepStatus(failed, "after"))
        assertEquals(listOf("it failed"), texts(memM, chat))
    }

    @Test
    fun `a transient failure is retried by the job queue until it works`() {
        val records = tenant.connect("mem-a")
        val workflow = publish(manual("write"), create("write", records, "fail:2", "done"), end())
        val done = drain(run(workflow))
        assertEquals("SUCCEEDED", status(done))
        assertEquals(3, steps(done)["write"]!!.get("attempt").asInt())
        assertEquals(1, names(records).size)
    }

    @Test
    fun `a run resumes after a crash without repeating finished steps`() {
        val records = tenant.connect("mem-a")
        val workflow = publish(manual("first"), create("first", records, "first", "second"), create("second", records, "second", "done"), end())
        val execution = run(workflow)
        drain(execution) { stepStatus(it, "first") == "SUCCEEDED" }
        val id = UUID.fromString(execution)
        val m = schemas.metadata
        // the worker of "second" died mid-step: its job RUNNING with a lease that ran out, its row RUNNING
        runBlocking {
            db
                .sql(
                    "UPDATE $m.integration_jobs SET status = 'RUNNING', attempts = 1, lease_until = now() - interval '1 minute' " +
                        "WHERE dedupe_key = :key AND status = 'PENDING'"
                ).bind("key", WorkflowEngine.dedupeKey(id, "second"))
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            db
                .sql("UPDATE $m.integration_workflow_step_executions SET status = 'RUNNING', attempt = 1 WHERE execution_id = :id AND step_id = 'second'")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            // and the job of the finished step is delivered again
            jobs.enqueue(
                tenant.organizationId,
                WorkflowEngine.KIND,
                mapOf("executionId" to execution, "stepId" to "first"),
                WorkflowEngine.dedupeKey(id, "first"),
                Instant.now()
            )
        }
        val done = drain(execution)
        assertEquals("SUCCEEDED", status(done))
        assertEquals(listOf("first", "second"), names(records), "each write once: the finished step was not repeated")
        assertEquals(1, steps(done)["first"]!!.get("attempt").asInt())
        assertEquals(2, steps(done)["second"]!!.get("attempt").asInt())
    }

    @Test
    fun `a cancelled run does nothing more`() {
        val records = tenant.connect("mem-a")
        val workflow =
            publish(
                manual("wait"),
                mapOf("id" to "wait", "type" to "delay", "duration" to "PT1S", "next" to "write"),
                create("write", records, "x", "done"),
                end()
            )
        val execution = run(workflow)
        drain(execution, ::waiting)
        call(client, HttpMethod.POST, "$base/executions/$execution/cancel", tenant.member(), null, 403)
        call(client, HttpMethod.POST, "$base/executions/$execution/cancel", other.admin, null, 404)
        val cancelled = call(client, HttpMethod.POST, "$base/executions/$execution/cancel", tenant.admin, null, 200)
        assertEquals("CANCELLED", status(cancelled))
        assertEquals("SKIPPED", stepStatus(cancelled, "wait"))
        Thread.sleep(1100)
        repeat(3) { runBlocking { runner.drainOnce() } }
        assertEquals("CANCELLED", status(detail(execution)))
        assertEquals(emptyList<String>(), names(records))
        call(client, HttpMethod.POST, "$base/executions/$execution/cancel", tenant.admin, null, 409)
    }

    @Test
    fun `manual runs need a published, unpaused workflow and an administrator`() {
        val records = tenant.connect("mem-a")
        val graph = mapOf("trigger" to manual("write"), "steps" to listOf(create("write", records, mapOf("ref" to "trigger.payload.name"), "done"), end()))
        val draft = call(client, HttpMethod.POST, base, tenant.admin, mapOf("name" to "Draft only", "graph" to graph), 201).get("id").asString()
        call(client, HttpMethod.POST, "$base/$draft/run", tenant.admin, null, 409)
        call(client, HttpMethod.POST, "$base/$draft/publish", tenant.admin, null, 201)
        call(client, HttpMethod.POST, "$base/$draft/run", tenant.member(), null, 403)
        call(client, HttpMethod.POST, "$base/$draft/run", other.admin, null, 404)
        call(client, HttpMethod.POST, "$base/$draft/run", tenant.admin, mapOf("payload" to listOf(1)), 400)
        val paused = call(client, HttpMethod.POST, "$base/$draft/pause", tenant.admin, null, 200)
        assertTrue(paused.get("paused").asBoolean())
        call(client, HttpMethod.POST, "$base/$draft/run", tenant.admin, null, 409)
        assertFalse(call(client, HttpMethod.POST, "$base/$draft/resume", tenant.admin, null, 200).get("paused").asBoolean())
        val first = run(draft, mapOf("name" to "Ada"))
        val started = detail(first).get("execution")
        assertEquals("manual", started.get("triggerType").asString())
        assertEquals(1, started.get("version").asInt())
        assertNotNull(started.get("startedBy").asString())
        drain(first)
        drain(run(draft, mapOf("name" to "Bo")))
        assertEquals(listOf("Ada", "Bo"), names(records))
        // history: newest first, paged by cursor, readable by members, not by another tenant
        val page = call(client, HttpMethod.GET, "$base/$draft/executions?limit=1", tenant.member(), null, 200)
        assertEquals(1, page.get("content").size())
        val cursor = page.get("nextCursor").asString()
        val rest = call(client, HttpMethod.GET, "$base/$draft/executions?limit=1&cursor=$cursor", tenant.admin, null, 200)
        assertEquals(
            first,
            rest
                .get("content")
                .get(0)
                .get("id")
                .asString()
        )
        assertTrue(rest.get("nextCursor").isNull)
        call(client, HttpMethod.GET, "$base/$draft/executions?status=succeeded", tenant.admin, null, 200)
        call(client, HttpMethod.GET, "$base/$draft/executions?status=nope", tenant.admin, null, 400)
        call(client, HttpMethod.GET, "$base/$draft/executions", other.admin, null, 404)
    }

    @Test
    fun `a schedule fires once across schedulers, and not while another holds the lock`() {
        val records = tenant.connect("mem-a")
        val workflow =
            publish(mapOf("type" to "schedule", "cron" to "0 * * * * *", "zone" to "UTC", "next" to "write"), create("write", records, "tick", "done"), end())
        val now = Instant.now().plusSeconds(61)
        val second = WorkflowScheduler(executions, engine, clusterLock, integrationsTransactions, WorkflowsProperties(), Clock.systemUTC())
        val held = runBlocking { clusterLock.tryLock(WorkflowScheduler.LOCK)!! }
        try {
            assertEquals(emptyList<UUID>(), runBlocking { scheduler.tick(now) })
            assertEquals(emptyList<UUID>(), runBlocking { second.tick(now) })
        } finally {
            runBlocking { held.release() }
        }
        val started = runBlocking { listOf(async { scheduler.tick(now) }, async { second.tick(now) }).awaitAll().flatten() }
        val mine = runs(workflow)
        assertEquals(1, mine.size)
        assertTrue(UUID.fromString(mine[0].get("id").asString()) in started)
        assertEquals("schedule", mine[0].get("triggerType").asString())
        assertTrue(runBlocking { scheduler.tick(now) + second.tick(now) }.none { it == UUID.fromString(mine[0].get("id").asString()) })
        assertEquals(1, runs(workflow).size)
        drain(mine[0].get("id").asString())
        assertEquals(listOf("tick"), names(records))
        // paused: no firing, however late
        call(client, HttpMethod.POST, "$base/$workflow/pause", tenant.admin, null, 200)
        runBlocking { scheduler.tick(now.plusSeconds(600)) }
        assertEquals(1, runs(workflow).size)
    }

    @Test
    fun `a webhook url starts runs, once per Idempotency-Key, and refuses a wrong secret`() {
        val chat = tenant.connect("mem-m")
        val workflow = publish(mapOf("type" to "webhook", "next" to "say"), send("say", chat, mapOf("ref" to "trigger.payload.text"), "done"), end())
        call(client, HttpMethod.POST, "$base/$workflow/webhook", tenant.member(), null, 403)
        val issued = call(client, HttpMethod.POST, "$base/$workflow/webhook", tenant.admin, null, 201)
        val path = issued.get("path").asString()
        assertTrue(path.startsWith("/api/integrations/webhooks/workflows/$workflow/"))
        val body = mapOf("text" to "hello")
        val first = call(client, HttpMethod.POST, path, null, body, 202, mapOf("Idempotency-Key" to "k-1")).get("executionId").asString()
        val repeated = call(client, HttpMethod.POST, path, null, body, 202, mapOf("Idempotency-Key" to "k-1")).get("executionId").asString()
        assertEquals(first, repeated)
        call(client, HttpMethod.POST, "${path}x", null, body, 404)
        call(client, HttpMethod.POST, "/api/integrations/webhooks/workflows/${UUID.randomUUID()}/nope", null, body, 404)
        assertEquals("SUCCEEDED", status(drain(first)))
        assertEquals(listOf("hello"), texts(memM, chat))
        assertEquals(1, runs(workflow).size)
        // a new url replaces the old one
        call(client, HttpMethod.POST, "$base/$workflow/webhook", tenant.admin, null, 201)
        call(client, HttpMethod.POST, path, null, body, 404)
    }
}
