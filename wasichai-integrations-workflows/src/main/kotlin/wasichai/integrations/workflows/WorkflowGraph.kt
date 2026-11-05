package wasichai.integrations.workflows

import org.springframework.scheduling.support.CronExpression
import tools.jackson.databind.JsonNode
import wasichai.core.common.FieldViolation
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

// a value written into an action's input: a reference to what the run produced, or a literal
sealed interface Operand {
    data class Reference(
        val ref: Ref
    ) : Operand

    data class Literal(
        val value: JsonNode
    ) : Operand

    companion object {
        // {"ref": "trigger.payload.email"} or {"value": "x"}: exactly one key
        fun parse(
            node: JsonNode?,
            path: String,
            v: MutableList<FieldViolation>
        ): Operand? {
            if (node == null || !node.isObject || node.size() != 1 || !(node.has("ref") || node.has("value"))) {
                v += FieldViolation(path, "{\"ref\": \"...\"} or {\"value\": ...}")
                return null
            }
            return if (node.has("ref")) Ref.parse(node.get("ref"), "$path.ref", v)?.let { Reference(it) } else Literal(node.get("value"))
        }
    }
}

enum class TriggerType(
    val json: String
) {
    EVENT("event"),
    WEBHOOK("webhook"),
    SCHEDULE("schedule"),
    MANUAL("manual")
}

// what starts a run. one per workflow; `next` is the first step
sealed interface Trigger {
    val type: TriggerType
    val next: String

    // a webhook event of a connection, by the connector's event type
    data class Event(
        val connectionId: UUID,
        val eventType: String,
        override val next: String
    ) : Trigger {
        override val type get() = TriggerType.EVENT
    }

    // a url of the workflow's own (issued when execution lands, #42)
    data class Webhook(
        override val next: String
    ) : Trigger {
        override val type get() = TriggerType.WEBHOOK
    }

    // Spring's six-field cron in a zone
    data class Schedule(
        val cron: String,
        val zone: ZoneId,
        override val next: String
    ) : Trigger {
        override val type get() = TriggerType.SCHEDULE
    }

    data class Manual(
        override val next: String
    ) : Trigger {
        override val type get() = TriggerType.MANUAL
    }
}

enum class WriteOperation(
    val json: String
) {
    CREATE("create"),
    UPDATE("update"),
    UPSERT("upsert"),
    DELETE("delete")
}

// a record write on a resource of the step's connection
data class ResourceWrite(
    val resource: String,
    val operation: WriteOperation,
    // update and delete
    val recordId: Operand?,
    // upsert
    val externalIdField: String?,
    val externalId: Operand?
)

// one node of the graph. its index in `steps` locates its violations: graph.steps[2].input.email
sealed interface Step {
    val id: String
    val index: Int

    // where a failure goes; absent means the run fails
    val onError: String?

    // named outgoing edges: (field, target id)
    fun edges(): List<Pair<String, String>>

    fun refs(): List<Pair<Ref, String>> = emptyList()

    val path: String get() = "graph.steps[$index]"

    // a connector action (ActionExecutor) or a resource write, on one connection
    data class Action(
        override val id: String,
        override val index: Int,
        val connectionId: UUID,
        val action: String?,
        val write: ResourceWrite?,
        val input: Map<String, Operand>,
        val next: String,
        override val onError: String?
    ) : Step {
        override fun edges() = listOfNotNull("next" to next, onError?.let { "onError" to it })

        override fun refs(): List<Pair<Ref, String>> =
            input.mapNotNull { (k, o) -> (o as? Operand.Reference)?.let { it.ref to "$path.input.$k.ref" } } +
                listOfNotNull(
                    (write?.recordId as? Operand.Reference)?.let { it.ref to "$path.write.recordId.ref" },
                    (write?.externalId as? Operand.Reference)?.let { it.ref to "$path.write.externalId.ref" }
                )
    }

    // a gate: on to next when it holds, the run ends otherwise
    data class Gate(
        override val id: String,
        override val index: Int,
        val condition: Condition,
        val next: String,
        override val onError: String?
    ) : Step {
        override fun edges() = listOfNotNull("next" to next, onError?.let { "onError" to it })

        override fun refs() = condition.refs()
    }

    data class Branch(
        override val id: String,
        override val index: Int,
        val condition: Condition,
        val then: String,
        val otherwise: String,
        override val onError: String?
    ) : Step {
        override fun edges() = listOfNotNull("then" to then, "else" to otherwise, onError?.let { "onError" to it })

        override fun refs() = condition.refs()
    }

    // every branch runs; the run goes on at join once each has reached it
    data class Parallel(
        override val id: String,
        override val index: Int,
        val branches: List<String>,
        val join: String,
        override val onError: String?
    ) : Step {
        override fun edges() = branches.mapIndexed { i, b -> "branches[$i]" to b } + listOfNotNull(onError?.let { "onError" to it })
    }

    data class Delay(
        override val id: String,
        override val index: Int,
        val duration: Duration,
        val next: String,
        override val onError: String?
    ) : Step {
        override fun edges() = listOfNotNull("next" to next, onError?.let { "onError" to it })
    }

    // a person decides: next when approved, rejected (else the run ends) when not or when it times out
    data class Approval(
        override val id: String,
        override val index: Int,
        val title: String,
        val timeout: Duration?,
        val next: String,
        val rejected: String?,
        override val onError: String?
    ) : Step {
        override fun edges() = listOfNotNull("next" to next, rejected?.let { "rejected" to it }, onError?.let { "onError" to it })
    }

    data class End(
        override val id: String,
        override val index: Int
    ) : Step {
        override val onError: String? get() = null

        override fun edges() = emptyList<Pair<String, String>>()
    }
}

// the graph as stored: the json the editor sent, parsed into this model with field-located violations.
// the json is what versions keep; this model is what the validator (and the runner, #42) reads.
data class WorkflowGraph(
    val trigger: Trigger,
    val steps: List<Step>
) {
    fun step(id: String): Step? = steps.firstOrNull { it.id == id }

    val connectionIds: Set<UUID>
        get() = (listOfNotNull((trigger as? Trigger.Event)?.connectionId) + steps.filterIsInstance<Step.Action>().map { it.connectionId }).toSet()

    companion object {
        const val ID_PATTERN = "[A-Za-z][A-Za-z0-9_-]{0,63}"
        private val ID = Regex("^$ID_PATTERN$")
        const val MAX_STEPS = 200
        const val MAX_INPUT = 100
        val STEP_TYPES = listOf("action", "condition", "branch", "parallel", "delay", "approval", "end")
        private val MIN_DELAY = Duration.ofSeconds(1)
        private val MAX_WAIT = Duration.ofDays(30)
        private val MIN_SCHEDULE_GAP = Duration.ofMinutes(1)

        // null when the trigger or a step does not parse; v holds why
        fun parse(
            graph: JsonNode?,
            v: MutableList<FieldViolation>
        ): WorkflowGraph? {
            if (graph == null || !graph.isObject) {
                v += FieldViolation("graph", "an object with a trigger and steps")
                return null
            }
            val trigger = trigger(graph.get("trigger"), v)
            val steps = graph.get("steps")
            if (steps == null || !steps.isArray || steps.isEmpty) {
                v += FieldViolation("graph.steps", "at least one step")
                return null
            }
            if (steps.size() > MAX_STEPS) {
                v += FieldViolation("graph.steps", "at most $MAX_STEPS steps")
                return null
            }
            val parsed = (0 until steps.size()).map { step(steps.get(it), it, v) }
            if (trigger == null || parsed.any { it == null }) return null
            return WorkflowGraph(trigger, parsed.filterNotNull())
        }

        private fun trigger(
            node: JsonNode?,
            v: MutableList<FieldViolation>
        ): Trigger? {
            val path = "graph.trigger"
            if (node == null || !node.isObject) {
                v += FieldViolation(path, "exactly one trigger, an object")
                return null
            }
            val next = id(node.get("next"), "$path.next", v)
            val type = text(node, "type")
            val trigger =
                when (type) {
                    "event" -> {
                        val connection = uuid(node.get("connectionId"), "$path.connectionId", v)
                        val eventType = bounded(node, "eventType", path, 200, v)
                        if (connection == null || eventType == null || next == null) null else Trigger.Event(connection, eventType, next)
                    }
                    "webhook" -> next?.let { Trigger.Webhook(it) }
                    "schedule" -> schedule(node, path, next, v)
                    "manual" -> next?.let { Trigger.Manual(it) }
                    else -> {
                        v += FieldViolation("$path.type", "one of ${TriggerType.entries.joinToString { it.json }}")
                        null
                    }
                }
            return trigger
        }

        private fun schedule(
            node: JsonNode,
            path: String,
            next: String?,
            v: MutableList<FieldViolation>
        ): Trigger.Schedule? {
            val cron = text(node, "cron")
            val expression = cron?.let { runCatching { CronExpression.parse(it) }.getOrNull() }
            if (expression == null) {
                v += FieldViolation("$path.cron", "a six-field cron expression (second minute hour day month weekday)")
            } else if (tooFrequent(expression)) {
                v += FieldViolation("$path.cron", "at most once a minute")
            }
            val zone =
                try {
                    ZoneId.of(text(node, "zone") ?: "UTC")
                } catch (e: DateTimeException) {
                    v += FieldViolation("$path.zone", "unknown time zone")
                    null
                }
            return if (expression == null || zone == null || next == null || tooFrequent(expression)) null else Trigger.Schedule(cron, zone, next)
        }

        // a few consecutive firings from a fixed instant tell a per-second cron from a per-minute one
        private fun tooFrequent(expression: CronExpression): Boolean {
            var at = Instant.parse("2026-01-01T00:00:00Z").atZone(ZoneId.of("UTC"))
            repeat(5) {
                val next = expression.next(at) ?: return false
                if (it > 0 && Duration.between(at, next) < MIN_SCHEDULE_GAP) return true
                at = next
            }
            return false
        }

        private fun step(
            node: JsonNode?,
            index: Int,
            v: MutableList<FieldViolation>
        ): Step? {
            val path = "graph.steps[$index]"
            if (node == null || !node.isObject) {
                v += FieldViolation(path, "a step object")
                return null
            }
            val id = id(node.get("id"), "$path.id", v)
            val onError = node.get("onError")?.takeUnless { it.isNull }?.let { id(it, "$path.onError", v) ?: return null }

            fun edge(name: String) = id(node.get(name), "$path.$name", v)
            return when (val type = text(node, "type")) {
                "action" -> action(node, path, index, id, onError, v)
                "condition" -> {
                    val condition = Condition.parse(node.get("condition"), "$path.condition", v)
                    val next = edge("next")
                    if (id == null || condition == null || next == null) null else Step.Gate(id, index, condition, next, onError)
                }
                "branch" -> {
                    val condition = Condition.parse(node.get("condition"), "$path.condition", v)
                    val then = edge("then")
                    val otherwise = edge("else")
                    if (id == null || condition == null || then == null ||
                        otherwise == null
                    ) {
                        null
                    } else {
                        Step.Branch(id, index, condition, then, otherwise, onError)
                    }
                }
                "parallel" -> {
                    val list = node.get("branches")
                    if (list == null || !list.isArray || list.size() < 2 || list.size() > 20) {
                        v += FieldViolation("$path.branches", "2 to 20 step ids")
                        return null
                    }
                    val branches = (0 until list.size()).map { id(list.get(it), "$path.branches[$it]", v) }
                    val join = edge("join")
                    if (id == null || join == null || branches.any { it == null }) null else Step.Parallel(id, index, branches.filterNotNull(), join, onError)
                }
                "delay" -> {
                    val duration = duration(node, "duration", path, MIN_DELAY, v)
                    val next = edge("next")
                    if (id == null || duration == null || next == null) null else Step.Delay(id, index, duration, next, onError)
                }
                "approval" -> {
                    val title = bounded(node, "title", path, 200, v)
                    val timeout = if (node.has("timeout")) duration(node, "timeout", path, Duration.ofMinutes(1), v) ?: return null else null
                    val next = edge("next")
                    val rejected = node.get("rejected")?.takeUnless { it.isNull }?.let { edge("rejected") ?: return null }
                    if (id == null || title == null || next == null) null else Step.Approval(id, index, title, timeout, next, rejected, onError)
                }
                "end" -> id?.let { Step.End(it, index) }
                else -> {
                    v += FieldViolation("$path.type", "one of ${STEP_TYPES.joinToString()}")
                    null
                }
            }
        }

        private fun action(
            node: JsonNode,
            path: String,
            index: Int,
            id: String?,
            onError: String?,
            v: MutableList<FieldViolation>
        ): Step.Action? {
            val connection = uuid(node.get("connectionId"), "$path.connectionId", v)
            val next = id(node.get("next"), "$path.next", v)
            val hasAction = node.has("action")
            val hasWrite = node.has("write")
            if (hasAction == hasWrite) {
                v += FieldViolation(path, "exactly one of 'action' (a connector action) or 'write' (a resource write)")
                return null
            }
            val action = if (hasAction) bounded(node, "action", path, 100, v) ?: return null else null
            val write = if (hasWrite) write(node.get("write"), "$path.write", v) ?: return null else null
            val input = input(node.get("input"), "$path.input", v) ?: return null
            if (write?.operation == WriteOperation.DELETE && input.isNotEmpty()) {
                v += FieldViolation("$path.input", "a delete takes no input")
                return null
            }
            if (write?.operation == WriteOperation.CREATE && input.isEmpty()) {
                v += FieldViolation("$path.input", "a create needs at least one field")
                return null
            }
            if (id == null || connection == null || next == null) return null
            return Step.Action(id, index, connection, action, write, input, next, onError)
        }

        private fun write(
            node: JsonNode?,
            path: String,
            v: MutableList<FieldViolation>
        ): ResourceWrite? {
            if (node == null || !node.isObject) {
                v += FieldViolation(path, "an object with resource and operation")
                return null
            }
            val resource = bounded(node, "resource", path, 300, v)
            val operation = WriteOperation.entries.firstOrNull { it.json == text(node, "operation") }
            if (operation == null) v += FieldViolation("$path.operation", "one of ${WriteOperation.entries.joinToString { it.json }}")
            val needsId = operation == WriteOperation.UPDATE || operation == WriteOperation.DELETE
            val recordId =
                if (needsId) Operand.parse(node.get("recordId"), "$path.recordId", v) else null
            var externalIdField: String? = null
            var externalId: Operand? = null
            if (operation == WriteOperation.UPSERT) {
                externalIdField = bounded(node, "externalIdField", path, 200, v)
                externalId = Operand.parse(node.get("externalId"), "$path.externalId", v)
            }
            if (resource == null || operation == null || (needsId && recordId == null)) return null
            if (operation == WriteOperation.UPSERT && (externalIdField == null || externalId == null)) return null
            return ResourceWrite(resource, operation, recordId, externalIdField, externalId)
        }

        private fun input(
            node: JsonNode?,
            path: String,
            v: MutableList<FieldViolation>
        ): Map<String, Operand>? {
            if (node == null || node.isNull) return emptyMap()
            if (!node.isObject || node.size() > MAX_INPUT) {
                v += FieldViolation(path, "an object of at most $MAX_INPUT fields, each {\"ref\"} or {\"value\"}")
                return null
            }
            val parsed =
                node.properties().associate { (k, value) ->
                    if (k.isBlank() || k.length > 200) v += FieldViolation(path, "field names are 1 to 200 characters")
                    k to Operand.parse(value, "$path.$k", v)
                }
            return if (parsed.values.any { it == null } || parsed.keys.any { it.isBlank() || it.length > 200 }) null else parsed.mapValues { it.value!! }
        }

        private fun text(
            node: JsonNode,
            name: String
        ): String? = node.get(name)?.takeIf { it.isString }?.stringValue()

        private fun bounded(
            node: JsonNode,
            name: String,
            path: String,
            max: Int,
            v: MutableList<FieldViolation>
        ): String? {
            val value = text(node, name)?.trim()
            if (value.isNullOrEmpty() || value.length > max) {
                v += FieldViolation("$path.$name", "required, 1 to $max characters")
                return null
            }
            return value
        }

        private fun id(
            node: JsonNode?,
            path: String,
            v: MutableList<FieldViolation>
        ): String? {
            val value = node?.takeIf { it.isString }?.stringValue()
            if (value == null || !ID.matches(value)) {
                v += FieldViolation(path, "a step id: a letter, then letters, digits, '_' or '-' (at most 64)")
                return null
            }
            return value
        }

        private fun uuid(
            node: JsonNode?,
            path: String,
            v: MutableList<FieldViolation>
        ): UUID? {
            val value = node?.takeIf { it.isString }?.let { runCatching { UUID.fromString(it.stringValue()) }.getOrNull() }
            if (value == null) v += FieldViolation(path, "a connection id")
            return value
        }

        private fun duration(
            node: JsonNode,
            name: String,
            path: String,
            min: Duration,
            v: MutableList<FieldViolation>
        ): Duration? {
            val value = text(node, name)?.let { runCatching { Duration.parse(it) }.getOrNull() }
            if (value == null || value < min || value > MAX_WAIT) {
                v += FieldViolation("$path.$name", "an ISO-8601 duration from $min to ${MAX_WAIT.toDays()} days (PT5M, PT2H, P1D)")
                return null
            }
            return value
        }
    }
}
