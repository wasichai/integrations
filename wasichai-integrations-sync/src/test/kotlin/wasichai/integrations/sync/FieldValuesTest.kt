package wasichai.integrations.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.FieldValue
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class FieldValuesTest {
    @Test
    fun `every value round-trips through its stored form`() {
        listOf(
            FieldValue.Null,
            FieldValue.Text("a"),
            FieldValue.Number(BigDecimal("1.5")),
            FieldValue.Bool(true),
            FieldValue.Date(LocalDate.of(2026, 1, 2)),
            FieldValue.DateTime(Instant.parse("2026-01-02T03:04:05Z")),
            FieldValue.Choice("x"),
            FieldValue.MultiChoice(listOf("a", "b")),
            FieldValue.Reference("Account", "001")
        ).forEach { assertTrue(FieldValues.same(it, FieldValues.decode(FieldValues.encode(it))), it.toString()) }
    }

    @Test
    fun `canonical form ignores representation noise`() {
        assertTrue(FieldValues.same(FieldValue.Number(BigDecimal("1.50")), FieldValue.Number(BigDecimal("1.5"))))
        assertTrue(FieldValues.same(FieldValue.Choice("x"), FieldValue.Text("x")))
        assertTrue(FieldValues.same(FieldValue.Text(""), FieldValue.Null))
        assertNotEquals(FieldValues.canonical(FieldValue.Text("1")), FieldValues.canonical(FieldValue.Number(BigDecimal.ONE)))
    }

    @Test
    fun `hash depends on values, not on map order`() {
        val one = FieldValues.hash(linkedMapOf("a" to FieldValue.Text("x"), "b" to FieldValue.Number(BigDecimal("2.0"))))
        val two = FieldValues.hash(linkedMapOf("b" to FieldValue.Number(BigDecimal("2")), "a" to FieldValue.Text("x")))
        assertEquals(one, two)
        assertNotEquals(one, FieldValues.hash(mapOf("a" to FieldValue.Text("y"), "b" to FieldValue.Number(BigDecimal(2)))))
    }

    @Test
    fun `plain request values become typed values`() {
        val json =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
        assertEquals(FieldValue.Bool(true), FieldValues.fromPlain(json.readTree("true")))
        assertEquals(FieldValue.Text("x"), FieldValues.fromPlain(json.readTree("\"x\"")))
        assertTrue(FieldValues.same(FieldValue.Number(BigDecimal(3)), FieldValues.fromPlain(json.readTree("3"))))
        assertEquals(FieldValue.Null, FieldValues.fromPlain(null))
    }
}
