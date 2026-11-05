package wasichai.integrations.connector.github

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.ResourceSchema
import java.time.Instant

// an issue's fields, the same in every repository: github has no custom issue fields in rest v3.
// users are referenced by login, which is what assignees take on write
internal object GitHubIssues {
    internal val json: JsonMapper = JsonMapper.builder().build()
    private val nodes = JsonNodeFactory.instance

    val STATES = listOf("open", "closed")
    val STATE_REASONS = listOf("completed", "not_planned", "reopened")

    private val FIELDS =
        listOf(
            FieldDescriptor("number", "Number", FieldType.NUMBER, readOnly = true, nativeType = "integer"),
            FieldDescriptor("title", "Title", FieldType.TEXT, required = true, nativeType = "string"),
            FieldDescriptor("body", "Body", FieldType.TEXT, nativeType = "markdown"),
            FieldDescriptor("state", "State", FieldType.CHOICE, options = STATES, nativeType = "string"),
            FieldDescriptor("state_reason", "State reason", FieldType.CHOICE, options = STATE_REASONS, nativeType = "string"),
            FieldDescriptor("labels", "Labels", FieldType.MULTI_CHOICE, nativeType = "label[]"),
            FieldDescriptor("assignees", "Assignees", FieldType.MULTI_CHOICE, nativeType = "user[]"),
            FieldDescriptor("milestone", "Milestone", FieldType.REFERENCE, nativeType = "milestone"),
            FieldDescriptor("user", "Author", FieldType.REFERENCE, readOnly = true, nativeType = "user"),
            // locking has its own endpoint
            FieldDescriptor("locked", "Locked", FieldType.BOOLEAN, readOnly = true, nativeType = "boolean"),
            FieldDescriptor("comments", "Comments", FieldType.NUMBER, readOnly = true, nativeType = "integer"),
            FieldDescriptor("html_url", "URL", FieldType.TEXT, readOnly = true, nativeType = "uri"),
            FieldDescriptor("created_at", "Created", FieldType.DATETIME, readOnly = true, nativeType = "date-time"),
            FieldDescriptor("updated_at", "Updated", FieldType.DATETIME, readOnly = true, nativeType = "date-time"),
            FieldDescriptor("closed_at", "Closed", FieldType.DATETIME, readOnly = true, nativeType = "date-time")
        )

    fun schema(
        resource: String,
        label: String
    ) = ResourceSchema(resource, label, FIELDS)

    // the issues api returns pull requests too; they carry a pull_request key and are not records here (ADR-0019)
    fun isPullRequest(issue: JsonNode): Boolean = issue.has("pull_request")

    fun updatedAt(issue: JsonNode): Instant? = issue.path("updated_at").asString(null)?.let { runCatching { Instant.parse(it) }.getOrNull() }

    fun read(issue: JsonNode): Map<String, FieldValue> {
        fun text(name: String) = issue.path(name).let { if (it.isNull || it.isMissingNode) FieldValue.Null else FieldValue.Text(it.asString()) }

        fun time(name: String) = issue.path(name).asString(null)?.let { runCatching { FieldValue.DateTime(Instant.parse(it)) }.getOrNull() } ?: FieldValue.Null

        fun number(name: String) = issue.path(name).let { if (it.isNumber) FieldValue.Number(it.decimalValue()) else FieldValue.Null }

        fun choice(name: String) = issue.path(name).asString(null)?.let { FieldValue.Choice(it) } ?: FieldValue.Null
        val milestone = issue.path("milestone")
        val author = issue.path("user").path("login").asString(null)
        return mapOf(
            "number" to number("number"),
            "title" to text("title"),
            "body" to text("body"),
            "state" to choice("state"),
            "state_reason" to choice("state_reason"),
            // labels come as objects; github also documents plain strings in places
            "labels" to FieldValue.MultiChoice(issue.path("labels").values().map { if (it.isString) it.asString() else it.path("name").asString() }),
            "assignees" to FieldValue.MultiChoice(issue.path("assignees").values().map { it.path("login").asString() }),
            "milestone" to if (milestone.isObject) FieldValue.Reference("milestone", milestone.path("number").asString()) else FieldValue.Null,
            "user" to (author?.let { FieldValue.Reference("user", it) } ?: FieldValue.Null),
            "locked" to FieldValue.Bool(issue.path("locked").asBoolean(false)),
            "comments" to number("comments"),
            "html_url" to text("html_url"),
            "created_at" to time("created_at"),
            "updated_at" to time("updated_at"),
            "closed_at" to time("closed_at")
        )
    }

    // the body of a create or edit. unknown or read-only fields are refused before anything is sent
    fun write(fields: Map<String, FieldValue>): ObjectNode {
        val out = nodes.objectNode()
        fields.forEach { (name, value) ->
            val field =
                FIELDS.firstOrNull { it.name == name }
                    ?: throw ConnectorException.Permanent("github issue field '$name' does not exist", mapOf("field" to name))
            if (field.readOnly) throw ConnectorException.Permanent("github issue field '$name' is read-only", mapOf("field" to name))
            out.set(name, node(name, value))
        }
        return out
    }

    private fun node(
        name: String,
        value: FieldValue
    ): JsonNode =
        when (name) {
            "labels", "assignees" ->
                nodes.arrayNode().apply {
                    when (value) {
                        FieldValue.Null -> Unit
                        is FieldValue.MultiChoice -> value.values.forEach { add(it) }
                        else -> add(text(name, value))
                    }
                }
            "milestone" ->
                when (value) {
                    FieldValue.Null -> nodes.nullNode()
                    is FieldValue.Reference -> nodes.numberNode(number(name, value.id))
                    is FieldValue.Number -> nodes.numberNode(number(name, value.value.toPlainString()))
                    else -> nodes.numberNode(number(name, text(name, value)))
                }
            // a null title is refused by text(): github requires one
            "title" -> nodes.stringNode(text(name, value))
            "state" -> nodes.stringNode(oneOf(name, text(name, value), STATES))
            "state_reason" -> if (value is FieldValue.Null) nodes.nullNode() else nodes.stringNode(oneOf(name, text(name, value), STATE_REASONS))
            else -> if (value is FieldValue.Null) nodes.nullNode() else nodes.stringNode(text(name, value))
        }

    private fun oneOf(
        name: String,
        value: String,
        allowed: List<String>
    ): String {
        if (value !in allowed) throw ConnectorException.Permanent("github issue $name must be one of $allowed", mapOf("field" to name))
        return value
    }

    private fun number(
        name: String,
        value: String
    ): Long = value.toLongOrNull()?.takeIf { it > 0 } ?: throw ConnectorException.Permanent("github $name must be a number", mapOf("field" to name))

    private fun text(
        name: String,
        value: FieldValue
    ): String =
        when (value) {
            is FieldValue.Text -> value.value
            is FieldValue.Choice -> value.value
            is FieldValue.Number -> value.value.toPlainString()
            is FieldValue.Reference -> value.id
            is FieldValue.Bool -> value.value.toString()
            is FieldValue.Date -> value.value.toString()
            is FieldValue.DateTime -> value.value.toString()
            is FieldValue.MultiChoice, is FieldValue.Json, FieldValue.Null ->
                throw ConnectorException.Permanent("github issue field '$name' cannot take this value", mapOf("field" to name))
        }
}
