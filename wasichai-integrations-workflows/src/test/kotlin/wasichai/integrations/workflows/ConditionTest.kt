package wasichai.integrations.workflows

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.FieldViolation
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class ConditionTest {
    private val json = JsonMapper.builder().build()

    private fun parse(spec: Any?): Pair<Condition?, List<FieldViolation>> {
        val v = mutableListOf<FieldViolation>()
        return Condition.parse(json.valueToTree(spec), "c", v) to v
    }

    private fun ok(spec: Any?): Condition {
        val (c, v) = parse(spec)
        assertEquals(emptyList<FieldViolation>(), v)
        return c!!
    }

    private fun fields(spec: Any?): Set<String> {
        val (c, v) = parse(spec)
        assertNull(c)
        return v.map { it.field }.toSet()
    }

    @Test
    fun `every operator of the closed set parses`() {
        assertInstanceOf(Condition.Equals::class.java, ok(mapOf("op" to "equals", "ref" to "trigger.payload.status", "value" to "open")))
        assertInstanceOf(Condition.NotEquals::class.java, ok(mapOf("op" to "notEquals", "ref" to "steps.find.output.id", "value" to null)))
        assertInstanceOf(Condition.Exists::class.java, ok(mapOf("op" to "exists", "ref" to "trigger")))
        val inList = ok(mapOf("op" to "in", "ref" to "trigger.tier", "values" to listOf("gold", "silver", 3, true))) as Condition.In
        assertEquals(4, inList.values.size)
        assertEquals(Bound.Number(BigDecimal("10.5")), (ok(mapOf("op" to "gt", "ref" to "trigger.amount", "value" to 10.5)) as Condition.GreaterThan).bound)
        assertEquals(
            Bound.Day(LocalDate.parse("2026-11-05")),
            (ok(mapOf("op" to "lt", "ref" to "trigger.due", "value" to "2026-11-05")) as Condition.LessThan).bound
        )
        assertEquals(
            Bound.Moment(Instant.parse("2026-11-05T10:00:00Z")),
            (ok(mapOf("op" to "gt", "ref" to "trigger.at", "value" to "2026-11-05T12:00:00+02:00")) as Condition.GreaterThan).bound
        )
        val nested =
            ok(
                mapOf(
                    "op" to "and",
                    "conditions" to
                        listOf(
                            mapOf("op" to "exists", "ref" to "trigger.a"),
                            mapOf("op" to "or", "conditions" to listOf(mapOf("op" to "exists", "ref" to "steps.x.b"))),
                            mapOf("op" to "not", "condition" to mapOf("op" to "exists", "ref" to "trigger.c"))
                        )
                )
            )
        assertEquals(listOf("trigger.a", "steps.x.b", "trigger.c"), nested.refs().map { it.first.path })
        assertEquals("c.conditions[1].conditions[0].ref", nested.refs()[1].second)
        assertEquals("x", nested.refs()[1].first.stepId)
        assertNull(nested.refs()[0].first.stepId)
    }

    @Test
    fun `an unknown operator or a non-object is refused, never evaluated`() {
        assertEquals(setOf("c.op"), fields(mapOf("op" to "matches", "ref" to "trigger.a", "value" to ".*")))
        assertEquals(setOf("c.op"), fields(mapOf("ref" to "trigger.a")))
        assertEquals(setOf("c"), fields("trigger.a == 1"))
        assertEquals(setOf("c"), fields(null))
    }

    @Test
    fun `references are paths into the trigger or a step`() {
        assertEquals(setOf("c.ref"), fields(mapOf("op" to "exists", "ref" to "payload.email")))
        assertEquals(setOf("c.ref"), fields(mapOf("op" to "exists", "ref" to "steps.")))
        assertEquals(setOf("c.ref"), fields(mapOf("op" to "exists", "ref" to "trigger.a b")))
        assertEquals(setOf("c.ref"), fields(mapOf("op" to "exists", "ref" to "steps.9x.out")))
        assertEquals(setOf("c.ref"), fields(mapOf("op" to "exists", "ref" to 3)))
    }

    @Test
    fun `values are literals and bounds are numbers or dates`() {
        assertEquals(setOf("c.value"), fields(mapOf("op" to "equals", "ref" to "trigger.a", "value" to mapOf("x" to 1))))
        assertEquals(setOf("c.value"), fields(mapOf("op" to "equals", "ref" to "trigger.a")))
        assertEquals(setOf("c.values"), fields(mapOf("op" to "in", "ref" to "trigger.a", "values" to emptyList<Any>())))
        assertEquals(setOf("c.values[1]"), fields(mapOf("op" to "in", "ref" to "trigger.a", "values" to listOf("a", listOf("b")))))
        assertEquals(setOf("c.value"), fields(mapOf("op" to "gt", "ref" to "trigger.a", "value" to "soon")))
        assertEquals(setOf("c.value"), fields(mapOf("op" to "lt", "ref" to "trigger.a", "value" to true)))
    }

    @Test
    fun `and, or and not need their operands, and nesting is bounded`() {
        assertEquals(setOf("c.conditions"), fields(mapOf("op" to "and", "conditions" to emptyList<Any>())))
        assertEquals(setOf("c.conditions"), fields(mapOf("op" to "or")))
        assertEquals(setOf("c.condition"), fields(mapOf("op" to "not")))
        assertEquals(
            setOf("c.conditions[1].op"),
            fields(
                mapOf(
                    "op" to "or",
                    "conditions" to listOf(mapOf("op" to "exists", "ref" to "trigger"), mapOf("op" to "?"))
                )
            )
        )
        var deep: Map<String, Any> = mapOf("op" to "exists", "ref" to "trigger")
        repeat(Condition.MAX_DEPTH) { deep = mapOf("op" to "not", "condition" to deep) }
        val problems = fields(deep)
        assertEquals(1, problems.size)
        assertTrue(problems.single().endsWith(".condition"), problems.toString())
    }
}
