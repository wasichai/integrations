package wasichai.integrations.connector.salesforce.pubsub

import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.generic.GenericRecordBuilder
import org.apache.avro.io.EncoderFactory
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.testing.StubProviderServer
import java.io.ByteArrayOutputStream
import java.time.Instant

// change events as salesforce documents them (change data capture developer guide, "change event message
// structure"): a ChangeEventHeader record, then the object's fields, avro binary. trimmed to Account's Name
internal object CdcFixtures {
    const val API = "/services/data/v66.0"
    const val CHANNEL = "/data/AccountChangeEvent"
    const val SHARED = "/data/ChangeEvents"
    const val SCHEMA_ID = "WJrXoTcSjFxAq3_D1ns-2A"
    const val ORG = "00D000000000001AAA"
    const val ID1 = "001000000000001AAA"
    const val ID2 = "001000000000002AAA"
    const val ID3 = "001000000000003AAA"
    const val CONTACT = "003000000000001AAA"

    val schemaJson =
        """
        {"type":"record","name":"AccountChangeEvent","namespace":"com.sforce.eventbus","fields":[
          {"name":"ChangeEventHeader","type":{"type":"record","name":"ChangeEventHeader","fields":[
            {"name":"entityName","type":"string"},
            {"name":"recordIds","type":{"type":"array","items":"string"}},
            {"name":"changeType","type":{"type":"enum","name":"ChangeType","symbols":
              ["CREATE","UPDATE","DELETE","UNDELETE","GAP_CREATE","GAP_UPDATE","GAP_DELETE","GAP_UNDELETE","GAP_OVERFLOW"]}},
            {"name":"changeOrigin","type":"string"},
            {"name":"transactionKey","type":"string"},
            {"name":"sequenceNumber","type":"int"},
            {"name":"commitTimestamp","type":"long"},
            {"name":"commitNumber","type":"long"},
            {"name":"commitUser","type":"string"},
            {"name":"nulledFields","type":{"type":"array","items":"string"}},
            {"name":"diffFields","type":{"type":"array","items":"string"}},
            {"name":"changedFields","type":{"type":"array","items":"string"}}]}},
          {"name":"Name","type":["null","string"],"default":null}]}
        """.trimIndent()

    private val schema = Schema.Parser().parse(schemaJson)

    fun event(
        changeType: String,
        ids: List<String>,
        at: Instant,
        entity: String = "Account",
        name: String? = "Acme"
    ): ByteArray {
        val headerSchema = schema.getField("ChangeEventHeader").schema()
        val header =
            GenericRecordBuilder(headerSchema)
                .set("entityName", entity)
                .set("recordIds", ids)
                .set("changeType", GenericData.EnumSymbol(headerSchema.getField("changeType").schema(), changeType))
                .set("changeOrigin", "com/salesforce/api/soap/66.0;client=SfdcInternalAPI/")
                .set("transactionKey", "0002343d-9d90-e395-ed20-cf416ba652ad")
                .set("sequenceNumber", 1)
                .set("commitTimestamp", at.toEpochMilli())
                .set("commitNumber", 10_769_312_895L)
                .set("commitUser", "005000000000001AAA")
                .set("nulledFields", emptyList<String>())
                .set("diffFields", emptyList<String>())
                // bitmap form the pub/sub api sends; the connector never reads it
                .set("changedFields", listOf("0x01"))
                .build()
        val record: GenericRecord =
            GenericRecordBuilder(schema)
                .set("ChangeEventHeader", header)
                .set("Name", name)
                .build()
        val out = ByteArrayOutputStream()
        val encoder = EncoderFactory.get().binaryEncoder(out, null)
        GenericDatumWriter<GenericRecord>(schema).write(record, encoder)
        encoder.flush()
        return out.toByteArray()
    }

    val describeAccount =
        """
        {"name":"Account","label":"Account","fields":[
          {"name":"Id","label":"Account ID","type":"id","nillable":false,"createable":false,"updateable":false,"defaultedOnCreate":true},
          {"name":"Name","label":"Account Name","type":"string","nillable":false,"createable":true,"updateable":true},
          {"name":"SystemModstamp","label":"System Modstamp","type":"datetime","nillable":false,"createable":false,"updateable":false}
        ]}
        """.trimIndent()

    val describeGlobal =
        """
        {"encoding":"UTF-8","maxBatchSize":200,"sobjects":[
          {"name":"Account","label":"Account","queryable":true,"retrieveable":true},
          {"name":"Contact","label":"Contact","queryable":true,"retrieveable":true}]}
        """.trimIndent()

    fun account(
        id: String,
        name: String,
        stamp: String = "2026-11-05T09:00:00.000+0000"
    ) = """{"attributes":{"type":"Account"},"Id":"$id","Name":"$name","SystemModstamp":"$stamp"}"""

    fun query(
        records: List<String>,
        next: String? = null
    ) = """{"totalSize":${records.size},"done":${next == null},${next?.let { "\"nextRecordsUrl\":\"$it\"," } ?: ""}"records":[${records.joinToString(",")}]}"""

    fun StubProviderServer.salesforceBasics() {
        onJson(HttpMethod.GET, "$API/sobjects/", describeGlobal)
        onJson(HttpMethod.GET, "$API/sobjects/Account/describe/", describeAccount)
    }
}
