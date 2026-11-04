package wasichai.integrations.sync

import wasichai.integrations.sdk.FieldValue
import java.time.Instant

// what both sides last agreed on for one mapping
data class Agreed(
    val a: FieldValue,
    val b: FieldValue
)

data class ConflictField(
    val key: String,
    val aField: String,
    val bField: String,
    val a: FieldValue,
    val b: FieldValue,
    val base: Agreed?
)

// the outcome for one pair: field values to write per side, conflicts that hold the pair, and which mappings
// keep their old snapshot entry (a change that may not flow stays pending, so it is not forgotten)
data class MergePlan(
    val writes: Map<Side, Map<String, FieldValue>>,
    val conflicts: List<ConflictField>,
    val kept: Set<String>
) {
    val hasWrites: Boolean get() = writes.values.any { it.isNotEmpty() }
}

// the pure heart of the engine (sync-semantics.md, "per change"): diff both sides against the snapshot and
// decide, per mapping, what flows where. no io here, so every rule is unit tested.
object MergePlanner {
    fun plan(
        mappings: List<FieldMapping>,
        direction: SyncDirection,
        source: Side,
        a: Map<String, FieldValue>,
        b: Map<String, FieldValue>,
        snapshot: Map<String, Agreed>?,
        aModifiedAt: Instant?,
        bModifiedAt: Instant?
    ): MergePlan {
        val writes = mapOf(Side.A to mutableMapOf<String, FieldValue>(), Side.B to mutableMapOf())
        val conflicts = mutableListOf<ConflictField>()
        val kept = mutableSetOf<String>()
        for (m in mappings) {
            val transform = Transform.of(m)
            val aVal = a[m.aField!!] ?: FieldValue.Null
            val bVal = b[m.bField!!] ?: FieldValue.Null
            val base = snapshot?.get(m.key)
            // no snapshot: two records adopted as one; the side being processed is the one that "changed"
            val changedA = if (base == null) source == Side.A else !FieldValues.same(aVal, base.a)
            val changedB = if (base == null) source == Side.B else !FieldValues.same(bVal, base.b)

            fun carry(from: Side): Boolean {
                val to = from.other
                if (!m.flows(direction, from, to)) return false
                val value = transform.apply(if (from == Side.A) aVal else bVal, to)
                val current = if (to == Side.A) aVal else bVal
                if (!FieldValues.same(value, current)) writes.getValue(to)[m.field(to)] = value
                return true
            }
            when {
                !changedA && !changedB -> Unit
                changedA && !changedB -> if (!carry(Side.A)) kept += m.key
                !changedA && changedB -> if (!carry(Side.B)) kept += m.key
                FieldValues.same(transform.toB(aVal), bVal) -> Unit
                else -> {
                    val winner = winner(m, source, aModifiedAt, bModifiedAt)
                    if (winner == null || !carry(winner)) conflicts += ConflictField(m.key, m.aField, m.bField, aVal, bVal, base)
                }
            }
        }
        return MergePlan(writes, conflicts, kept)
    }

    // owner A or B always wins its field; with BOTH the strategy decides; null is "a person must decide"
    fun winner(
        m: FieldMapping,
        source: Side,
        aModifiedAt: Instant?,
        bModifiedAt: Instant?
    ): Side? =
        when (m.owner) {
            Owner.A -> Side.A
            Owner.B -> Side.B
            Owner.BOTH ->
                when (m.conflictStrategy) {
                    ConflictStrategy.A_WINS -> Side.A
                    ConflictStrategy.B_WINS -> Side.B
                    ConflictStrategy.SOURCE_WINS -> source
                    ConflictStrategy.MANUAL -> null
                    // only with timestamps on both sides; never a guess
                    ConflictStrategy.LAST_WRITE_WINS ->
                        if (aModifiedAt == null || bModifiedAt == null) {
                            null
                        } else if (aModifiedAt == bModifiedAt) {
                            source
                        } else if (aModifiedAt.isAfter(bModifiedAt)) {
                            Side.A
                        } else {
                            Side.B
                        }
                }
        }

    // the mapped values of one side, by field name: what a side hash is computed over
    fun mapped(
        mappings: List<FieldMapping>,
        side: Side,
        values: Map<String, FieldValue>
    ): Map<String, FieldValue> = mappings.associate { it.field(side) to (values[it.field(side)] ?: FieldValue.Null) }
}
