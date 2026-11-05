package wasichai.integrations.workflows

import tools.jackson.databind.JsonNode
import wasichai.core.common.FieldViolation
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.ProviderErrors
import wasichai.integrations.sdk.ActionExecutor
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceSchema
import wasichai.integrations.sdk.ResourceUpserter
import wasichai.integrations.sdk.ResourceWriter
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

// checks a workflow graph before it may be published: its shape, its edges, the tenant's connections, what their
// connectors declare and, for record writes, the resource schema the connector describes today
class WorkflowValidator(
    private val registry: ConnectorRegistry,
    private val connections: ConnectionRepository,
    private val contexts: ConnectorContexts
) {
    // provider failures other than a missing resource surface as http errors, not as violations
    suspend fun validate(
        organizationId: UUID,
        graph: JsonNode?
    ): List<FieldViolation> {
        val v = mutableListOf<FieldViolation>()
        val parsed = WorkflowGraph.parse(graph, v)
        if (parsed == null) return v
        // another tenant's connection reads as unknown, like a missing one
        val found = parsed.connectionIds.mapNotNull { id -> connections.find(organizationId, id)?.let { id to it } }.toMap()
        val connectors = found.mapNotNull { (id, c) -> registry.find(c.connectorId)?.let { id to it } }.toMap()
        v += offline(parsed, found.keys, connectors)
        if (v.isNotEmpty()) return v
        for (step in parsed.steps.filterIsInstance<Step.Action>()) {
            val write = step.write ?: continue
            val connector = connectors.getValue(step.connectionId) as? ResourceDiscovery ?: continue
            val schema =
                try {
                    connector.describe(contexts.forConnection(found.getValue(step.connectionId)), write.resource)
                } catch (e: ConnectorException.NotFound) {
                    v += FieldViolation("${step.path}.write.resource", "resource '${write.resource}' does not exist")
                    continue
                } catch (e: ConnectorException) {
                    throw ProviderErrors.toHttp(e)
                }
            v += againstSchema(step, write, schema, connectors.getValue(step.connectionId) is ResourceUpserter)
        }
        return v
    }

    companion object {
        // everything that needs no provider call. tenantConnections: the referenced ids found in the tenant;
        // connectors: those whose connector is installed
        fun offline(
            graph: WorkflowGraph,
            tenantConnections: Set<UUID>,
            connectors: Map<UUID, Connector>
        ): List<FieldViolation> {
            val v = mutableListOf<FieldViolation>()
            shape(graph, v)
            connections(graph, tenantConnections, connectors, v)
            return v
        }

        private fun shape(
            graph: WorkflowGraph,
            v: MutableList<FieldViolation>
        ) {
            val byId = mutableMapOf<String, Step>()
            graph.steps.forEach { step ->
                if (byId.putIfAbsent(step.id, step) != null) v += FieldViolation("${step.path}.id", "duplicate step id '${step.id}'")
            }
            if (graph.trigger.next !in byId) v += FieldViolation("graph.trigger.next", "no step '${graph.trigger.next}'")
            graph.steps.forEach { step ->
                step.edges().forEach { (field, target) ->
                    if (target !in byId) v += FieldViolation("${step.path}.$field", "no step '$target'")
                }
                if (step is Step.Parallel) {
                    if (step.join !in byId) v += FieldViolation("${step.path}.join", "no step '${step.join}'")
                    if (step.join in step.branches) v += FieldViolation("${step.path}.join", "the join cannot be one of the branches")
                    if (step.branches.toSet().size != step.branches.size) v += FieldViolation("${step.path}.branches", "a branch is listed twice")
                }
            }
            if (v.isNotEmpty()) return
            // the edges a run may take; a parallel step also leads to its join, through its branches
            val out = graph.steps.associate { it.id to it.edges().map { e -> e.second } }
            val reachable = reach(graph.trigger.next, out)
            graph.steps.filterNot { it.id in reachable }.forEach { v += FieldViolation(it.path, "not reachable from the trigger") }
            cycles(graph, v)
            if (v.isNotEmpty()) return
            graph.steps.filterIsInstance<Step.Parallel>().forEach { p ->
                p.branches.forEachIndexed { i, b ->
                    if (p.join !in reach(b, out)) v += FieldViolation("${p.path}.join", "not reached from branches[$i] '$b'")
                }
                // a step runs at most once per run (ADR-0025): two branches may meet only at the join. a step that
                // leads away from it (a shared error path to an end) may be shared: the join never waits for it
                val owners = mutableMapOf<String, Int>()
                p.branches.forEachIndexed { i, b ->
                    (reach(b, out, stop = p.join) - p.join).filter { p.join in reach(it, out) }.forEach { id ->
                        val first = owners.putIfAbsent(id, i)
                        if (first != null && first != i) {
                            v += FieldViolation(graph.step(id)!!.path, "in branches[$first] and branches[$i] of '${p.id}': branches meet only at the join")
                        }
                    }
                }
            }
            // a step reads only what ran before it: the trigger, or a step upstream of it that has an output
            val upstream = ancestors(graph, out)
            graph.steps.forEach { step ->
                step.refs().forEach { (ref, at) ->
                    val source = ref.stepId ?: return@forEach
                    val target = byId[source]
                    when {
                        target == null -> v += FieldViolation(at, "no step '$source'")
                        target !is Step.Action && target !is Step.Approval -> v += FieldViolation(at, "step '$source' has no output")
                        source !in upstream.getValue(step.id) -> v += FieldViolation(at, "step '$source' does not run before '${step.id}'")
                    }
                }
            }
        }

        private fun reach(
            from: String,
            out: Map<String, List<String>>,
            stop: String? = null
        ): Set<String> {
            val seen = mutableSetOf<String>()
            val stack = ArrayDeque(listOf(from))
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                if (seen.add(id) && id != stop) out[id].orEmpty().forEach(stack::addLast)
            }
            return seen
        }

        // no loops in the MVP: every edge that closes a cycle is a violation on that edge
        private fun cycles(
            graph: WorkflowGraph,
            v: MutableList<FieldViolation>
        ) {
            val state = mutableMapOf<String, Int>()

            fun visit(id: String) {
                state[id] = 1
                val step = graph.step(id)!!
                step.edges().forEach { (field, target) ->
                    when (state[target]) {
                        1 -> v += FieldViolation("${step.path}.$field", "closes a cycle through '$target': loops are not supported")
                        null -> visit(target)
                    }
                }
                state[id] = 2
            }
            graph.steps.forEach { if (it.id !in state) visit(it.id) }
        }

        private fun ancestors(
            graph: WorkflowGraph,
            out: Map<String, List<String>>
        ): Map<String, Set<String>> {
            val into = mutableMapOf<String, MutableList<String>>()
            out.forEach { (from, targets) -> targets.forEach { into.getOrPut(it) { mutableListOf() } += from } }
            return graph.steps.associate { it.id to (reach(it.id, into) - it.id) }
        }

        private fun connections(
            graph: WorkflowGraph,
            tenant: Set<UUID>,
            connectors: Map<UUID, Connector>,
            v: MutableList<FieldViolation>
        ) {
            fun connector(
                id: UUID,
                at: String
            ): Connector? {
                if (id !in tenant) {
                    v += FieldViolation(at, "unknown connection")
                    return null
                }
                return connectors[id] ?: run {
                    v += FieldViolation(at, "the connection's connector is not installed")
                    null
                }
            }
            (graph.trigger as? Trigger.Event)?.let { t ->
                val c = connector(t.connectionId, "graph.trigger.connectionId") ?: return@let
                val webhooks = c.manifest.webhooks
                if (!c.manifest.supports(Capability.WEBHOOKS) || webhooks == null) {
                    v += FieldViolation("graph.trigger.connectionId", "connector '${c.manifest.id}' sends no events")
                } else if (t.eventType !in webhooks.events) {
                    v += FieldViolation("graph.trigger.eventType", "connector '${c.manifest.id}' declares no event '${t.eventType}'")
                }
            }
            graph.steps.filterIsInstance<Step.Action>().forEach { step ->
                val c = connector(step.connectionId, "${step.path}.connectionId") ?: return@forEach
                val m = c.manifest
                step.action?.let { action ->
                    if (!m.supports(Capability.ACTIONS) || c !is ActionExecutor) {
                        v += FieldViolation("${step.path}.action", "connector '${m.id}' runs no actions")
                    } else if (m.actions.none { it.name == action }) {
                        v += FieldViolation("${step.path}.action", "connector '${m.id}' declares no action '$action'")
                    }
                }
                step.write?.let { write ->
                    val upsert = write.operation == WriteOperation.UPSERT
                    if (upsert && (!m.supports(Capability.UPSERT) || c !is ResourceUpserter)) {
                        v += FieldViolation("${step.path}.write.operation", "connector '${m.id}' cannot upsert records")
                    } else if (!upsert && (!m.supports(Capability.WRITE) || c !is ResourceWriter)) {
                        v += FieldViolation("${step.path}.write.operation", "connector '${m.id}' cannot write records")
                    }
                    // a dynamic declaration (or none, with discovery) leaves the resource to describe()
                    val declared = m.resources.any { it.name == write.resource }
                    val open = m.resources.any { it.dynamic } || (m.resources.isEmpty() && c is ResourceDiscovery)
                    if (!declared && !open) v += FieldViolation("${step.path}.write.resource", "connector '${m.id}' declares no resource '${write.resource}'")
                }
            }
        }

        // a write's input against the resource schema: fields exist and are writable, literals fit the type,
        // a create sets every required field. a reference's type is only known at run time
        fun againstSchema(
            step: Step.Action,
            write: ResourceWrite,
            schema: ResourceSchema,
            upserts: Boolean
        ): List<FieldViolation> {
            val v = mutableListOf<FieldViolation>()
            step.input.forEach { (name, operand) ->
                val at = "${step.path}.input.$name"
                val field = schema.field(name)
                when {
                    field == null -> v += FieldViolation(at, "no field '$name' in ${schema.label}")
                    field.readOnly -> v += FieldViolation(at, "'$name' is read-only")
                    operand is Operand.Literal -> fit(field, operand.value)?.let { v += FieldViolation("$at.value", it) }
                }
            }
            if (write.operation == WriteOperation.CREATE) {
                schema.fields
                    .filter { it.required && !it.readOnly && it.name !in step.input }
                    .forEach { v += FieldViolation("${step.path}.input", "required field '${it.name}' is not set") }
            }
            write.externalIdField?.let { name ->
                val field = schema.field(name)
                val at = "${step.path}.write.externalIdField"
                when {
                    field == null -> v += FieldViolation(at, "no field '$name' in ${schema.label}")
                    upserts && !field.externalId -> v += FieldViolation(at, "'$name' is not an external id field of ${schema.label}")
                }
            }
            return v
        }

        // null when the literal fits the field type
        fun fit(
            field: FieldDescriptor,
            value: JsonNode
        ): String? {
            if (value.isNull) return if (field.required) "'${field.name}' is required: not null" else null
            val ok =
                when (field.type) {
                    FieldType.TEXT, FieldType.REFERENCE -> value.isString
                    FieldType.CHOICE -> value.isString && (field.options.isEmpty() || value.stringValue() in field.options)
                    FieldType.MULTI_CHOICE ->
                        value.isArray &&
                            (0 until value.size()).all { i ->
                                value.get(i).isString && (field.options.isEmpty() || value.get(i).stringValue() in field.options)
                            }
                    FieldType.NUMBER -> value.isNumber
                    FieldType.BOOLEAN -> value.isBoolean
                    FieldType.DATE -> value.isString && runCatching { LocalDate.parse(value.stringValue()) }.isSuccess
                    FieldType.DATETIME ->
                        value.isString &&
                            (
                                runCatching {
                                    OffsetDateTime.parse(
                                        value.stringValue()
                                    )
                                }.isSuccess || runCatching { Instant.parse(value.stringValue()) }.isSuccess
                            )
                    FieldType.JSON -> true
                }
            if (ok) return null
            return if (field.options.isNotEmpty() && (field.type == FieldType.CHOICE || field.type == FieldType.MULTI_CHOICE)) {
                "not ${field.type} of '${field.name}': one of ${field.options.joinToString()}"
            } else {
                "does not fit ${field.type} field '${field.name}'"
            }
        }
    }
}
