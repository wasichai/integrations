package wasichai.integrations.workflows

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// what a run has produced so far, as refs read it: trigger.<path> into the trigger object ({type, payload, ...}),
// steps.<id>.<path> into {"output": ...} of a finished step. a path that leads nowhere resolves to null
class RunContext(
    private val trigger: JsonNode,
    private val outputs: Map<String, JsonNode?>
) {
    fun resolve(ref: Ref): JsonNode? {
        val segments = ref.path.split('.')
        var node: JsonNode? =
            if (segments[0] == "trigger") {
                trigger
            } else {
                val id = segments[1]
                if (id !in outputs) return null
                JsonNodeFactory.instance.objectNode().set("output", outputs[id] ?: JsonNodeFactory.instance.nullNode())
            }
        val rest = if (segments[0] == "trigger") segments.drop(1) else segments.drop(2)
        for (segment in rest) {
            node =
                when {
                    node == null -> return null
                    node.isObject -> node.get(segment)
                    node.isArray -> segment.toIntOrNull()?.let { node.get(it) }
                    else -> return null
                }
        }
        return node?.takeUnless { it.isMissingNode }
    }

    // an action's input as json: refs resolved, a ref that resolves to nothing is null
    fun input(input: Map<String, Operand>): ObjectNode {
        val node = JsonNodeFactory.instance.objectNode()
        input.forEach { (k, o) -> node.set(k, value(o) ?: JsonNodeFactory.instance.nullNode()) }
        return node
    }

    fun value(operand: Operand): JsonNode? =
        when (operand) {
            is Operand.Literal -> operand.value
            is Operand.Reference -> resolve(operand.ref)
        }
}

// evaluation of the closed condition set (ADR-0022). total: a missing value or a type that does not compare makes a
// comparison false, never an error. so `not` of it is true: "not (amount > 10)" holds when there is no amount
object Conditions {
    fun holds(
        condition: Condition,
        context: RunContext
    ): Boolean =
        when (condition) {
            is Condition.Equals -> same(context.resolve(condition.ref), condition.value)
            is Condition.NotEquals -> !same(context.resolve(condition.ref), condition.value)
            is Condition.Exists -> context.resolve(condition.ref).let { it != null && !it.isNull }
            is Condition.In -> context.resolve(condition.ref).let { v -> condition.values.any { same(v, it) } }
            is Condition.GreaterThan -> compare(context.resolve(condition.ref), condition.bound)?.let { it > 0 } ?: false
            is Condition.LessThan -> compare(context.resolve(condition.ref), condition.bound)?.let { it < 0 } ?: false
            is Condition.And -> condition.conditions.all { holds(it, context) }
            is Condition.Or -> condition.conditions.any { holds(it, context) }
            is Condition.Not -> !holds(condition.condition, context)
        }

    // a missing value equals null; numbers by value (1 == 1.0); otherwise json equality of scalars
    fun same(
        value: JsonNode?,
        literal: JsonNode
    ): Boolean {
        if (value == null || value.isNull) return literal.isNull
        if (value.isNumber && literal.isNumber) return value.decimalValue().compareTo(literal.decimalValue()) == 0
        if (value.isContainer) return false
        return value == literal
    }

    // value against the bound: negative, zero, positive; null when they do not compare
    fun compare(
        value: JsonNode?,
        bound: Bound
    ): Int? {
        if (value == null || value.isNull) return null
        return when (bound) {
            is Bound.Number -> number(value)?.compareTo(bound.value)
            is Bound.Day -> value.takeIf { it.isString }?.let { runCatching { LocalDate.parse(it.stringValue()) }.getOrNull() }?.compareTo(bound.value)
            is Bound.Moment -> value.takeIf { it.isString }?.let { moment(it.stringValue()) }?.compareTo(bound.value)
        }
    }

    // a number, or a string that is one (providers send amounts as text)
    private fun number(value: JsonNode): BigDecimal? =
        when {
            value.isNumber -> value.decimalValue()
            value.isString -> value.stringValue().trim().toBigDecimalOrNull()
            else -> null
        }

    private fun moment(text: String): Instant? =
        runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull() ?: runCatching { Instant.parse(text) }.getOrNull()
}
