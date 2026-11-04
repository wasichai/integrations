package wasichai.integrations.sync

import tools.jackson.databind.JsonNode
import wasichai.core.common.FieldViolation
import wasichai.integrations.sdk.FieldValue

// what A ends up with for one mapping of a resolution. a whole side (A or B), or per field (CUSTOM): an explicit
// value keyed by A's field name, else the side picked for the mapping key. B's value always comes back through the
// inverse transform, and a pick of B on a transform without one is refused, never guessed (issue #33).
object Resolution {
    fun chooseA(
        mapping: FieldMapping,
        transform: Transform,
        a: FieldValue,
        b: FieldValue,
        choice: Side?,
        explicit: JsonNode?,
        pick: String?,
        violations: MutableList<FieldViolation>
    ): FieldValue? {
        val side =
            choice ?: when {
                explicit != null -> return FieldValues.fromPlain(explicit)
                pick == null -> {
                    violations += FieldViolation("values.${mapping.aField}", "required, or pick A or B in fields.${mapping.key}")
                    return null
                }
                pick.uppercase() == "A" -> Side.A
                pick.uppercase() == "B" -> Side.B
                else -> {
                    violations += FieldViolation("fields.${mapping.key}", "A or B")
                    return null
                }
            }
        if (side == Side.A) return a
        if (!transform.invertible) {
            violations += FieldViolation("fields.${mapping.key}", "this mapping's transform has no inverse; give values.${mapping.aField}")
            return null
        }
        return transform.toA(b)
    }
}
