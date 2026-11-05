package wasichai.integrations.connector.salesforce.pubsub

import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DecoderFactory
import java.time.Instant

// the part of a change event the feed trusts: which records of which object changed, how and when. the field values
// in the payload are not read: records are fetched by id, as the polling feed reads them
internal data class ChangeHeader(
    val entityName: String,
    val changeType: String,
    val recordIds: List<String>,
    val commitTimestamp: Instant
) {
    // GAP_CREATE, GAP_UPDATE, GAP_DELETE, GAP_UNDELETE, GAP_OVERFLOW: salesforce could not produce the regular events
    val gap: Boolean get() = changeType.startsWith("GAP_")
}

internal object ChangeEvents {
    // avro binary with the writer schema the event names; ChangeEventHeader is the same record on every cdc channel
    fun header(
        schema: Schema,
        payload: ByteArray
    ): ChangeHeader {
        val record = GenericDatumReader<GenericRecord>(schema).read(null, DecoderFactory.get().binaryDecoder(payload, null))
        val header =
            record.get("ChangeEventHeader") as? GenericRecord
                ?: throw IllegalStateException("event of schema ${schema.fullName} has no ChangeEventHeader: not a change data capture event")
        val ids = (header.get("recordIds") as? Collection<*>)?.map { it.toString() } ?: emptyList()
        val commit = (header.get("commitTimestamp") as? Number)?.toLong() ?: throw IllegalStateException("change event without commitTimestamp")
        return ChangeHeader(
            header.get("entityName")?.toString() ?: throw IllegalStateException("change event without entityName"),
            header.get("changeType")?.toString() ?: throw IllegalStateException("change event without changeType"),
            ids,
            Instant.ofEpochMilli(commit)
        )
    }
}
