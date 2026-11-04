package wasichai.integrations.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.FieldViolation
import wasichai.integrations.sdk.FieldValue
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class TransformsTest {
    private val json = JsonMapper.builder().build()

    private fun parse(spec: String): Pair<Transform?, List<FieldViolation>> {
        val v = mutableListOf<FieldViolation>()
        return Transform.parse(json.readTree(spec), "t", v) to v
    }

    @Test
    fun `copy is the identity both ways`() {
        val t = parse("""{"type":"copy"}""").first!!
        assertEquals(FieldValue.Text("x"), t.toB(FieldValue.Text("x")))
        assertEquals(FieldValue.Text("x"), t.toA(FieldValue.Text("x")))
        assertEquals(Transform.Copy, Transform.parse(null, "t", mutableListOf()))
    }

    @Test
    fun `static writes its value to B and is not invertible`() {
        val t = parse("""{"type":"static","value":"Partner"}""").first!!
        assertEquals(FieldValue.Text("Partner"), t.toB(FieldValue.Text("anything")))
        assertFalse(t.invertible)
    }

    @Test
    fun `default fills only nulls`() {
        val t = parse("""{"type":"default","value":5}""").first!!
        assertEquals(FieldValue.Number(BigDecimal(5)), t.toB(FieldValue.Null))
        assertEquals(FieldValue.Text("k"), t.toB(FieldValue.Text("k")))
        assertEquals(FieldValue.Null, t.toA(FieldValue.Null))
    }

    @Test
    fun `enum map maps both ways and never guesses`() {
        val t = parse("""{"type":"enumMap","map":{"Energy":"ENERGY","Oil":"ENERGY","Retail":"RETAIL"}}""").first!!
        assertEquals(FieldValue.Choice("ENERGY"), t.toB(FieldValue.Choice("Energy")))
        assertEquals(FieldValue.Choice("RETAIL"), t.toB(FieldValue.Text("Retail")))
        assertEquals(FieldValue.Null, t.toB(FieldValue.Choice("Mining")))
        assertEquals(FieldValue.Choice("Energy"), t.toA(FieldValue.Choice("ENERGY")), "first A value naming ENERGY")
        assertEquals(FieldValue.Null, t.toA(FieldValue.Choice("OTHER")))
    }

    @Test
    fun `date time converts between a date and an instant in the zone`() {
        val t = parse("""{"type":"dateTime","zone":"America/Lima","a":"DATETIME","b":"DATE"}""").first!!
        val instant = Instant.parse("2026-01-02T03:00:00Z")
        assertEquals(FieldValue.Date(LocalDate.of(2026, 1, 1)), t.toB(FieldValue.DateTime(instant)))
        assertEquals(
            FieldValue.DateTime(LocalDate.of(2026, 1, 1).atStartOfDay(ZoneId.of("America/Lima")).toInstant()),
            t.toA(FieldValue.Date(LocalDate.of(2026, 1, 1)))
        )
        assertTrue(parse("""{"type":"dateTime","zone":"Mars/Base"}""").second.isNotEmpty())
    }

    @Test
    fun `number scales half up toward B`() {
        val t = parse("""{"type":"number","scale":2}""").first!!
        assertEquals(FieldValue.Number(BigDecimal("1.24")), t.toB(FieldValue.Number(BigDecimal("1.235"))))
        assertEquals(FieldValue.Number(BigDecimal("3.00")), t.toB(FieldValue.Text("3")))
        assertEquals(FieldValue.Null, t.toB(FieldValue.Text("three")))
        assertEquals(FieldValue.Number(BigDecimal("1.235")), t.toA(FieldValue.Number(BigDecimal("1.235"))))
        assertTrue(parse("""{"type":"number","scale":99}""").second.isNotEmpty())
    }

    @Test
    fun `conditional picks by equality and only flows to B`() {
        val t = parse("""{"type":"conditional","equals":true,"then":"Active","else":"Inactive"}""").first!!
        assertEquals(FieldValue.Text("Active"), t.toB(FieldValue.Bool(true)))
        assertEquals(FieldValue.Text("Inactive"), t.toB(FieldValue.Bool(false)))
        assertFalse(t.invertible)
    }

    @Test
    fun `unknown transforms are violations, not exceptions`() {
        val (t, v) = parse("""{"type":"script","code":"rm -rf"}""")
        assertNull(t)
        assertEquals("t.type", v.single().field)
    }
}
