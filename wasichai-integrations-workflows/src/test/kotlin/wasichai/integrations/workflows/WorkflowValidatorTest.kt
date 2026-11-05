package wasichai.integrations.workflows

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.FieldViolation
import wasichai.integrations.sdk.Connector
import java.util.UUID

class WorkflowValidatorTest {
    private val json = JsonMapper.builder().build()
    private val records = UUID.randomUUID()
    private val messenger = UUID.randomUUID()
    private val upserter = UUID.randomUUID()
    private val orphan = UUID.randomUUID()
    private val foreign = UUID.randomUUID()
    private val connectors: Map<UUID, Connector> = mapOf(records to MemConnector("mem-a"), messenger to MemMessenger("mem-m"), upserter to MemUpserter("mem-u"))

    // manual -> create a contact -> branch on its id -> notify | done; a failed create ends at "failed"
    private fun steps(): MutableList<MutableMap<String, Any?>> =
        mutableListOf(
            mutableMapOf(
                "id" to "create",
                "type" to "action",
                "connectionId" to records.toString(),
                "write" to mapOf("resource" to "contacts", "operation" to "create"),
                "input" to mapOf("name" to mapOf("value" to "Ada"), "email" to mapOf("ref" to "trigger.payload.email")),
                "next" to "check",
                "onError" to "failed"
            ),
            mutableMapOf(
                "id" to "check",
                "type" to "branch",
                "condition" to mapOf("op" to "exists", "ref" to "steps.create.output.id"),
                "then" to "notify",
                "else" to "done"
            ),
            mutableMapOf(
                "id" to "notify",
                "type" to "action",
                "connectionId" to messenger.toString(),
                "action" to "send_message",
                "input" to mapOf("text" to mapOf("ref" to "steps.create.output.id")),
                "next" to "done"
            ),
            mutableMapOf("id" to "done", "type" to "end"),
            mutableMapOf("id" to "failed", "type" to "end")
        )

    private fun graph(
        trigger: Map<String, Any?> = mapOf("type" to "manual", "next" to "create"),
        steps: List<Map<String, Any?>> = steps()
    ): Map<String, Any?> = mapOf("trigger" to trigger, "steps" to steps)

    private fun violations(graph: Any?): List<FieldViolation> {
        val v = mutableListOf<FieldViolation>()
        val parsed = WorkflowGraph.parse(json.valueToTree(graph), v) ?: return v
        return WorkflowValidator.offline(parsed, setOf(records, messenger, upserter, orphan), connectors)
    }

    private fun fields(graph: Any?): Set<String> = violations(graph).map { it.field }.toSet()

    private fun with(
        index: Int,
        vararg changes: Pair<String, Any?>
    ): Map<String, Any?> = graph(steps = steps().also { s -> changes.forEach { (k, value) -> if (value == null) s[index].remove(k) else s[index][k] = value } })

    @Test
    fun `a well-formed graph has no violations`() {
        assertEquals(emptyList<FieldViolation>(), violations(graph()))
    }

    @Test
    fun `the graph is an object with exactly one trigger and some steps`() {
        assertEquals(setOf("graph"), fields(listOf(1)))
        assertEquals(setOf("graph.trigger"), fields(mapOf("steps" to steps())))
        assertEquals(setOf("graph.trigger"), fields(graph().plus("trigger" to listOf(mapOf("type" to "manual", "next" to "create")))))
        assertEquals(setOf("graph.trigger.type"), fields(graph(trigger = mapOf("type" to "cron", "next" to "create"))))
        assertEquals(setOf("graph.trigger.next"), fields(graph(trigger = mapOf("type" to "manual"))))
        assertEquals(setOf("graph.steps"), fields(graph(steps = emptyList())))
        assertEquals(setOf("graph.steps"), fields(graph(steps = List(201) { mapOf("id" to "e$it", "type" to "end") })))
    }

    @Test
    fun `steps have a known type and an id`() {
        assertEquals(setOf("graph.steps[3].type"), fields(with(3, "type" to "loop")))
        assertEquals(setOf("graph.steps[3].id"), fields(with(3, "id" to "1-done")))
        assertEquals(setOf("graph.steps[4].id", "graph.steps[0].onError"), fields(with(4, "id" to "done")))
    }

    @Test
    fun `edges point at existing steps`() {
        assertEquals(setOf("graph.trigger.next"), fields(graph(trigger = mapOf("type" to "manual", "next" to "nowhere"))))
        assertEquals(setOf("graph.steps[1].else"), fields(with(1, "else" to "nowhere")))
        assertEquals(setOf("graph.steps[2].onError"), fields(with(2, "onError" to "nowhere")))
        assertEquals(setOf("graph.steps[1].then"), fields(with(1, "then" to null)))
    }

    @Test
    fun `every step is reachable and there are no loops`() {
        val unreachable = steps().also { it += mutableMapOf("id" to "lost", "type" to "end") }
        assertEquals(setOf("graph.steps[5]"), fields(graph(steps = unreachable)))
        val loop = fields(with(2, "next" to "create"))
        assertEquals(setOf("graph.steps[2].next"), loop)
        assertTrue(violations(with(2, "next" to "create")).single().message.contains("cycle"))
        assertEquals(setOf("graph.steps[1].else"), fields(with(1, "else" to "check")))
    }

    @Test
    fun `references read what ran before, from steps with an output`() {
        val unknown = steps().also { it[2]["input"] = mapOf("text" to mapOf("ref" to "steps.ghost.output")) }
        assertEquals(setOf("graph.steps[2].input.text.ref"), fields(graph(steps = unknown)))
        val noOutput = steps().also { it[2]["input"] = mapOf("text" to mapOf("ref" to "steps.check.result")) }
        assertEquals(setOf("graph.steps[2].input.text.ref"), fields(graph(steps = noOutput)))
        val later = steps().also { it[1]["condition"] = mapOf("op" to "exists", "ref" to "steps.notify.output") }
        val problem = violations(graph(steps = later)).single()
        assertEquals("graph.steps[1].condition.ref", problem.field)
        assertTrue(problem.message.contains("does not run before"), problem.message)
    }

    @Test
    fun `parallel branches fan out and meet at their join`() {
        fun parallel(
            branches: List<String>,
            join: String,
            bNext: String = "done"
        ) = graph(
            trigger = mapOf("type" to "manual", "next" to "fan"),
            steps =
                listOf(
                    mapOf("id" to "fan", "type" to "parallel", "branches" to branches, "join" to join),
                    mapOf("id" to "a", "type" to "delay", "duration" to "PT5M", "next" to "done"),
                    mapOf("id" to "b", "type" to "delay", "duration" to "PT1H", "next" to bNext),
                    mapOf("id" to "done", "type" to "end"),
                    mapOf("id" to "other", "type" to "end")
                ).filter { it["id"] != "other" || bNext == "other" }
        )
        assertEquals(emptyList<FieldViolation>(), violations(parallel(listOf("a", "b"), "done")))
        assertEquals(setOf("graph.steps[0].branches"), fields(parallel(listOf("a"), "done")))
        assertEquals(setOf("graph.steps[0].join"), fields(parallel(listOf("a", "b"), "done", bNext = "other")))
        assertEquals(setOf("graph.steps[0].join"), fields(parallel(listOf("a", "b"), "nowhere")).filter { it.endsWith("join") }.toSet())
        assertTrue(fields(parallel(listOf("a", "b"), "a")).contains("graph.steps[0].join"))
        assertEquals(setOf("graph.steps[0].branches"), fields(parallel(listOf("a", "a"), "done")).filter { it.endsWith("branches") }.toSet())
    }

    @Test
    fun `delays and approvals take bounded durations and a title`() {
        fun one(step: Map<String, Any?>) =
            graph(
                trigger = mapOf("type" to "manual", "next" to "s"),
                steps =
                    listOf(
                        step,
                        mapOf("id" to "done", "type" to "end"),
                        mapOf(
                            "id" to "no",
                            "type" to "end"
                        )
                    )
            )
        assertEquals(
            setOf("graph.steps[0].duration"),
            fields(one(mapOf("id" to "s", "type" to "delay", "duration" to "5 minutes", "next" to "done"))) - "graph.steps[2]"
        )
        assertTrue(fields(one(mapOf("id" to "s", "type" to "delay", "duration" to "P90D", "next" to "done"))).contains("graph.steps[0].duration"))
        assertTrue(fields(one(mapOf("id" to "s", "type" to "approval", "next" to "done"))).contains("graph.steps[0].title"))
        assertTrue(
            fields(one(mapOf("id" to "s", "type" to "approval", "title" to "OK?", "timeout" to "PT1S", "next" to "done"))).contains("graph.steps[0].timeout")
        )
        assertEquals(
            emptyList<FieldViolation>(),
            violations(one(mapOf("id" to "s", "type" to "approval", "title" to "OK?", "timeout" to "P2D", "next" to "done", "rejected" to "no")))
        )
    }

    @Test
    fun `an action is a connector action or a resource write, never both`() {
        assertEquals(setOf("graph.steps[2]"), fields(with(2, "write" to mapOf("resource" to "contacts", "operation" to "create"))))
        assertEquals(setOf("graph.steps[2]"), fields(with(2, "action" to null)))
        assertEquals(setOf("graph.steps[0].write.operation"), fields(with(0, "write" to mapOf("resource" to "contacts", "operation" to "merge"))))
        assertEquals(setOf("graph.steps[0].write.recordId"), fields(with(0, "write" to mapOf("resource" to "contacts", "operation" to "update"))))
        assertEquals(
            setOf("graph.steps[0].write.externalIdField", "graph.steps[0].write.externalId"),
            fields(with(0, "connectionId" to upserter.toString(), "write" to mapOf("resource" to "contacts", "operation" to "upsert")))
        )
        assertEquals(
            setOf("graph.steps[0].input"),
            fields(with(0, "write" to mapOf("resource" to "contacts", "operation" to "delete", "recordId" to mapOf("value" to "1"))))
        )
        assertEquals(setOf("graph.steps[0].input"), fields(with(0, "input" to emptyMap<String, Any>())))
        assertEquals(setOf("graph.steps[0].input.name"), fields(with(0, "input" to mapOf("name" to "Ada"))))
        assertEquals(setOf("graph.steps[0].input.name"), fields(with(0, "input" to mapOf("name" to mapOf("ref" to "trigger", "value" to 1)))))
        assertEquals(setOf("graph.steps[0].connectionId"), fields(with(0, "connectionId" to "not-a-uuid")))
    }

    @Test
    fun `schedules are six-field crons at most once a minute, in a known zone`() {
        fun schedule(vararg config: Pair<String, Any>) = graph(trigger = mapOf("type" to "schedule", "next" to "create") + config)
        assertEquals(emptyList<FieldViolation>(), violations(schedule("cron" to "0 0 9 * * MON-FRI", "zone" to "America/Santiago")))
        assertEquals(setOf("graph.trigger.cron"), fields(schedule("cron" to "every day")))
        assertEquals(setOf("graph.trigger.cron"), fields(schedule("cron" to "* * * * * *")))
        assertEquals(setOf("graph.trigger.zone"), fields(schedule("cron" to "0 */5 * * * *", "zone" to "Mars/Olympus")))
    }

    @Test
    fun `an event trigger names an event its connector declares`() {
        fun event(
            connection: UUID,
            eventType: String?
        ) = graph(
            trigger =
                mapOf("type" to "event", "connectionId" to connection.toString(), "next" to "create") + listOfNotNull(eventType?.let { "eventType" to it })
        )
        assertEquals(emptyList<FieldViolation>(), violations(event(messenger, "message.created")))
        assertEquals(setOf("graph.trigger.eventType"), fields(event(messenger, null)))
        assertEquals(setOf("graph.trigger.eventType"), fields(event(messenger, "message.deleted")))
        assertEquals(setOf("graph.trigger.connectionId"), fields(event(records, "message.created")))
        assertEquals(setOf("graph.trigger.connectionId"), fields(event(foreign, "message.created")))
    }

    @Test
    fun `connections are the tenant's and their connectors declare what the step uses`() {
        assertEquals("unknown connection", violations(with(0, "connectionId" to foreign.toString())).single().message)
        assertEquals(setOf("graph.steps[0].connectionId"), fields(with(0, "connectionId" to orphan.toString())))
        assertEquals(setOf("graph.steps[2].action"), fields(with(2, "action" to "delete_channel")))
        assertEquals(setOf("graph.steps[2].action"), fields(with(2, "connectionId" to records.toString())))
        assertEquals(setOf("graph.steps[0].write.operation", "graph.steps[0].write.resource"), fields(with(0, "connectionId" to messenger.toString())))
        assertEquals(
            setOf("graph.steps[0].write.operation"),
            fields(
                with(
                    0,
                    "write" to mapOf("resource" to "contacts", "operation" to "upsert", "externalIdField" to "extId", "externalId" to mapOf("value" to "x"))
                )
            )
        )
        assertEquals(setOf("graph.steps[0].write.resource"), fields(with(0, "write" to mapOf("resource" to "deals", "operation" to "create"))))
    }

    @Test
    fun `a write's input fits the resource schema`() {
        fun schema(
            input: Map<String, Any?>,
            write: Map<String, Any?> = mapOf("resource" to "contacts", "operation" to "create"),
            upserts: Boolean = false
        ): Set<String> {
            val v = mutableListOf<FieldViolation>()
            val parsed = WorkflowGraph.parse(json.valueToTree(with(0, "input" to input, "write" to write)), v)
            assertNotNull(parsed, v.toString())
            val step = parsed!!.steps[0] as Step.Action
            return WorkflowValidator.againstSchema(step, step.write!!, MemConnector.SCHEMA, upserts).map { it.field }.toSet()
        }
        assertEquals(emptySet<String>(), schema(mapOf("name" to mapOf("value" to "Ada"), "amount" to mapOf("value" to 3), "vip" to mapOf("value" to true))))
        assertEquals(setOf("graph.steps[0].input.nickname"), schema(mapOf("name" to mapOf("value" to "Ada"), "nickname" to mapOf("value" to "A"))))
        assertEquals(setOf("graph.steps[0].input.computed"), schema(mapOf("name" to mapOf("value" to "Ada"), "computed" to mapOf("ref" to "trigger.x"))))
        assertEquals(
            setOf(
                "graph.steps[0].input.amount.value",
                "graph.steps[0].input.since.value",
                "graph.steps[0].input.seenAt.value",
                "graph.steps[0].input.tier.value"
            ),
            schema(
                mapOf(
                    "name" to mapOf("value" to "Ada"),
                    "amount" to mapOf("value" to "three"),
                    "since" to mapOf("value" to "yesterday"),
                    "seenAt" to mapOf("value" to 5),
                    "tier" to mapOf("value" to "platinum")
                )
            )
        )
        // a reference's type is only known when the workflow runs
        assertEquals(emptySet<String>(), schema(mapOf("name" to mapOf("ref" to "trigger.payload.name"), "amount" to mapOf("ref" to "trigger.payload.n"))))
        assertEquals(setOf("graph.steps[0].input"), schema(mapOf("email" to mapOf("value" to "a@b.c"))))
        assertEquals(setOf("graph.steps[0].input.name.value"), schema(mapOf("name" to mapOf("value" to null))))
        val upsert = mapOf("resource" to "contacts", "operation" to "upsert", "externalIdField" to "email", "externalId" to mapOf("ref" to "trigger.id"))
        assertEquals(setOf("graph.steps[0].write.externalIdField"), schema(mapOf("name" to mapOf("value" to "Ada")), upsert, upserts = true))
        assertEquals(setOf("graph.steps[0].write.externalIdField"), schema(mapOf("name" to mapOf("value" to "Ada")), upsert + ("externalIdField" to "nope")))
    }
}
