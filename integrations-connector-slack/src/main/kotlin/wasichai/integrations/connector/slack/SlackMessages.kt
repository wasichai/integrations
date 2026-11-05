package wasichai.integrations.connector.slack

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.ResourceSchema
import java.time.Instant

// a message's fields, the same in every channel. only text is written: blocks, attachments and files are read-only,
// a write that would need them is refused rather than flattened
internal object SlackMessages {
    internal val json: JsonMapper = JsonMapper.builder().build()

    // a deleted parent that still has replies stays in the history as a tombstone: gone as a record
    const val TOMBSTONE = "tombstone"

    private val FIELDS =
        listOf(
            FieldDescriptor("ts", "Timestamp", FieldType.TEXT, readOnly = true, nativeType = "ts"),
            FieldDescriptor("text", "Text", FieldType.TEXT, required = true, nativeType = "mrkdwn"),
            FieldDescriptor("user", "Author", FieldType.REFERENCE, readOnly = true, nativeType = "user"),
            FieldDescriptor("bot_id", "Bot", FieldType.TEXT, readOnly = true, nativeType = "string"),
            // channel_join, bot_message, thread_broadcast...: null for a plain user message
            FieldDescriptor("subtype", "Subtype", FieldType.TEXT, readOnly = true, nativeType = "string"),
            FieldDescriptor("thread_ts", "Thread", FieldType.TEXT, readOnly = true, nativeType = "ts"),
            FieldDescriptor("reply_count", "Replies", FieldType.NUMBER, readOnly = true, nativeType = "integer"),
            FieldDescriptor("blocks", "Blocks", FieldType.JSON, readOnly = true, nativeType = "block[]"),
            FieldDescriptor("posted_at", "Posted", FieldType.DATETIME, readOnly = true, nativeType = "ts"),
            FieldDescriptor("edited_at", "Edited", FieldType.DATETIME, readOnly = true, nativeType = "ts")
        )

    fun schema(
        resource: String,
        label: String
    ) = ResourceSchema(resource, label, FIELDS)

    fun isTombstone(message: JsonNode): Boolean = message.path("subtype").asString(null) == TOMBSTONE

    // an edit stamps edited.ts; an unedited message's version is its own ts
    fun version(message: JsonNode): String? = message.path("edited").path("ts").asString(null) ?: message.path("ts").asString(null)

    fun modifiedAt(message: JsonNode): Instant? = SlackTs.instant(version(message))

    fun read(message: JsonNode): Map<String, FieldValue> {
        fun text(name: String) = message.path(name).asString(null)?.let { FieldValue.Text(it) } ?: FieldValue.Null

        fun time(ts: String?) = SlackTs.instant(ts)?.let { FieldValue.DateTime(it) } ?: FieldValue.Null
        val user = message.path("user").asString(null)
        val blocks = message.path("blocks")
        val replies = message.path("reply_count")
        return mapOf(
            "ts" to text("ts"),
            "text" to text("text"),
            "user" to (user?.let { FieldValue.Reference("user", it) } ?: FieldValue.Null),
            "bot_id" to text("bot_id"),
            "subtype" to text("subtype"),
            "thread_ts" to text("thread_ts"),
            "reply_count" to if (replies.isNumber) FieldValue.Number(replies.decimalValue()) else FieldValue.Null,
            "blocks" to if (blocks.isArray) FieldValue.Json(blocks) else FieldValue.Null,
            "posted_at" to time(message.path("ts").asString(null)),
            "edited_at" to time(message.path("edited").path("ts").asString(null))
        )
    }

    // the text of a post or edit. unknown or read-only fields are refused before anything is sent
    fun text(fields: Map<String, FieldValue>): String {
        fields.keys.forEach { name ->
            val field =
                FIELDS.firstOrNull { it.name == name }
                    ?: throw ConnectorException.Permanent("slack message field '$name' does not exist", mapOf("field" to name))
            if (field.readOnly) throw ConnectorException.Permanent("slack message field '$name' is read-only", mapOf("field" to name))
        }
        val text =
            when (val value = fields["text"]) {
                is FieldValue.Text -> value.value
                is FieldValue.Choice -> value.value
                is FieldValue.Number -> value.value.toPlainString()
                else -> null
            }
        // slack refuses an empty message (no_text): said here, before any call
        if (text.isNullOrBlank()) throw ConnectorException.Permanent("a slack message needs a non-empty text", mapOf("field" to "text"))
        return text
    }
}
