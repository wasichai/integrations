package wasichai.integrations.connector.notion

// the api version is pinned: notion rejects unversioned requests and breaks shapes between versions.
// 2025-09-03 is the first version with data sources (one database may hold several).
data class NotionConfig(
    val apiBaseUrl: String = "https://api.notion.com",
    val notionVersion: String = "2025-09-03"
) {
    init {
        require(apiBaseUrl.startsWith("https://") || apiBaseUrl.startsWith("http://127.0.0.1")) { "apiBaseUrl must be https" }
    }

    val api: String get() = apiBaseUrl.trimEnd('/') + "/v1"
}
