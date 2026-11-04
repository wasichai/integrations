package wasichai.integrations.connector.notion

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// notion property values <-> typed field values. a property's type comes from the data source schema.
internal object NotionProperties {
    private val nodes = JsonNodeFactory.instance
    internal val json: JsonMapper = JsonMapper.builder().build()

    // notion caps one rich text item at 2000 characters
    private const val TEXT_CHUNK = 2000

    private val WRITABLE =
        setOf("title", "rich_text", "number", "select", "status", "multi_select", "date", "checkbox", "url", "email", "phone_number")

    fun descriptor(
        name: String,
        property: JsonNode
    ): FieldDescriptor {
        val type = property.path("type").asString()
        val fieldType =
            when (type) {
                "title", "rich_text", "url", "email", "phone_number", "unique_id" -> FieldType.TEXT
                "number" -> FieldType.NUMBER
                "checkbox" -> FieldType.BOOLEAN
                "select", "status" -> FieldType.CHOICE
                "multi_select" -> FieldType.MULTI_CHOICE
                "date" -> FieldType.DATE
                "created_time", "last_edited_time" -> FieldType.DATETIME
                else -> FieldType.JSON
            }
        val options =
            property
                .path(type)
                .path("options")
                .takeIf { it.isArray }
                ?.items()
                ?.map { it.path("name").asString() }
                .orEmpty()
        return FieldDescriptor(
            name = name,
            label = name,
            type = fieldType,
            required = type == "title",
            readOnly = type !in WRITABLE,
            options = options,
            nativeType = type
        )
    }

    fun read(property: JsonNode): FieldValue {
        val type = property.path("type").asString()
        val value = property.path(type)
        if (value.isNull || value.isMissingNode) return FieldValue.Null
        return when (type) {
            "title", "rich_text" -> FieldValue.Text(value.items().joinToString("") { it.path("plain_text").asString() })
            "number" -> FieldValue.Number(value.decimalValue())
            "checkbox" -> FieldValue.Bool(value.asBoolean())
            "select", "status" -> FieldValue.Choice(value.path("name").asString())
            "multi_select" -> FieldValue.MultiChoice(value.items().map { it.path("name").asString() })
            "url", "email", "phone_number" -> FieldValue.Text(value.asString())
            "date" -> readDate(value.path("start").asString())
            "created_time", "last_edited_time" -> FieldValue.DateTime(Instant.parse(value.asString()))
            "unique_id" ->
                FieldValue.Text(
                    listOfNotNull(value.path("prefix").takeUnless { it.isNull || it.isMissingNode }?.asString(), value.path("number").asString())
                        .joinToString("-")
                )
            else -> FieldValue.Json(value)
        }
    }

    private fun readDate(start: String): FieldValue =
        if ('T' in start) FieldValue.DateTime(OffsetDateTime.parse(start).toInstant()) else FieldValue.Date(LocalDate.parse(start))

    // the json notion expects for a property of nativeType holding value
    fun write(
        nativeType: String,
        value: FieldValue
    ): JsonNode {
        require(nativeType in WRITABLE) { "notion property type '$nativeType' is read-only" }
        if (value is FieldValue.Null) {
            return when (nativeType) {
                "title", "rich_text", "multi_select" -> nodes.objectNode().set(nativeType, nodes.arrayNode())
                else -> nodes.objectNode().putNull(nativeType)
            }
        }
        val node: ObjectNode = nodes.objectNode()
        when (nativeType) {
            "title", "rich_text" -> node.set(nativeType, richText(text(value)))
            "number" -> node.put(nativeType, (value as? FieldValue.Number)?.value ?: BigDecimal(text(value)))
            "checkbox" -> node.put(nativeType, (value as? FieldValue.Bool)?.value ?: text(value).toBooleanStrict())
            "select", "status" -> node.set(nativeType, nodes.objectNode().put("name", text(value)))
            "multi_select" -> {
                val names = (value as? FieldValue.MultiChoice)?.values ?: listOf(text(value))
                node.set(nativeType, nodes.arrayNode().apply { names.forEach { add(nodes.objectNode().put("name", it)) } })
            }
            "date" -> {
                val start =
                    when (value) {
                        is FieldValue.Date -> value.value.toString()
                        is FieldValue.DateTime -> value.value.toString()
                        else -> text(value)
                    }
                node.set(nativeType, nodes.objectNode().put("start", start))
            }
            else -> node.put(nativeType, text(value))
        }
        return node
    }

    private fun richText(text: String): ArrayNode {
        val array = nodes.arrayNode()
        text.chunked(TEXT_CHUNK).forEach { chunk ->
            array.add(nodes.objectNode().put("type", "text").set("text", nodes.objectNode().put("content", chunk)))
        }
        return array
    }

    private fun text(value: FieldValue): String =
        when (value) {
            is FieldValue.Text -> value.value
            is FieldValue.Choice -> value.value
            is FieldValue.Number -> value.value.toPlainString()
            is FieldValue.Bool -> value.value.toString()
            is FieldValue.Date -> value.value.toString()
            is FieldValue.DateTime -> value.value.toString()
            is FieldValue.MultiChoice -> value.values.joinToString(", ")
            is FieldValue.Reference -> value.id
            is FieldValue.Json -> value.value.toString()
            FieldValue.Null -> ""
        }
}
