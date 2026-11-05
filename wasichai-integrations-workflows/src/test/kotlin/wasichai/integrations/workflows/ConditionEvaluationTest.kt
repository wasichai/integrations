package wasichai.integrations.workflows

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.FieldViolation

class ConditionEvaluationTest {
    private val json = JsonMapper.builder().build()

    private val context =
        RunContext(
            json.valueToTree(
                mapOf(
                    "type" to "event",
                    "payload" to
                        mapOf(
                            "status" to "open",
                            "amount" to 10.5,
                            "count" to 3,
                            "text" to "42",
                            "vip" to true,
                            "none" to null,
                            "due" to "2026-11-05",
                            "at" to "2026-11-05T10:00:00Z",
                            "tags" to listOf("a", "b"),
                            "nested" to mapOf("id" to "x1")
                        )
                )
            ),
            mapOf("lookup" to json.valueToTree(mapOf("id" to "c-1", "score" to 7)), "silent" to null)
        )

    private fun holds(spec: Any): Boolean {
        val v = mutableListOf<FieldViolation>()
        val condition = Condition.parse(json.valueToTree(spec), "c", v) ?: throw AssertionError(v.toString())
        return Conditions.holds(condition, context)
    }

    private fun op(
        op: String,
        ref: String,
        value: Any?
    ) = mapOf("op" to op, "ref" to ref, "value" to value)

    @Test
    fun `refs read the trigger and finished steps' outputs, and resolve to nothing otherwise`() {
        assertEquals("open", context.resolve(Ref("trigger.payload.status"))!!.stringValue())
        assertEquals("x1", context.resolve(Ref("trigger.payload.nested.id"))!!.stringValue())
        assertEquals("b", context.resolve(Ref("trigger.payload.tags.1"))!!.stringValue())
        assertEquals("event", context.resolve(Ref("trigger.type"))!!.stringValue())
        assertEquals("c-1", context.resolve(Ref("steps.lookup.output.id"))!!.stringValue())
        assertTrue(context.resolve(Ref("steps.silent.output"))!!.isNull)
        assertNull(context.resolve(Ref("steps.never.output.id")))
        assertNull(context.resolve(Ref("trigger.payload.missing.deeper")))
        assertNull(context.resolve(Ref("trigger.payload.status.length")))
        assertNull(context.resolve(Ref("steps.lookup.id")))
    }

    @Test
    fun `equals and notEquals compare scalars, numbers by value, missing as null`() {
        assertTrue(holds(op("equals", "trigger.payload.status", "open")))
        assertFalse(holds(op("equals", "trigger.payload.status", "closed")))
        assertTrue(holds(op("equals", "trigger.payload.count", 3.0)))
        assertFalse(holds(op("equals", "trigger.payload.text", 42)))
        assertTrue(holds(op("equals", "trigger.payload.vip", true)))
        assertTrue(holds(op("equals", "trigger.payload.none", null)))
        assertTrue(holds(op("equals", "trigger.payload.missing", null)))
        assertFalse(holds(op("equals", "trigger.payload.tags", "a")))
        assertTrue(holds(op("notEquals", "trigger.payload.status", "closed")))
        assertFalse(holds(op("notEquals", "steps.lookup.output.id", "c-1")))
        assertTrue(holds(op("notEquals", "trigger.payload.missing", "x")))
    }

    @Test
    fun `exists is a value that is there and not null`() {
        assertTrue(holds(mapOf("op" to "exists", "ref" to "trigger.payload.status")))
        assertTrue(holds(mapOf("op" to "exists", "ref" to "trigger.payload.tags")))
        assertFalse(holds(mapOf("op" to "exists", "ref" to "trigger.payload.none")))
        assertFalse(holds(mapOf("op" to "exists", "ref" to "trigger.payload.missing")))
        assertFalse(holds(mapOf("op" to "exists", "ref" to "steps.never.output")))
    }

    @Test
    fun `in is equals against any of the values`() {
        assertTrue(holds(mapOf("op" to "in", "ref" to "trigger.payload.status", "values" to listOf("new", "open"))))
        assertFalse(holds(mapOf("op" to "in", "ref" to "trigger.payload.status", "values" to listOf("new", "closed"))))
        assertTrue(holds(mapOf("op" to "in", "ref" to "steps.lookup.output.score", "values" to listOf(1, 7))))
        assertTrue(holds(mapOf("op" to "in", "ref" to "trigger.payload.missing", "values" to listOf("x", null))))
    }

    @Test
    fun `gt and lt compare numbers, dates and date-times, and are false when they cannot compare`() {
        assertTrue(holds(op("gt", "trigger.payload.amount", 10)))
        assertFalse(holds(op("gt", "trigger.payload.amount", 10.5)))
        assertTrue(holds(op("lt", "trigger.payload.amount", 11)))
        assertTrue(holds(op("gt", "trigger.payload.text", 41)))
        assertTrue(holds(op("gt", "trigger.payload.due", "2026-11-04")))
        assertFalse(holds(op("lt", "trigger.payload.due", "2026-11-05")))
        assertTrue(holds(op("lt", "trigger.payload.at", "2026-11-05T12:00:00+01:00")))
        assertFalse(holds(op("gt", "trigger.payload.at", "2026-11-05T12:00:00+02:00")))
        assertFalse(holds(op("gt", "trigger.payload.missing", 1)))
        assertFalse(holds(op("lt", "trigger.payload.missing", 1)))
        assertFalse(holds(op("gt", "trigger.payload.status", 1)))
        assertFalse(holds(op("gt", "trigger.payload.amount", "2026-01-01")))
        assertFalse(holds(op("lt", "trigger.payload.vip", 2)))
    }

    @Test
    fun `and, or and not combine`() {
        val yes = mapOf("op" to "exists", "ref" to "trigger.payload.status")
        val no = mapOf("op" to "exists", "ref" to "trigger.payload.missing")
        assertTrue(holds(mapOf("op" to "and", "conditions" to listOf(yes, yes))))
        assertFalse(holds(mapOf("op" to "and", "conditions" to listOf(yes, no))))
        assertTrue(holds(mapOf("op" to "or", "conditions" to listOf(no, yes))))
        assertFalse(holds(mapOf("op" to "or", "conditions" to listOf(no, no))))
        assertTrue(holds(mapOf("op" to "not", "condition" to no)))
        assertFalse(holds(mapOf("op" to "not", "condition" to yes)))
        // a comparison that cannot be made is false, so its negation holds
        assertTrue(holds(mapOf("op" to "not", "condition" to op("gt", "trigger.payload.missing", 1))))
    }

    @Test
    fun `an action's input resolves refs and keeps literals`() {
        val input =
            context.input(
                mapOf(
                    "id" to Operand.Reference(Ref("steps.lookup.output.id")),
                    "gone" to Operand.Reference(Ref("trigger.payload.missing")),
                    "tier" to Operand.Literal(json.valueToTree("gold"))
                )
            )
        assertEquals("c-1", input.get("id").stringValue())
        assertTrue(input.get("gone").isNull)
        assertEquals("gold", input.get("tier").stringValue())
    }
}
