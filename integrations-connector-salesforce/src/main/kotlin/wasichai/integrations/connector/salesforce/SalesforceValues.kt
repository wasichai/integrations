package wasichai.integrations.connector.salesforce

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.ResourceSchema
import java.time.LocalDate

// one sObject as describe told us, plus what the sdk schema has no room for (reference targets)
internal class SObjectDescribe(
    val schema: ResourceSchema,
    val referenceTo: Map<String, String>,
    // compound address/location fields cannot be selected next to their parts in every api version
    val compound: Set<String>
) {
    val selectable: List<String> get() = schema.fields.map { it.name }.filterNot { it in compound }
}

internal object SalesforceValues {
    private val json = JsonMapper.builder().build()
    private val nodes = JsonNodeFactory.instance

    fun fieldType(native: String): FieldType =
        when (native) {
            "string", "textarea", "id", "encryptedstring", "combobox", "email", "phone", "url" -> FieldType.TEXT
            "reference" -> FieldType.REFERENCE
            "picklist" -> FieldType.CHOICE
            "multipicklist" -> FieldType.MULTI_CHOICE
            "boolean" -> FieldType.BOOLEAN
            "int", "long", "double", "currency", "percent" -> FieldType.NUMBER
            "date" -> FieldType.DATE
            "datetime" -> FieldType.DATETIME
            else -> FieldType.JSON
        }

    fun describe(body: JsonNode): SObjectDescribe {
        val name = body.get("name").asString()
        val references = mutableMapOf<String, String>()
        val compound = mutableSetOf<String>()
        val fields =
            body.get("fields").values().map { f ->
                val fieldName = f.get("name").asString()
                val native = f.get("type").asString()
                if (native == "address" || native == "location") compound += fieldName
                f
                    .get("referenceTo")
                    ?.values()
                    ?.firstOrNull()
                    ?.let { references[fieldName] = it.asString() }
                val createable = f.path("createable").asBoolean(false)
                val updateable = f.path("updateable").asBoolean(false)
                FieldDescriptor(
                    name = fieldName,
                    label = f.path("label").asString(fieldName),
                    type = fieldType(native),
                    required = !f.path("nillable").asBoolean(true) && createable && !f.path("defaultedOnCreate").asBoolean(false),
                    readOnly = !createable && !updateable,
                    externalId = f.path("externalId").asBoolean(false),
                    options =
                        f
                            .path("picklistValues")
                            .values()
                            .filter { it.path("active").asBoolean(true) }
                            .map { it.get("value").asString() },
                    nativeType = native
                )
            }
        return SObjectDescribe(ResourceSchema(name, body.path("label").asString(name), fields), references, compound)
    }

    // record json → typed values. without a describe the json type decides
    fun fromJson(
        record: JsonNode,
        describe: SObjectDescribe?
    ): Map<String, FieldValue> =
        record
            .properties()
            .filter { it.key != "attributes" }
            .associate { (name, value) -> name to toValue(value, describe?.schema?.field(name)?.type, describe?.referenceTo?.get(name)) }

    private fun toValue(
        value: JsonNode,
        type: FieldType?,
        referenceTo: String?
    ): FieldValue {
        if (value.isNull || value.isMissingNode) return FieldValue.Null
        return when (type) {
            FieldType.TEXT -> FieldValue.Text(value.asString())
            FieldType.CHOICE -> FieldValue.Choice(value.asString())
            FieldType.MULTI_CHOICE -> FieldValue.MultiChoice(value.asString().split(";").filter { it.isNotEmpty() })
            FieldType.NUMBER -> FieldValue.Number(value.decimalValue())
            FieldType.BOOLEAN -> FieldValue.Bool(value.asBoolean())
            FieldType.DATE -> FieldValue.Date(LocalDate.parse(value.asString()))
            FieldType.DATETIME -> FieldValue.DateTime(SalesforceTimes.parse(value.asString()))
            FieldType.REFERENCE -> FieldValue.Reference(referenceTo ?: "", value.asString())
            FieldType.JSON -> FieldValue.Json(value)
            null ->
                when {
                    value.isString -> FieldValue.Text(value.asString())
                    value.isNumber -> FieldValue.Number(value.decimalValue())
                    value.isBoolean -> FieldValue.Bool(value.asBoolean())
                    else -> FieldValue.Json(value)
                }
        }
    }

    fun toJson(fields: Map<String, FieldValue>): ObjectNode {
        val body = json.createObjectNode()
        fields.forEach { (name, value) ->
            Soql.identifier(name)
            body.set(name, toNode(value))
        }
        return body
    }

    private fun toNode(value: FieldValue): JsonNode =
        when (value) {
            FieldValue.Null -> nodes.nullNode()
            is FieldValue.Text -> nodes.stringNode(value.value)
            is FieldValue.Choice -> nodes.stringNode(value.value)
            is FieldValue.MultiChoice -> nodes.stringNode(value.values.joinToString(";"))
            is FieldValue.Number -> nodes.numberNode(value.value)
            is FieldValue.Bool -> nodes.booleanNode(value.value)
            is FieldValue.Date -> nodes.stringNode(value.value.toString())
            is FieldValue.DateTime -> nodes.stringNode(value.value.toString())
            is FieldValue.Reference -> nodes.stringNode(value.id)
            is FieldValue.Json -> value.value
        }

    fun bytes(node: JsonNode): ByteArray = json.writeValueAsBytes(node)
}
