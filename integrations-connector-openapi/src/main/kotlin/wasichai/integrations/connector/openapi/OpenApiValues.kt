package wasichai.integrations.connector.openapi

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.ResourceSchema
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// json values to typed fields by the derived schema, and back. a value that does not fit its declared type is
// kept as json, never dropped
internal object OpenApiValues {
    private val nodes = JsonNodeFactory.instance

    fun read(
        schema: ResourceSchema,
        record: JsonNode
    ): Map<String, FieldValue> = schema.fields.associate { it.name to read(it, record.path(it.name)) }

    fun instant(node: JsonNode): Instant? =
        node.asString(null)?.let { text ->
            runCatching { Instant.parse(text) }.getOrNull() ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
        }

    private fun read(
        field: FieldDescriptor,
        node: JsonNode
    ): FieldValue {
        if (node.isMissingNode || node.isNull) return FieldValue.Null
        val typed: FieldValue? =
            when (field.type) {
                FieldType.TEXT -> if (node.isValueNode) FieldValue.Text(node.asString()) else null
                FieldType.CHOICE -> if (node.isString) FieldValue.Choice(node.asString()) else null
                FieldType.NUMBER -> if (node.isNumber) FieldValue.Number(node.decimalValue()) else null
                FieldType.BOOLEAN -> if (node.isBoolean) FieldValue.Bool(node.booleanValue()) else null
                FieldType.DATE -> node.asString(null)?.let { runCatching { FieldValue.Date(LocalDate.parse(it)) }.getOrNull() }
                FieldType.DATETIME -> instant(node)?.let { FieldValue.DateTime(it) }
                FieldType.MULTI_CHOICE ->
                    if (node.isArray && node.values().all { it.isString }) FieldValue.MultiChoice(node.values().map { it.asString() }) else null
                FieldType.REFERENCE, FieldType.JSON -> null
            }
        return typed ?: FieldValue.Json(node)
    }

    // the body of a create or update: only fields of the record schema, never read-only ones
    fun write(
        schema: ResourceSchema,
        fields: Map<String, FieldValue>
    ): ObjectNode {
        val out = nodes.objectNode()
        fields.forEach { (name, value) ->
            val field = schema.field(name) ?: throw ConnectorException.Permanent("${schema.resource} has no field '$name'", mapOf("field" to name))
            if (field.readOnly) throw ConnectorException.Permanent("${schema.resource} field '$name' is read-only", mapOf("field" to name))
            out.set(name, node(schema.resource, field, value))
        }
        return out
    }

    private fun node(
        resource: String,
        field: FieldDescriptor,
        value: FieldValue
    ): JsonNode {
        fun refuse(): Nothing =
            throw ConnectorException.Permanent(
                "$resource field '${field.name}' (${field.type}) cannot take ${value.javaClass.simpleName}",
                mapOf("field" to field.name)
            )
        if (value is FieldValue.Null) return nodes.nullNode()
        if (value is FieldValue.Json) return value.value
        return when (field.type) {
            FieldType.TEXT, FieldType.CHOICE, FieldType.REFERENCE ->
                when (value) {
                    is FieldValue.Text -> nodes.stringNode(value.value)
                    is FieldValue.Choice -> nodes.stringNode(value.value)
                    is FieldValue.Reference -> nodes.stringNode(value.id)
                    is FieldValue.Number -> nodes.stringNode(value.value.toPlainString())
                    else -> refuse()
                }.also { if (field.options.isNotEmpty() && it.asString() !in field.options) refuse() }
            FieldType.NUMBER ->
                when (value) {
                    is FieldValue.Number -> nodes.numberNode(value.value)
                    is FieldValue.Text -> nodes.numberNode(value.value.toBigDecimalOrNull() ?: refuse())
                    else -> refuse()
                }
            FieldType.BOOLEAN -> if (value is FieldValue.Bool) nodes.booleanNode(value.value) else refuse()
            FieldType.DATE -> if (value is FieldValue.Date) nodes.stringNode(value.value.toString()) else refuse()
            FieldType.DATETIME -> if (value is FieldValue.DateTime) nodes.stringNode(value.value.toString()) else refuse()
            FieldType.MULTI_CHOICE ->
                if (value is FieldValue.MultiChoice && value.values.all { it in field.options }) {
                    nodes.arrayNode().apply { value.values.forEach { add(it) } }
                } else {
                    refuse()
                }
            FieldType.JSON -> refuse()
        }
    }
}
