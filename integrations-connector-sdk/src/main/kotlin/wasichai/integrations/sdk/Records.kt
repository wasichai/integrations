package wasichai.integrations.sdk

import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

// a provider record in a form the platform can compare and map. the native payload rides along untouched.
data class ProviderRecord(
    val id: String,
    // an etag, revision or timestamp the provider can check on write; null when it has none
    val version: String?,
    // when the provider says it changed; null when it does not expose a reliable timestamp
    val modifiedAt: Instant?,
    val fields: Map<String, FieldValue>,
    val native: JsonNode? = null
)

// typed values: the mapping engine converts between them and hashes them, never compares raw json
sealed interface FieldValue {
    data object Null : FieldValue

    data class Text(
        val value: String
    ) : FieldValue

    data class Number(
        val value: BigDecimal
    ) : FieldValue

    data class Bool(
        val value: Boolean
    ) : FieldValue

    data class Date(
        val value: LocalDate
    ) : FieldValue

    data class DateTime(
        val value: Instant
    ) : FieldValue

    data class Choice(
        val value: String
    ) : FieldValue

    data class MultiChoice(
        val values: List<String>
    ) : FieldValue

    data class Reference(
        val resource: String,
        val id: String
    ) : FieldValue

    data class Json(
        val value: JsonNode
    ) : FieldValue
}

data class RecordPage(
    val records: List<ProviderRecord>,
    val nextCursor: String?
)

data class ReadRequest(
    val resource: String,
    val cursor: String? = null,
    val pageSize: Int = 100
) {
    init {
        require(pageSize in 1..1000) { "pageSize must be within 1..1000" }
    }
}

// one change seen by a change feed
sealed interface Change {
    val id: String

    data class Upserted(
        val record: ProviderRecord
    ) : Change {
        override val id: String get() = record.id
    }

    data class Deleted(
        override val id: String,
        val deletedAt: Instant?
    ) : Change
}

// checkpoint is opaque to the platform: only the connector reads it. null means "from the start"
data class ChangePage(
    val changes: List<Change>,
    val checkpoint: String?,
    val hasMore: Boolean
)

data class WriteResult(
    val id: String,
    val version: String?,
    val modifiedAt: Instant?,
    val created: Boolean
)
