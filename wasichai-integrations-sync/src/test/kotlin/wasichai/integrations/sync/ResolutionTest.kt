package wasichai.integrations.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.FieldViolation
import wasichai.integrations.sdk.FieldValue
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class ResolutionTest {
    private val json = JsonMapper.builder().build()

    private fun mapping(transform: String) = FieldMapping("a", "b", json.readTree(transform))

    private fun pickB(
        transform: String,
        a: FieldValue,
        b: FieldValue
    ): Pair<FieldValue?, List<FieldViolation>> {
        val m = mapping(transform)
        val v = mutableListOf<FieldViolation>()
        return Resolution.chooseA(m, Transform.of(m), a, b, null, null, "B", v) to v
    }

    @Test
    fun `picking B goes back through each invertible transform`() {
        assertEquals(FieldValue.Text("b"), pickB("""{"type":"copy"}""", FieldValue.Text("a"), FieldValue.Text("b")).first)
        assertEquals(FieldValue.Text("b"), pickB("""{"type":"default","value":"none"}""", FieldValue.Null, FieldValue.Text("b")).first)
        assertEquals(
            FieldValue.Choice("silver"),
            pickB("""{"type":"enumMap","map":{"gold":"GOLD","silver":"SILVER"}}""", FieldValue.Choice("gold"), FieldValue.Choice("SILVER")).first
        )
        assertEquals(
            FieldValue.Date(LocalDate.parse("2026-03-01")),
            pickB(
                """{"type":"dateTime","zone":"UTC","a":"DATE","b":"DATETIME"}""",
                FieldValue.Date(LocalDate.parse("2026-01-01")),
                FieldValue.DateTime(Instant.parse("2026-03-01T10:00:00Z"))
            ).first
        )
        assertEquals(
            FieldValue.Number(BigDecimal("2.345")),
            pickB("""{"type":"number","scale":1}""", FieldValue.Number(BigDecimal.ONE), FieldValue.Number(BigDecimal("2.345"))).first
        )
    }

    @Test
    fun `an enum value B has with no A value becomes null, never a guess`() {
        assertEquals(FieldValue.Null, pickB("""{"type":"enumMap","map":{"gold":"GOLD"}}""", FieldValue.Choice("gold"), FieldValue.Choice("BRONZE")).first)
    }

    @Test
    fun `picking B on a transform without an inverse is refused`() {
        for (spec in listOf("""{"type":"static","value":"x"}""", """{"type":"conditional","equals":"a","then":"yes","else":"no"}""")) {
            val (value, violations) = pickB(spec, FieldValue.Text("a"), FieldValue.Text("yes"))
            assertNull(value)
            assertEquals("fields.a->b", violations.single().field, spec)
        }
    }

    @Test
    fun `an explicit value wins, A is taken as it is, and nothing is a violation`() {
        val m = mapping("""{"type":"enumMap","map":{"gold":"GOLD"}}""")
        val t = Transform.of(m)
        val v = mutableListOf<FieldViolation>()
        assertEquals(FieldValue.Text("typed"), Resolution.chooseA(m, t, FieldValue.Choice("gold"), FieldValue.Null, null, json.readTree("\"typed\""), "B", v))
        assertEquals(FieldValue.Choice("gold"), Resolution.chooseA(m, t, FieldValue.Choice("gold"), FieldValue.Null, null, null, "a", v))
        assertEquals(FieldValue.Choice("gold"), Resolution.chooseA(m, t, FieldValue.Choice("gold"), FieldValue.Choice("SILVER"), Side.A, null, null, v))
        assertTrue(v.isEmpty(), v.toString())
        assertNull(Resolution.chooseA(m, t, FieldValue.Null, FieldValue.Null, null, null, null, v))
        assertNull(Resolution.chooseA(m, t, FieldValue.Null, FieldValue.Null, null, null, "C", v))
        assertEquals(listOf("values.a", "fields.a->b"), v.map { it.field })
    }
}
