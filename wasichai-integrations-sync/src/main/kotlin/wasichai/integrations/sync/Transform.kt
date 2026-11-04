package wasichai.integrations.sync

import tools.jackson.databind.JsonNode
import wasichai.core.common.FieldViolation
import wasichai.integrations.sdk.FieldValue
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

// the closed set of transforms (ADR-0009): no expressions, no code. a transform is written A -> B;
// toA is its inverse where one exists. static and conditional have none: they only flow A -> B.
sealed interface Transform {
    val invertible: Boolean get() = true

    fun toB(value: FieldValue): FieldValue

    fun toA(value: FieldValue): FieldValue

    fun apply(
        value: FieldValue,
        to: Side
    ): FieldValue = if (to == Side.B) toB(value) else toA(value)

    data object Copy : Transform {
        override fun toB(value: FieldValue) = value

        override fun toA(value: FieldValue) = value
    }

    data class Static(
        val value: FieldValue
    ) : Transform {
        override val invertible get() = false

        override fun toB(value: FieldValue) = this.value

        override fun toA(value: FieldValue) = value
    }

    // a null source becomes the default on B; back to A the value goes as it is
    data class Default(
        val value: FieldValue
    ) : Transform {
        override fun toB(value: FieldValue) = if (value is FieldValue.Null) this.value else value

        override fun toA(value: FieldValue) = value
    }

    // unmapped values become null: never a guess. the inverse takes the first A value naming each B value
    data class EnumMap(
        val map: Map<String, String>
    ) : Transform {
        private val inverse: Map<String, String> = map.entries.reversed().associate { (k, v) -> v to k }

        override fun toB(value: FieldValue) = lookup(value, map)

        override fun toA(value: FieldValue) = lookup(value, inverse)

        private fun lookup(
            value: FieldValue,
            table: Map<String, String>
        ): FieldValue {
            val key =
                when (value) {
                    is FieldValue.Choice -> value.value
                    is FieldValue.Text -> value.value
                    else -> return FieldValue.Null
                }
            return table[key]?.let { FieldValue.Choice(it) } ?: FieldValue.Null
        }
    }

    // a date on one side, a date-time on the other: a date is midnight in the zone, a date-time its day in the zone
    data class DateTime(
        val zone: ZoneId,
        val aIsDate: Boolean,
        val bIsDate: Boolean
    ) : Transform {
        override fun toB(value: FieldValue) = convert(value, bIsDate)

        override fun toA(value: FieldValue) = convert(value, aIsDate)

        private fun convert(
            value: FieldValue,
            wantDate: Boolean
        ): FieldValue =
            when (value) {
                is FieldValue.DateTime -> if (wantDate) FieldValue.Date(value.value.atZone(zone).toLocalDate()) else value
                is FieldValue.Date -> if (wantDate) value else FieldValue.DateTime(value.value.atStartOfDay(zone).toInstant())
                is FieldValue.Text -> runCatching { convert(FieldValue.Date(LocalDate.parse(value.value)), wantDate) }.getOrDefault(FieldValue.Null)
                else -> FieldValue.Null
            }
    }

    // rounds half up to the scale on the way to B; A gets the number back as it is
    data class Number(
        val scale: Int
    ) : Transform {
        override fun toB(value: FieldValue): FieldValue =
            when (value) {
                is FieldValue.Number -> FieldValue.Number(value.value.setScale(scale, RoundingMode.HALF_UP))
                is FieldValue.Text ->
                    value.value
                        .trim()
                        .toBigDecimalOrNull()
                        ?.let { FieldValue.Number(it.setScale(scale, RoundingMode.HALF_UP)) }
                        ?: FieldValue.Null
                else -> value
            }

        override fun toA(value: FieldValue) = value
    }

    data class Conditional(
        val equals: FieldValue,
        val then: FieldValue,
        val otherwise: FieldValue
    ) : Transform {
        override val invertible get() = false

        override fun toB(value: FieldValue) = if (FieldValues.same(value, equals)) then else otherwise

        override fun toA(value: FieldValue) = value
    }

    companion object {
        // the spec as sent: {"type": "copy"} ... a problem is a violation on the given field path
        fun parse(
            spec: JsonNode?,
            path: String,
            violations: MutableList<FieldViolation>
        ): Transform? {
            if (spec == null || spec.isNull || spec.isMissingNode) return Copy
            return when (val type = spec.path("type").asString()) {
                "", "copy" -> Copy
                "static" -> Static(FieldValues.fromPlain(spec.get("value")))
                "default" -> Default(FieldValues.fromPlain(spec.get("value")))
                "enumMap" -> {
                    val map = spec.path("map")
                    if (!map.isObject || map.isEmpty) {
                        violations += FieldViolation("$path.map", "an object of source value to target value")
                        null
                    } else {
                        EnumMap(map.properties().associate { (k, v) -> k to v.asString() })
                    }
                }
                "dateTime" -> {
                    val zone = runCatching { ZoneId.of(spec.path("zone").asString().ifEmpty { "UTC" }) }.getOrNull()
                    if (zone == null) violations += FieldViolation("$path.zone", "unknown time zone")
                    zone?.let { DateTime(it, spec.path("a").asString() == "DATE", spec.path("b").asString() == "DATE") }
                }
                "number" -> {
                    val scale = spec.path("scale")
                    if (!scale.isInt || scale.asInt() !in 0..12) {
                        violations += FieldViolation("$path.scale", "an integer from 0 to 12")
                        null
                    } else {
                        Number(scale.asInt())
                    }
                }
                "conditional" ->
                    Conditional(FieldValues.fromPlain(spec.get("equals")), FieldValues.fromPlain(spec.get("then")), FieldValues.fromPlain(spec.get("else")))
                else -> {
                    violations += FieldViolation("$path.type", "unknown transform '$type'")
                    null
                }
            }
        }

        // for the engine: definitions are validated before they run
        fun of(mapping: FieldMapping): Transform = parse(mapping.transform, "transform", mutableListOf()) ?: Copy
    }
}
