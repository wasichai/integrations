package wasichai.integrations.connector.notion

// payload shapes from the notion api reference (2025-09-03), trimmed to what the connector reads
internal object NotionFixtures {
    const val SOURCE = "1f6a2b3c-4d5e-4f60-8a7b-9c0d1e2f3a4b"
    const val PAGE = "2a7b3c4d-5e6f-4a01-9b8c-0d1e2f3a4b5c"

    val dataSource =
        """
        {"object":"data_source","id":"$SOURCE","title":[{"plain_text":"Accounts"}],
         "properties":{
           "Name":{"id":"title","name":"Name","type":"title","title":{}},
           "Industry":{"id":"a1","name":"Industry","type":"select","select":{"options":[{"name":"Energy"},{"name":"Retail"}]}},
           "Employees":{"id":"a2","name":"Employees","type":"number","number":{"format":"number"}},
           "Active":{"id":"a3","name":"Active","type":"checkbox","checkbox":{}},
           "Since":{"id":"a4","name":"Since","type":"date","date":{}},
           "Tags":{"id":"a5","name":"Tags","type":"multi_select","multi_select":{"options":[{"name":"a"},{"name":"b"}]}},
           "Edited":{"id":"a6","name":"Edited","type":"last_edited_time","last_edited_time":{}},
           "Notes":{"id":"a7","name":"Notes","type":"rich_text","rich_text":{}}
         }}
        """.trimIndent()

    fun page(
        id: String = PAGE,
        name: String = "Acme",
        edited: String = "2026-11-04T10:15:00.000Z",
        trashed: Boolean = false
    ) = """
        {"object":"page","id":"$id","last_edited_time":"$edited","in_trash":$trashed,"archived":false,
         "parent":{"type":"data_source_id","data_source_id":"$SOURCE"},
         "properties":{
           "Name":{"id":"title","type":"title","title":[{"type":"text","plain_text":"$name"}]},
           "Industry":{"id":"a1","type":"select","select":{"name":"Energy"}},
           "Employees":{"id":"a2","type":"number","number":120},
           "Active":{"id":"a3","type":"checkbox","checkbox":true},
           "Since":{"id":"a4","type":"date","date":{"start":"2020-01-15","end":null}},
           "Tags":{"id":"a5","type":"multi_select","multi_select":[{"name":"a"},{"name":"b"}]},
           "Edited":{"id":"a6","type":"last_edited_time","last_edited_time":"$edited"},
           "Notes":{"id":"a7","type":"rich_text","rich_text":[]}
         }}
        """.trimIndent()

    fun query(
        pages: List<String>,
        next: String? = null
    ) = """{"object":"list","results":[${pages.joinToString(",")}],"next_cursor":${next?.let { "\"$it\"" } ?: "null"},"has_more":${next != null}}"""
}
