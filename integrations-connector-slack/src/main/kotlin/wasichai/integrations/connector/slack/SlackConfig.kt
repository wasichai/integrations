package wasichai.integrations.connector.slack

// slack.com by default; a stub provider in tests. one host per connector bean: the platform stores no per-connection
// base url (ADR-0020)
data class SlackConfig(
    val webUrl: String = "https://slack.com",
    val apiUrl: String = "https://slack.com/api",
    // bot scopes: read channels (public and private ones the bot is in), their history, and post as the bot
    val defaultScopes: List<String> = listOf("channels:read", "groups:read", "channels:history", "groups:history", "chat:write")
) {
    init {
        listOf(webUrl, apiUrl).forEach {
            // loopback http only for a stub provider in tests, as the other connectors allow
            require(it.startsWith("https://") || it.startsWith("http://127.0.0.1")) { "slack urls must be https" }
        }
    }

    internal val api: String get() = apiUrl.trimEnd('/')
    internal val web: String get() = webUrl.trimEnd('/')
}
