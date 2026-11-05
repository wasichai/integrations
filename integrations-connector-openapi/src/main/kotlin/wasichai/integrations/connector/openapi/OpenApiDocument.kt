package wasichai.integrations.connector.openapi

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.MissingNode
import tools.jackson.dataformat.yaml.YAMLMapper
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.HttpMethod

// the parts of an openapi 3.x document the connector reads: operations by operationId, their parameters, json bodies,
// local $refs and object schemas. external $refs, callbacks and links are not read (ADR-0021)
internal class OpenApiDocument(
    val root: JsonNode
) {
    class Operation(
        val id: String,
        val method: HttpMethod,
        // relative to the base url, with {name} placeholders
        val path: String,
        val parameters: List<Parameter>,
        val node: JsonNode
    ) {
        fun pathParameters(): List<Parameter> = parameters.filter { it.location == "path" }

        fun query(name: String): Parameter? = parameters.firstOrNull { it.location == "query" && it.name == name }
    }

    class Parameter(
        val name: String,
        val location: String,
        val required: Boolean
    )

    // an object schema with allOf merged: what a record (or a request body) looks like
    class Shape(
        val properties: Map<String, JsonNode>,
        val required: Set<String>
    )

    val operations: Map<String, Operation> = readOperations()

    // follows local refs ("#/components/..."); a ref elsewhere or a cycle is a configuration error
    fun deref(node: JsonNode): JsonNode {
        var current = node
        val seen = mutableSetOf<String>()
        while (current.isObject && current.has("\$ref")) {
            val ref = current.path("\$ref").asString("")
            if (!ref.startsWith("#/")) throw IllegalArgumentException("only local \$refs are supported, not '$ref'")
            if (!seen.add(ref)) throw IllegalArgumentException("\$ref cycle at '$ref'")
            current = root.at(ref.substring(1))
            if (current.isMissingNode) throw IllegalArgumentException("\$ref '$ref' points at nothing")
        }
        return current
    }

    // the json schema of the first 2xx response
    fun responseSchema(operation: Operation): JsonNode? {
        val responses = deref(operation.node.path("responses"))
        val success =
            responses
                .propertyNames()
                .filter { it.length == 3 && it.startsWith("2") || it == "2XX" }
                .sorted()
                .map { deref(responses.path(it)) }
                .firstOrNull { json(it).isObject } ?: return null
        return json(success).path("schema").takeUnless { it.isMissingNode }?.let(::deref)
    }

    fun requestSchema(operation: Operation): JsonNode? {
        val body = deref(operation.node.path("requestBody"))
        return json(body).path("schema").takeUnless { it.isMissingNode }?.let(::deref)
    }

    // the json media type the operation takes (application/merge-patch+json for some patches)
    fun requestMediaType(operation: Operation): String = jsonMediaType(deref(operation.node.path("requestBody")).path("content")) ?: "application/json"

    // allOf parts merged; null when the schema is not an object (a oneOf, an array, a scalar)
    fun shape(
        schema: JsonNode,
        depth: Int = 0
    ): Shape? {
        if (depth > MAX_DEPTH) throw IllegalArgumentException("allOf nested deeper than $MAX_DEPTH")
        val resolved = deref(schema)
        val properties = linkedMapOf<String, JsonNode>()
        val required = linkedSetOf<String>()
        var objectLike = false
        resolved.path("allOf").values().forEach { part ->
            val inner = shape(part, depth + 1) ?: return null
            properties.putAll(inner.properties)
            required.addAll(inner.required)
            objectLike = true
        }
        if (types(resolved).contains("object") || resolved.has("properties")) objectLike = true
        resolved.path("properties").properties().forEach { (name, property) -> properties[name] = property }
        resolved.path("required").values().forEach { required.add(it.asString()) }
        return if (objectLike) Shape(properties, required) else null
    }

    // a property as a field: openapi types to FieldType; nested objects and mixed arrays stay json
    fun field(
        name: String,
        property: JsonNode,
        required: Boolean,
        readOnly: Boolean
    ): FieldDescriptor {
        val schema = deref(property)
        val type = types(schema).firstOrNull { it != "null" }
        val format = schema.path("format").asString(null)
        val options =
            schema
                .path("enum")
                .values()
                .filter { it.isString }
                .map { it.asString() }
        val items = if (type == "array") deref(schema.path("items")) else MissingNode.getInstance()
        val itemOptions =
            items
                .path("enum")
                .values()
                .filter { it.isString }
                .map { it.asString() }
        val fieldType =
            when {
                schema.has("oneOf") || schema.has("anyOf") -> FieldType.JSON
                type == "string" && options.isNotEmpty() -> FieldType.CHOICE
                type == "string" && format == "date" -> FieldType.DATE
                type == "string" && format == "date-time" -> FieldType.DATETIME
                type == "string" -> FieldType.TEXT
                type == "integer" || type == "number" -> FieldType.NUMBER
                type == "boolean" -> FieldType.BOOLEAN
                type == "array" && itemOptions.isNotEmpty() -> FieldType.MULTI_CHOICE
                else -> FieldType.JSON
            }
        val nativeType =
            when {
                property.has("\$ref") -> property.path("\$ref").asString().substringAfterLast('/')
                type == null -> "any"
                format != null -> "$type/$format"
                else -> type
            }
        return FieldDescriptor(
            name = name,
            label = schema.path("title").asString(null) ?: name,
            type = fieldType,
            required = required,
            readOnly = readOnly || schema.path("readOnly").asBoolean(false),
            options = if (fieldType == FieldType.MULTI_CHOICE) itemOptions else options,
            nativeType = nativeType
        )
    }

    // 3.0 writes "type": "string", 3.1 also "type": ["string", "null"]
    private fun types(schema: JsonNode): List<String> {
        val type = schema.path("type")
        return if (type.isArray) type.values().map { it.asString() } else listOfNotNull(type.asString(null))
    }

    // application/json or any +json media type
    private fun json(holder: JsonNode): JsonNode {
        val content = holder.path("content")
        return jsonMediaType(content)?.let { content.path(it) } ?: MissingNode.getInstance()
    }

    private fun jsonMediaType(content: JsonNode): String? =
        content.propertyNames().firstOrNull { it == "application/json" || it.substringBefore(';').trim().endsWith("+json") }

    private fun readOperations(): Map<String, Operation> {
        val found = linkedMapOf<String, Operation>()
        root.path("paths").properties().forEach { (path, rawItem) ->
            val item = deref(rawItem)
            val shared = item.path("parameters").values().map(::parameter)
            METHODS.forEach { (key, method) ->
                val node = item.path(key)
                val id = node.path("operationId").asString(null) ?: return@forEach
                val own = node.path("parameters").values().map(::parameter)
                // an operation's parameter overrides the path item's one of the same name and location
                val parameters = own + shared.filterNot { s -> own.any { it.name == s.name && it.location == s.location } }
                if (found.put(id, Operation(id, method, path, parameters, node)) != null) {
                    throw IllegalArgumentException("operationId '$id' is used twice")
                }
            }
        }
        return found
    }

    private fun parameter(raw: JsonNode): Parameter {
        val node = deref(raw)
        val location = node.path("in").asString("")
        return Parameter(node.path("name").asString(""), location, location == "path" || node.path("required").asBoolean(false))
    }

    companion object {
        private const val MAX_DEPTH = 16
        private val json = JsonMapper.builder().build()
        private val yaml = YAMLMapper.builder().build()
        private val METHODS =
            listOf(
                "get" to HttpMethod.GET,
                "post" to HttpMethod.POST,
                "put" to HttpMethod.PUT,
                "patch" to HttpMethod.PATCH,
                "delete" to HttpMethod.DELETE
            )

        // json when it looks like json, yaml otherwise. swagger 2.0 is refused
        fun parse(text: String): OpenApiDocument {
            val root =
                runCatching { if (text.trimStart().startsWith("{")) json.readTree(text) else yaml.readTree(text) }
                    .getOrElse { throw IllegalArgumentException("the document is neither json nor yaml: ${it.message}") }
            if (root == null || !root.isObject) throw IllegalArgumentException("the document is not an object")
            val version = root.path("openapi").asString("")
            if (!version.startsWith("3.")) {
                throw IllegalArgumentException("only openapi 3.x documents are supported, this one says '${version.ifEmpty { "nothing" }}'")
            }
            return OpenApiDocument(root)
        }
    }
}
