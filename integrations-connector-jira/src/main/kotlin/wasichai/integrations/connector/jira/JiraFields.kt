package wasichai.integrations.connector.jira

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.ResourceSchema
import java.time.LocalDate

// how a jira field value looks on the wire, from its /field schema. OTHER is kept as json and never written
internal enum class JiraKind {
    TEXT,
    RICH_TEXT,
    NUMBER,
    DATE,
    DATETIME,

    // {"value": ..}: single select custom fields
    OPTION,
    OPTIONS,

    // plain strings: labels
    STRINGS,

    // {"name": ..}: priority, issue type, status, resolution
    NAMED,

    // [{"name": ..}]: components, versions
    NAMES,
    USER,
    PROJECT,
    OTHER
}

// one project's issue schema, plus what the sdk schema has no room for (the wire kind per field)
internal class JiraIssueSchema(
    val schema: ResourceSchema,
    val kinds: Map<String, JiraKind>
)

internal object JiraFields {
    internal val json: JsonMapper = JsonMapper.builder().build()
    private val nodes = JsonNodeFactory.instance

    const val KEY = "key"
    private const val TEXTAREA = "com.atlassian.jira.plugin.system.customfieldtypes:textarea"

    // system fields jira computes, or that change only through their own api (status by transitions, comments...)
    private val READ_ONLY =
        setOf(
            "created",
            "updated",
            "status",
            "resolution",
            "resolutiondate",
            "creator",
            "lastViewed",
            "votes",
            "watches",
            "workratio",
            "progress",
            "aggregateprogress",
            "aggregatetimespent",
            "aggregatetimeestimate",
            "aggregatetimeoriginalestimate",
            "timespent",
            "timeestimate",
            "statuscategorychangedate",
            "issuekey",
            "thumbnail",
            "subtasks",
            "issuelinks",
            "attachment",
            "comment",
            "worklog",
            "project",
            "parent"
        )

    // a create without these fails in every project; others may be required by a project's screens (jira says so on write)
    private val REQUIRED = setOf("summary", "issuetype")

    fun kind(schema: JsonNode): JiraKind {
        val type = schema.path("type").asString("")
        val system = schema.path("system").asString("")
        val custom = schema.path("custom").asString("")
        return when (type) {
            "string" -> if (system == "description" || system == "environment" || custom == TEXTAREA) JiraKind.RICH_TEXT else JiraKind.TEXT
            "number" -> JiraKind.NUMBER
            "date" -> JiraKind.DATE
            "datetime" -> JiraKind.DATETIME
            "option" -> JiraKind.OPTION
            "priority", "issuetype", "status", "resolution" -> JiraKind.NAMED
            "user" -> JiraKind.USER
            "project" -> JiraKind.PROJECT
            "array" ->
                when (schema.path("items").asString("")) {
                    "option" -> JiraKind.OPTIONS
                    "string" -> JiraKind.STRINGS
                    "component", "version" -> JiraKind.NAMES
                    else -> JiraKind.OTHER
                }
            else -> JiraKind.OTHER
        }
    }

    private fun fieldType(kind: JiraKind): FieldType =
        when (kind) {
            JiraKind.TEXT, JiraKind.RICH_TEXT -> FieldType.TEXT
            JiraKind.NUMBER -> FieldType.NUMBER
            JiraKind.DATE -> FieldType.DATE
            JiraKind.DATETIME -> FieldType.DATETIME
            JiraKind.OPTION, JiraKind.NAMED -> FieldType.CHOICE
            JiraKind.OPTIONS, JiraKind.STRINGS, JiraKind.NAMES -> FieldType.MULTI_CHOICE
            JiraKind.USER, JiraKind.PROJECT -> FieldType.REFERENCE
            JiraKind.OTHER -> FieldType.JSON
        }

    // fields: GET /rest/api/3/field. project: GET /rest/api/3/project/{key}, for its issue types
    fun schema(
        project: JsonNode,
        fields: JsonNode
    ): JiraIssueSchema {
        val key = project.path("key").asString()
        val kinds = mutableMapOf(KEY to JiraKind.TEXT)
        val issueTypes = project.path("issueTypes").values().map { it.path("name").asString() }
        val descriptors =
            mutableListOf(FieldDescriptor(KEY, "Key", FieldType.TEXT, readOnly = true, nativeType = "issuekey"))
        fields
            .values()
            .filter { it.has("schema") && it.path("id").asString("") != KEY }
            .forEach { field ->
                val id = field.path("id").asString()
                val kind = kind(field.path("schema"))
                kinds[id] = kind
                val schema = field.path("schema")
                descriptors +=
                    FieldDescriptor(
                        name = id,
                        label = field.path("name").asString(id),
                        type = fieldType(kind),
                        required = id in REQUIRED,
                        readOnly = id in READ_ONLY || kind == JiraKind.OTHER || kind == JiraKind.PROJECT,
                        options = if (id == "issuetype") issueTypes else emptyList(),
                        nativeType = schema.path("custom").asString(null) ?: schema.path("type").asString()
                    )
            }
        return JiraIssueSchema(ResourceSchema(key, project.path("name").asString(key), descriptors.sortedBy { it.name }), kinds)
    }

    // issue json -> typed values. a field the schema does not know is read by its json type
    fun read(
        issue: JsonNode,
        schema: JiraIssueSchema?
    ): Map<String, FieldValue> {
        val out = mutableMapOf<String, FieldValue>(KEY to FieldValue.Text(issue.path("key").asString("")))
        issue.path("fields").properties().forEach { (name, value) -> out[name] = value(value, schema?.kinds?.get(name)) }
        return out
    }

    private fun value(
        node: JsonNode,
        kind: JiraKind?
    ): FieldValue {
        if (node.isNull || node.isMissingNode) return FieldValue.Null
        return when (kind) {
            JiraKind.TEXT -> FieldValue.Text(node.asString())
            JiraKind.RICH_TEXT -> FieldValue.Text(if (node.isObject) JiraAdf.toText(node) else node.asString())
            JiraKind.NUMBER -> FieldValue.Number(node.decimalValue())
            JiraKind.DATE -> FieldValue.Date(LocalDate.parse(node.asString()))
            JiraKind.DATETIME -> FieldValue.DateTime(JiraTimes.parse(node.asString()))
            JiraKind.OPTION -> FieldValue.Choice(node.path("value").asString())
            JiraKind.NAMED -> FieldValue.Choice(node.path("name").asString())
            JiraKind.OPTIONS -> FieldValue.MultiChoice(node.values().map { it.path("value").asString() })
            JiraKind.STRINGS -> FieldValue.MultiChoice(node.values().map { it.asString() })
            JiraKind.NAMES -> FieldValue.MultiChoice(node.values().map { it.path("name").asString() })
            JiraKind.USER -> FieldValue.Reference("user", node.path("accountId").asString())
            JiraKind.PROJECT -> FieldValue.Reference("project", node.path("key").asString())
            JiraKind.OTHER -> FieldValue.Json(node)
            null ->
                when {
                    node.isString -> FieldValue.Text(node.asString())
                    node.isNumber -> FieldValue.Number(node.decimalValue())
                    node.isBoolean -> FieldValue.Bool(node.asBoolean())
                    else -> FieldValue.Json(node)
                }
        }
    }

    // the "fields" object of a create or edit. unknown or read-only fields are refused before anything is sent
    fun write(
        fields: Map<String, FieldValue>,
        schema: JiraIssueSchema
    ): ObjectNode {
        val out = nodes.objectNode()
        fields.forEach { (name, value) ->
            val field = schema.schema.field(name) ?: throw ConnectorException.Permanent("jira field '$name' does not exist", mapOf("field" to name))
            if (field.readOnly) throw ConnectorException.Permanent("jira field '$name' is read-only", mapOf("field" to name))
            out.set(name, node(schema.kinds.getValue(name), value))
        }
        return out
    }

    private fun node(
        kind: JiraKind,
        value: FieldValue
    ): JsonNode {
        if (value is FieldValue.Null) return if (kind in setOf(JiraKind.OPTIONS, JiraKind.STRINGS, JiraKind.NAMES)) nodes.arrayNode() else nodes.nullNode()
        return when (kind) {
            JiraKind.TEXT -> nodes.stringNode(text(value))
            JiraKind.RICH_TEXT -> JiraAdf.fromText(text(value))
            JiraKind.NUMBER -> nodes.numberNode((value as? FieldValue.Number)?.value ?: text(value).toBigDecimal())
            JiraKind.DATE -> nodes.stringNode(text(value))
            JiraKind.DATETIME -> nodes.stringNode(if (value is FieldValue.DateTime) JiraTimes.format(value.value) else text(value))
            JiraKind.OPTION -> nodes.objectNode().put("value", text(value))
            JiraKind.NAMED -> nodes.objectNode().put("name", text(value))
            JiraKind.USER -> nodes.objectNode().put("accountId", (value as? FieldValue.Reference)?.id ?: text(value))
            JiraKind.OPTIONS -> nodes.arrayNode().apply { values(value).forEach { add(nodes.objectNode().put("value", it)) } }
            JiraKind.STRINGS -> nodes.arrayNode().apply { values(value).forEach { add(it) } }
            JiraKind.NAMES -> nodes.arrayNode().apply { values(value).forEach { add(nodes.objectNode().put("name", it)) } }
            JiraKind.PROJECT, JiraKind.OTHER -> throw ConnectorException.Permanent("jira field kind $kind is not writable")
        }
    }

    private fun values(value: FieldValue): List<String> = (value as? FieldValue.MultiChoice)?.values ?: listOf(text(value))

    private fun text(value: FieldValue): String =
        when (value) {
            is FieldValue.Text -> value.value
            is FieldValue.Choice -> value.value
            is FieldValue.Number -> value.value.toPlainString()
            is FieldValue.Bool -> value.value.toString()
            is FieldValue.Date -> value.value.toString()
            is FieldValue.DateTime -> JiraTimes.format(value.value)
            is FieldValue.MultiChoice -> value.values.joinToString(", ")
            is FieldValue.Reference -> value.id
            is FieldValue.Json -> value.value.toString()
            FieldValue.Null -> ""
        }
}
