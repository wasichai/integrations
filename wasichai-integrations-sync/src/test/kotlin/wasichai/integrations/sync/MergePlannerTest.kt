package wasichai.integrations.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.FieldValue
import java.time.Instant

class MergePlannerTest {
    private fun t(s: String) = FieldValue.Text(s)

    private val name = FieldMapping("name", "Name")
    private val base = mapOf(name.key to Agreed(t("old"), t("old")))

    private fun plan(
        a: String,
        b: String,
        mapping: FieldMapping = name,
        direction: SyncDirection = SyncDirection.BIDIRECTIONAL,
        source: Side = Side.A,
        snapshot: Map<String, Agreed>? = base,
        aAt: Instant? = null,
        bAt: Instant? = null
    ) = MergePlanner.plan(listOf(mapping), direction, source, mapOf(mapping.aField!! to t(a)), mapOf(mapping.bField!! to t(b)), snapshot, aAt, bAt)

    @Test
    fun `a change on one side flows to the other`() {
        assertEquals(mapOf("Name" to t("new")), plan("new", "old").writes[Side.B])
        assertEquals(mapOf("name" to t("new")), plan("old", "new", source = Side.B).writes[Side.A])
    }

    @Test
    fun `nothing changed, nothing written`() {
        val p = plan("old", "old")
        assertFalse(p.hasWrites)
        assertTrue(p.conflicts.isEmpty())
    }

    @Test
    fun `the same new value on both sides is agreement, not a conflict`() {
        val p = plan("new", "new")
        assertFalse(p.hasWrites)
        assertTrue(p.conflicts.isEmpty())
    }

    @Test
    fun `different changes on both sides are a manual conflict by default, nothing written`() {
        val p = plan("mine", "theirs")
        assertFalse(p.hasWrites)
        assertEquals("name->Name", p.conflicts.single().key)
        assertEquals(
            t("old"),
            p.conflicts
                .single()
                .base!!
                .a
        )
    }

    @Test
    fun `strategies decide a clash`() {
        assertEquals(mapOf("Name" to t("mine")), plan("mine", "theirs", name.copy(conflictStrategy = ConflictStrategy.A_WINS)).writes[Side.B])
        assertEquals(mapOf("name" to t("theirs")), plan("mine", "theirs", name.copy(conflictStrategy = ConflictStrategy.B_WINS)).writes[Side.A])
        assertEquals(
            mapOf("name" to t("theirs")),
            plan("mine", "theirs", name.copy(conflictStrategy = ConflictStrategy.SOURCE_WINS), source = Side.B).writes[Side.A]
        )
        val lww = name.copy(conflictStrategy = ConflictStrategy.LAST_WRITE_WINS)
        val early = Instant.parse("2026-01-01T00:00:00Z")
        val late = Instant.parse("2026-01-02T00:00:00Z")
        assertEquals(mapOf("Name" to t("mine")), plan("mine", "theirs", lww, aAt = late, bAt = early).writes[Side.B])
        assertEquals(1, plan("mine", "theirs", lww, aAt = late, bAt = null).conflicts.size, "no timestamp, no guess")
    }

    @Test
    fun `an owner always wins its field and never receives the other side's change`() {
        val ownedByA = name.copy(owner = Owner.A)
        assertEquals(mapOf("Name" to t("mine")), plan("mine", "theirs", ownedByA).writes[Side.B])
        val p = plan("old", "theirs", ownedByA, source = Side.B)
        assertFalse(p.hasWrites)
        assertEquals(setOf(name.key), p.kept, "B's edit stays pending, it is not written into A")
    }

    @Test
    fun `direction is respected`() {
        val p = plan("old", "theirs", name.copy(owner = Owner.A), direction = SyncDirection.A_TO_B, source = Side.B)
        assertFalse(p.hasWrites)
    }

    @Test
    fun `transforms apply on the way`() {
        val enum =
            FieldMapping(
                "tier",
                "Tier",
                tools.jackson.databind.json.JsonMapper
                    .builder()
                    .build()
                    .readTree("""{"type":"enumMap","map":{"gold":"GOLD"}}""")
            )
        val p =
            MergePlanner.plan(
                listOf(enum),
                SyncDirection.BIDIRECTIONAL,
                Side.A,
                mapOf("tier" to FieldValue.Choice("gold")),
                mapOf("Tier" to FieldValue.Null),
                mapOf(enum.key to Agreed(FieldValue.Null, FieldValue.Null)),
                null,
                null
            )
        assertEquals(FieldValue.Choice("GOLD"), p.writes.getValue(Side.B)["Tier"])
    }

    @Test
    fun `adopted pairs take the processed side's values`() {
        val p = plan("mine", "theirs", snapshot = null)
        assertEquals(mapOf("Name" to t("mine")), p.writes[Side.B])
        assertNull(p.conflicts.firstOrNull())
    }
}
