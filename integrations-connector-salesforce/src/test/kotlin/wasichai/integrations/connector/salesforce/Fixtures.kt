package wasichai.integrations.connector.salesforce

import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.testing.StubProviderServer

// payload shapes from the salesforce rest api reference, trimmed to what the connector reads
internal object Fixtures {
    const val API = "/services/data/v66.0"

    val versions =
        """
        [{"label":"Spring '26","url":"/services/data/v66.0","version":"66.0"},
         {"label":"Winter '26","url":"/services/data/v65.0","version":"65.0"}]
        """.trimIndent()

    val describeGlobal =
        """
        {"encoding":"UTF-8","maxBatchSize":200,"sobjects":[
          {"name":"Account","label":"Account","queryable":true,"retrieveable":true},
          {"name":"Contact","label":"Contact","queryable":true,"retrieveable":true},
          {"name":"AccountHistory","label":"Account History","queryable":true,"retrieveable":false}]}
        """.trimIndent()

    val describeAccount =
        """
        {"name":"Account","label":"Account","fields":[
          {"name":"Id","label":"Account ID","type":"id","nillable":false,"createable":false,"updateable":false,"defaultedOnCreate":true},
          {"name":"Name","label":"Account Name","type":"string","nillable":false,"createable":true,"updateable":true,
           "defaultedOnCreate":false},
          {"name":"Industry","label":"Industry","type":"picklist","nillable":true,"createable":true,"updateable":true,
           "picklistValues":[{"value":"Banking","active":true},{"value":"Retired","active":false}]},
          {"name":"AnnualRevenue","label":"Annual Revenue","type":"currency","nillable":true,"createable":true,"updateable":true},
          {"name":"Active__c","label":"Active","type":"boolean","nillable":false,"createable":true,"updateable":true,
           "defaultedOnCreate":true},
          {"name":"Ext_Id__c","label":"External Id","type":"string","nillable":true,"createable":true,"updateable":true,
           "externalId":true},
          {"name":"ParentId","label":"Parent Account","type":"reference","nillable":true,"createable":true,"updateable":true,
           "referenceTo":["Account"]},
          {"name":"BillingAddress","label":"Billing Address","type":"address","nillable":true,"createable":false,"updateable":false},
          {"name":"SystemModstamp","label":"System Modstamp","type":"datetime","nillable":false,"createable":false,"updateable":false}
        ]}
        """.trimIndent()

    fun account(
        id: String,
        name: String,
        stamp: String = "2026-11-04T12:00:00.000+0000"
    ) = """{"attributes":{"type":"Account","url":"$API/sobjects/Account/$id"},"Id":"$id","Name":"$name","Industry":"Banking",
        |"AnnualRevenue":1500.5,"Active__c":true,"Ext_Id__c":null,"ParentId":null,"SystemModstamp":"$stamp"}
        """.trimMargin()

    fun query(
        records: List<String>,
        next: String? = null
    ) = """{"totalSize":${records.size},"done":${next == null},${next?.let { "\"nextRecordsUrl\":\"$it\"," } ?: ""}
        |"records":[${records.joinToString(",")}]}
        """.trimMargin()

    fun StubProviderServer.salesforceBasics() {
        onJson(HttpMethod.GET, "/services/data/", versions)
        onJson(HttpMethod.GET, "$API/sobjects/", describeGlobal)
        onJson(HttpMethod.GET, "$API/sobjects/Account/describe/", describeAccount)
    }

    const val ID1 = "001000000000001AAA"
    const val ID2 = "001000000000002AAA"
}
