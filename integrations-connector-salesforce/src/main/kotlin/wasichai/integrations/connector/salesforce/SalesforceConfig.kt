package wasichai.integrations.connector.salesforce

// where users sign in: login.salesforce.com, test.salesforce.com for sandboxes, or a My Domain url
data class SalesforceConfig(
    val loginUrl: String = "https://login.salesforce.com",
    val defaultScopes: List<String> = listOf("api", "refresh_token"),
    // a fixed api version ("66.0"); null asks the instance for its newest one
    val apiVersion: String? = null
) {
    init {
        // loopback http only for a stub provider in tests, as NotionConfig allows
        require(loginUrl.startsWith("https://") || loginUrl.startsWith("http://127.0.0.1")) { "loginUrl must be https" }
        require(apiVersion == null || VERSION.matches(apiVersion)) { "apiVersion must look like 66.0" }
    }

    companion object {
        internal val VERSION = Regex("""^\d{2,3}\.\d$""")
    }
}
