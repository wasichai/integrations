package wasichai.integrations.workflows

import tools.jackson.databind.JsonNode
import wasichai.core.common.FieldViolation
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// a value the run produced: the trigger's (trigger.payload.email) or an earlier step's output
// (steps.lookup.output.id). a path, never an expression.
data class Ref(
    val path: String
) {
    // null for the trigger
    val stepId: String? get() = if (path.startsWith("steps.")) path.removePrefix("steps.").substringBefore('.') else null

    companion object {
        private const val SEGMENT = "[A-Za-z0-9_-]{1,100}"
        private val PATH = Regex("""^(trigger|steps\.${WorkflowGraph.ID_PATTERN})(\.$SEGMENT)*$""")

        fun parse(
            node: JsonNode?,
            path: String,
            v: MutableList<FieldViolation>
        ): Ref? {
            val text = node?.takeIf { it.isString }?.stringValue()
            if (text == null || text.length > 300 || !PATH.matches(text)) {
                v += FieldViolation(path, "a reference: trigger[.field...] or steps.<id>[.field...]")
                return null
            }
            return Ref(text)
        }
    }
}

// the bound of gt / lt: numbers compare with numbers, dates with dates, date-times with date-times
sealed interface Bound {
    data class Number(
        val value: BigDecimal
    ) : Bound

    data class Day(
        val value: LocalDate
    ) : Bound

    data class Moment(
        val value: Instant
    ) : Bound

    companion object {
        fun parse(node: JsonNode?): Bound? =
            when {
                node == null -> null
                node.isNumber -> Number(node.decimalValue())
                node.isString ->
                    node.stringValue().let { s ->
                        runCatching { Day(LocalDate.parse(s)) }.getOrNull()
                            ?: runCatching { Moment(OffsetDateTime.parse(s).toInstant()) }.getOrNull()
                    }
                else -> null
            }
    }
}

// the closed set of conditions (ADR-0022), like sync's closed transform set: no expression language, no code.
// a comparison is always a reference against literal values.
sealed interface Condition {
    // the references it reads, for the validator
    fun refs(): List<Pair<Ref, String>>

    data class Equals(
        val ref: Ref,
        val value: JsonNode,
        val at: String
    ) : Condition {
        override fun refs() = listOf(ref to "$at.ref")
    }

    data class NotEquals(
        val ref: Ref,
        val value: JsonNode,
        val at: String
    ) : Condition {
        override fun refs() = listOf(ref to "$at.ref")
    }

    data class Exists(
        val ref: Ref,
        val at: String
    ) : Condition {
        override fun refs() = listOf(ref to "$at.ref")
    }

    data class In(
        val ref: Ref,
        val values: List<JsonNode>,
        val at: String
    ) : Condition {
        override fun refs() = listOf(ref to "$at.ref")
    }

    data class GreaterThan(
        val ref: Ref,
        val bound: Bound,
        val at: String
    ) : Condition {
        override fun refs() = listOf(ref to "$at.ref")
    }

    data class LessThan(
        val ref: Ref,
        val bound: Bound,
        val at: String
    ) : Condition {
        override fun refs() = listOf(ref to "$at.ref")
    }

    data class And(
        val conditions: List<Condition>
    ) : Condition {
        override fun refs() = conditions.flatMap { it.refs() }
    }

    data class Or(
        val conditions: List<Condition>
    ) : Condition {
        override fun refs() = conditions.flatMap { it.refs() }
    }

    data class Not(
        val condition: Condition
    ) : Condition {
        override fun refs() = condition.refs()
    }

    companion object {
        const val MAX_DEPTH = 8
        const val MAX_VALUES = 100
        val OPERATORS = listOf("equals", "notEquals", "exists", "in", "gt", "lt", "and", "or", "not")

        // the condition as sent: {"op": "equals", "ref": "trigger.payload.status", "value": "open"} ...
        // a problem is a violation on its field path; null when it does not parse
        fun parse(
            node: JsonNode?,
            path: String,
            v: MutableList<FieldViolation>,
            depth: Int = 1
        ): Condition? {
            if (node == null || !node.isObject) {
                v += FieldViolation(path, "a condition object with an 'op'")
                return null
            }
            if (depth > MAX_DEPTH) {
                v += FieldViolation(path, "conditions nest at most $MAX_DEPTH deep")
                return null
            }
            val op = node.get("op")?.takeIf { it.isString }?.stringValue()
            return when (op) {
                "equals", "notEquals" -> {
                    val ref = Ref.parse(node.get("ref"), "$path.ref", v)
                    val value = literal(node.get("value"), "$path.value", v)
                    if (ref == null || value == null) {
                        null
                    } else if (op == "equals") {
                        Equals(ref, value, path)
                    } else {
                        NotEquals(ref, value, path)
                    }
                }
                "exists" -> Ref.parse(node.get("ref"), "$path.ref", v)?.let { Exists(it, path) }
                "in" -> {
                    val ref = Ref.parse(node.get("ref"), "$path.ref", v)
                    val values = node.get("values")
                    if (values == null || !values.isArray || values.isEmpty || values.size() > MAX_VALUES) {
                        v += FieldViolation("$path.values", "1 to $MAX_VALUES literal values")
                        return null
                    }
                    val parsed = (0 until values.size()).map { literal(values.get(it), "$path.values[$it]", v) }
                    if (ref == null || parsed.any { it == null }) null else In(ref, parsed.filterNotNull(), path)
                }
                "gt", "lt" -> {
                    val ref = Ref.parse(node.get("ref"), "$path.ref", v)
                    val bound = Bound.parse(node.get("value"))
                    if (bound == null) v += FieldViolation("$path.value", "a number, an ISO date or an ISO date-time with offset")
                    if (ref == null || bound == null) {
                        null
                    } else if (op == "gt") {
                        GreaterThan(ref, bound, path)
                    } else {
                        LessThan(ref, bound, path)
                    }
                }
                "and", "or" -> {
                    val list = node.get("conditions")
                    if (list == null || !list.isArray || list.isEmpty || list.size() > MAX_VALUES) {
                        v += FieldViolation("$path.conditions", "1 to $MAX_VALUES conditions")
                        return null
                    }
                    val parsed = (0 until list.size()).map { parse(list.get(it), "$path.conditions[$it]", v, depth + 1) }
                    when {
                        parsed.any { it == null } -> null
                        op == "and" -> And(parsed.filterNotNull())
                        else -> Or(parsed.filterNotNull())
                    }
                }
                "not" -> parse(node.get("condition"), "$path.condition", v, depth + 1)?.let { Not(it) }
                else -> {
                    v += FieldViolation("$path.op", "one of ${OPERATORS.joinToString()}")
                    null
                }
            }
        }

        // a plain json value to compare with: no objects, nothing that looks like code
        private fun literal(
            node: JsonNode?,
            path: String,
            v: MutableList<FieldViolation>
        ): JsonNode? {
            if (node == null || node.isMissingNode || node.isContainer) {
                v += FieldViolation(path, "a literal: string, number, boolean or null")
                return null
            }
            return node
        }
    }
}
