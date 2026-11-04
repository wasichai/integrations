package wasichai.integrations.sync

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.FieldValue
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.HexFormat

// typed values as stored json, and the canonical form two values are compared and hashed in.
// canonical: numbers without trailing zeros, instants in utc, so 1.50 and 1.5 are the same value.
object FieldValues {
    private val nodes = JsonNodeFactory.instance

    fun encode(value: FieldValue): ObjectNode {
        val node = nodes.objectNode()
        when (value) {
            FieldValue.Null -> node.put("t", "null")
            is FieldValue.Text -> node.put("t", "text").put("v", value.value)
            is FieldValue.Number -> node.put("t", "number").put("v", value.value.stripTrailingZeros().toPlainString())
            is FieldValue.Bool -> node.put("t", "bool").put("v", value.value)
            is FieldValue.Date -> node.put("t", "date").put("v", value.value.toString())
            is FieldValue.DateTime -> node.put("t", "datetime").put("v", value.value.toString())
            is FieldValue.Choice -> node.put("t", "choice").put("v", value.value)
            is FieldValue.MultiChoice -> node.put("t", "multi").set("v", nodes.arrayNode().apply { value.values.forEach { add(it) } })
            is FieldValue.Reference -> node.put("t", "ref").put("r", value.resource).put("v", value.id)
            is FieldValue.Json -> node.put("t", "json").set("v", value.value)
        }
        return node
    }

    fun decode(node: JsonNode?): FieldValue {
        if (node == null || node.isNull || node.isMissingNode) return FieldValue.Null
        val v = node.path("v")
        return when (node.path("t").asString()) {
            "text" -> FieldValue.Text(v.asString())
            "number" -> FieldValue.Number(BigDecimal(v.asString()))
            "bool" -> FieldValue.Bool(v.asBoolean())
            "date" -> FieldValue.Date(LocalDate.parse(v.asString()))
            "datetime" -> FieldValue.DateTime(Instant.parse(v.asString()))
            "choice" -> FieldValue.Choice(v.asString())
            "multi" -> FieldValue.MultiChoice((0 until v.size()).map { v.get(it).asString() })
            "ref" -> FieldValue.Reference(node.path("r").asString(), v.asString())
            "json" -> FieldValue.Json(v)
            else -> FieldValue.Null
        }
    }

    // a plain json scalar from a request (a static value, a custom resolution) as a typed value
    fun fromPlain(node: JsonNode?): FieldValue =
        when {
            node == null || node.isNull || node.isMissingNode -> FieldValue.Null
            node.isObject && node.has("t") -> decode(node)
            node.isBoolean -> FieldValue.Bool(node.asBoolean())
            node.isNumber -> FieldValue.Number(node.decimalValue())
            node.isString -> FieldValue.Text(node.asString())
            node.isArray -> FieldValue.MultiChoice((0 until node.size()).map { node.get(it).asString() })
            else -> FieldValue.Json(node)
        }

    // text and choice carry the same string: a select copied to a text field is not a change
    fun canonical(value: FieldValue): String =
        when (value) {
            is FieldValue.Choice -> encode(FieldValue.Text(value.value)).toString()
            is FieldValue.Text -> if (value.value.isEmpty()) encode(FieldValue.Null).toString() else encode(value).toString()
            else -> encode(value).toString()
        }

    fun same(
        a: FieldValue,
        b: FieldValue
    ): Boolean = canonical(a) == canonical(b)

    // the hash of a record's mapped fields, by field name: equal hash, nothing changed that the sync cares about
    fun hash(values: Map<String, FieldValue>): String {
        val text = values.toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=${canonical(v)}" }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    }
}
