package wasichai.integrations.ai

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import wasichai.core.common.FieldViolation
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.ResourceSchema
import java.time.LocalDate
import java.time.OffsetDateTime

// the json schema subset a tool input is written in (ADR-0026). check() refuses anything outside it when a tool is
// exposed; validate() checks an input against a checked schema. objects are closed: additionalProperties is false
// whether written or not, and check() writes it so a client reads what is enforced
object ToolSchema {
    const val MAX_DEPTH = 8
    const val MAX_SCHEMA_BYTES = 32 * 1024
    const val MAX_ENUM = 100

    private val TYPES = setOf("object", "string", "number", "integer", "boolean", "array")
    private val COMMON = setOf("type", "enum", "title", "description")
    private val BY_TYPE =
        mapOf(
            "object" to setOf("properties", "required", "additionalProperties"),
            "string" to setOf("minLength", "maxLength", "format"),
            "number" to setOf("minimum", "maximum"),
            "integer" to setOf("minimum", "maximum"),
            "boolean" to emptySet(),
            "array" to setOf("items", "minItems", "maxItems")
        )
    private val FORMATS = setOf("date", "date-time")
    private val PROPERTY = Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$")

    // a tool takes no credentials: the connection holds them. such names are refused in schemas and masked in
    // what is stored of an output
    val SECRET_NAME = Regex("(?i)(password|passwd|secret|token|api[_-]?key|credential|authorization|private[_-]?key|cookie)")

    // violations located under `at`; the normalized schema when there are none
    fun check(
        schema: JsonNode?,
        json: JsonMapper,
        at: String = "inputSchema"
    ): Pair<ObjectNode?, List<FieldViolation>> {
        val v = mutableListOf<FieldViolation>()
        if (schema == null || schema.isNull || !schema.isObject) return null to listOf(FieldViolation(at, "a JSON Schema object"))
        if (json.writeValueAsBytes(schema).size > MAX_SCHEMA_BYTES) return null to listOf(FieldViolation(at, "at most ${MAX_SCHEMA_BYTES / 1024} KiB"))
        val copy = schema.deepCopy() as ObjectNode
        if (copy.get("type")?.takeIf { it.isString }?.stringValue() != "object") v += FieldViolation("$at.type", "a tool input is an object")
        copy.remove("\$schema")
        node(copy, at, 1, v)
        return (if (v.isEmpty()) copy else null) to v
    }

    private fun node(
        s: ObjectNode,
        at: String,
        depth: Int,
        v: MutableList<FieldViolation>
    ) {
        if (depth > MAX_DEPTH) {
            v += FieldViolation(at, "nested deeper than $MAX_DEPTH")
            return
        }
        val type = s.get("type")?.takeIf { it.isString }?.stringValue()
        if (type == null || type !in TYPES) {
            v += FieldViolation("$at.type", "one of ${TYPES.joinToString()}")
            return
        }
        val allowed = COMMON + BY_TYPE.getValue(type)
        s.propertyNames().filterNot { it in allowed }.forEach { v += FieldViolation("$at.$it", "not supported for type $type") }
        s.get("title")?.let { if (!it.isString) v += FieldViolation("$at.title", "a string") }
        s.get("description")?.let { if (!it.isString) v += FieldViolation("$at.description", "a string") }
        s.get("enum")?.let { e ->
            if (!e.isArray || e.size() !in 1..MAX_ENUM || type == "object" || type == "array") {
                v += FieldViolation("$at.enum", "1 to $MAX_ENUM values of type $type")
            } else {
                (0 until e.size()).filterNot { fits(type, e.get(it)) }.forEach { v += FieldViolation("$at.enum[$it]", "not a $type") }
            }
        }
        when (type) {
            "object" -> objectNode(s, at, depth, v)
            "string" -> {
                bound(s, "minLength", at, v)
                bound(s, "maxLength", at, v)
                order(s, "minLength", "maxLength", at, v)
                s.get("format")?.let { f ->
                    if (!f.isString || f.stringValue() !in FORMATS) v += FieldViolation("$at.format", "one of ${FORMATS.joinToString()}")
                }
            }
            "number", "integer" -> {
                listOf("minimum", "maximum").forEach { k -> s.get(k)?.let { if (!it.isNumber) v += FieldViolation("$at.$k", "a number") } }
                order(s, "minimum", "maximum", at, v)
            }
            "array" -> {
                bound(s, "minItems", at, v)
                bound(s, "maxItems", at, v)
                order(s, "minItems", "maxItems", at, v)
                val items = s.get("items")
                if (items == null || !items.isObject) {
                    v += FieldViolation("$at.items", "the schema of every item")
                } else {
                    node(items as ObjectNode, "$at.items", depth + 1, v)
                }
            }
        }
    }

    private fun objectNode(
        s: ObjectNode,
        at: String,
        depth: Int,
        v: MutableList<FieldViolation>
    ) {
        val properties = s.get("properties")
        val names =
            when {
                properties == null -> emptySet()
                !properties.isObject -> {
                    v += FieldViolation("$at.properties", "an object of schemas")
                    emptySet()
                }
                else -> properties.propertyNames().toSet()
            }
        if (properties != null && properties.isObject) {
            properties.properties().forEach { (name, child) ->
                val p = "$at.properties.$name"
                when {
                    !PROPERTY.matches(name) -> v += FieldViolation(p, "a property name of letters, digits, '_', '.', '-' (at most 64)")
                    SECRET_NAME.containsMatchIn(name) -> v += FieldViolation(p, "a tool takes no credentials: the connection holds them")
                    !child.isObject -> v += FieldViolation(p, "a schema object")
                    else -> node(child as ObjectNode, p, depth + 1, v)
                }
            }
        }
        s.get("required")?.let { r ->
            if (!r.isArray || (0 until r.size()).any { !r.get(it).isString }) {
                v += FieldViolation("$at.required", "an array of property names")
            } else {
                (0 until r.size()).filterNot { r.get(it).stringValue() in names }.forEach { v += FieldViolation("$at.required[$it]", "not a property") }
            }
        }
        when (val extra = s.get("additionalProperties")) {
            null -> s.put("additionalProperties", false)
            else -> if (!extra.isBoolean || extra.booleanValue()) v += FieldViolation("$at.additionalProperties", "only false: tool inputs are closed objects")
        }
    }

    private fun bound(
        s: ObjectNode,
        key: String,
        at: String,
        v: MutableList<FieldViolation>
    ) {
        s.get(key)?.let { if (!it.isIntegralNumber || it.longValue() < 0) v += FieldViolation("$at.$key", "a non-negative integer") }
    }

    private fun order(
        s: ObjectNode,
        min: String,
        max: String,
        at: String,
        v: MutableList<FieldViolation>
    ) {
        val lo = s.get(min)?.takeIf { it.isNumber } ?: return
        val hi = s.get(max)?.takeIf { it.isNumber } ?: return
        if (lo.decimalValue() > hi.decimalValue()) v += FieldViolation("$at.$min", "greater than $max")
    }

    private fun fits(
        type: String,
        value: JsonNode
    ): Boolean =
        when (type) {
            "string" -> value.isString
            "number" -> value.isNumber
            "integer" -> value.isNumber && isWhole(value)
            "boolean" -> value.isBoolean
            "object" -> value.isObject
            "array" -> value.isArray
            else -> false
        }

    private fun isWhole(value: JsonNode): Boolean = value.isIntegralNumber || value.decimalValue().stripTrailingZeros().scale() <= 0

    // an input against a schema check() accepted. every violation, each with its path (input.fields.name)
    fun validate(
        schema: JsonNode,
        input: JsonNode?,
        at: String = "input"
    ): List<FieldViolation> {
        val v = mutableListOf<FieldViolation>()
        value(schema, input, at, v)
        return v
    }

    private fun value(
        s: JsonNode,
        input: JsonNode?,
        at: String,
        v: MutableList<FieldViolation>
    ) {
        val type = s.get("type").stringValue()
        if (input == null || input.isNull || input.isMissingNode || !fits(type, input)) {
            v += FieldViolation(at, "expected $type")
            return
        }
        s.get("enum")?.let { e ->
            if ((0 until e.size()).none { same(e.get(it), input) }) {
                v += FieldViolation(at, "one of ${(0 until e.size()).joinToString { i -> e.get(i).toString() }}")
                return
            }
        }
        when (type) {
            "object" -> {
                val properties = s.get("properties")
                (s.get("required")?.let { r -> (0 until r.size()).map { r.get(it).stringValue() } } ?: emptyList())
                    .filterNot { input.has(it) }
                    .forEach { v += FieldViolation("$at.$it", "required") }
                input.properties().forEach { (name, child) ->
                    val p = properties?.get(name)
                    if (p == null) v += FieldViolation("$at.$name", "not allowed") else value(p, child, "$at.$name", v)
                }
            }
            "string" -> {
                val text = input.stringValue()
                val length = text.codePointCount(0, text.length)
                s.get("minLength")?.let { if (length < it.longValue()) v += FieldViolation(at, "at least ${it.longValue()} characters") }
                s.get("maxLength")?.let { if (length > it.longValue()) v += FieldViolation(at, "at most ${it.longValue()} characters") }
                when (s.get("format")?.stringValue()) {
                    "date" -> if (runCatching { LocalDate.parse(text) }.isFailure) v += FieldViolation(at, "an ISO date (2026-11-05)")
                    "date-time" ->
                        if (runCatching { OffsetDateTime.parse(text) }.isFailure) v += FieldViolation(at, "an ISO date-time with offset")
                }
            }
            "number", "integer" -> {
                val n = input.decimalValue()
                s.get("minimum")?.let { if (n < it.decimalValue()) v += FieldViolation(at, "at least ${it.decimalValue().toPlainString()}") }
                s.get("maximum")?.let { if (n > it.decimalValue()) v += FieldViolation(at, "at most ${it.decimalValue().toPlainString()}") }
            }
            "array" -> {
                s.get("minItems")?.let { if (input.size() < it.longValue()) v += FieldViolation(at, "at least ${it.longValue()} items") }
                s.get("maxItems")?.let { if (input.size() > it.longValue()) v += FieldViolation(at, "at most ${it.longValue()} items") }
                val items = s.get("items")
                (0 until input.size()).forEach { value(items, input.get(it), "$at[$it]", v) }
            }
        }
    }

    private fun same(
        a: JsonNode,
        b: JsonNode
    ): Boolean = if (a.isNumber && b.isNumber) a.decimalValue().compareTo(b.decimalValue()) == 0 else a == b

    // the input schema of a record operation, from what the connector describes today. read-only, reference and
    // credential-like fields are left out (ADR-0026). fieldTypes: what each exposed field converts to
    fun forResource(
        operation: ToolOperation,
        schema: ResourceSchema?,
        externalIdField: String?,
        json: JsonMapper
    ): Pair<ObjectNode, Map<String, FieldType>> {
        val root = json.createObjectNode().put("type", "object")
        val properties = root.putObject("properties")
        val required = mutableListOf<String>()
        val types = mutableMapOf<String, FieldType>()

        fun id(
            name: String,
            description: String
        ) {
            properties
                .putObject(name)
                .put("type", "string")
                .put("minLength", 1)
                .put("maxLength", 1024)
                .put("description", description)
            required += name
        }
        when (operation) {
            ToolOperation.READ, ToolOperation.DELETE -> id("id", "the provider's record id")
            ToolOperation.LIST -> {
                properties
                    .putObject("cursor")
                    .put("type", "string")
                    .put("maxLength", 4096)
                    .put("description", "nextCursor of the previous page")
                properties
                    .putObject("pageSize")
                    .put("type", "integer")
                    .put("minimum", 1)
                    .put("maximum", 100)
            }
            ToolOperation.CREATE, ToolOperation.UPDATE, ToolOperation.UPSERT -> {
                if (operation == ToolOperation.UPDATE) id("id", "the provider's record id")
                if (operation == ToolOperation.UPSERT) id("externalId", "the value of $externalIdField that identifies the record")
                val fields = properties.putObject("fields").put("type", "object")
                val fieldProps = fields.putObject("properties")
                val fieldRequired = fields.putArray("required")
                schema!!
                    .fields
                    .filter { !it.readOnly && it.type != FieldType.REFERENCE && !SECRET_NAME.containsMatchIn(it.name) && PROPERTY.matches(it.name) }
                    .filterNot { operation == ToolOperation.UPSERT && it.name == externalIdField }
                    .forEach { f ->
                        val p = fieldProps.putObject(f.name)
                        when (f.type) {
                            FieldType.TEXT -> p.put("type", "string")
                            FieldType.NUMBER -> p.put("type", "number")
                            FieldType.BOOLEAN -> p.put("type", "boolean")
                            FieldType.DATE -> p.put("type", "string").put("format", "date")
                            FieldType.DATETIME -> p.put("type", "string").put("format", "date-time")
                            FieldType.CHOICE ->
                                p
                                    .put(
                                        "type",
                                        "string"
                                    ).also { if (f.options.isNotEmpty()) it.putArray("enum").apply { f.options.forEach(::add) } }
                            FieldType.MULTI_CHOICE ->
                                p.put("type", "array").putObject("items").put("type", "string").also {
                                    if (f.options.isNotEmpty()) it.putArray("enum").apply { f.options.forEach(::add) }
                                }
                            FieldType.JSON -> p.put("type", "object")
                            FieldType.REFERENCE -> Unit
                        }
                        p.put("title", f.label)
                        types[f.name] = f.type
                        if (operation == ToolOperation.CREATE && f.required) fieldRequired.add(f.name)
                    }
                if (fieldRequired.size() == 0) fields.remove("required")
                fields.put("additionalProperties", false)
                required += "fields"
            }
        }
        if (required.isNotEmpty()) root.putArray("required").apply { required.forEach(::add) }
        root.put("additionalProperties", false)
        return root to types
    }
}
