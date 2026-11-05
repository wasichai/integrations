package wasichai.integrations.connector.github

import java.net.URI

// github.com by default. GitHub Enterprise Server: webUrl https://ghe.example.com, apiUrl https://ghe.example.com/api/v3.
// one host per connector bean: the platform stores no per-connection base url (ADR-0019)
data class GitHubConfig(
    val webUrl: String = "https://github.com",
    val apiUrl: String = "https://api.github.com",
    // oauth apps: repo reaches private repositories' issues. github app user tokens ignore scopes
    val defaultScopes: List<String> = listOf("repo")
) {
    init {
        listOf(webUrl, apiUrl).forEach {
            // loopback http only for a stub provider in tests, as the other connectors allow
            require(it.startsWith("https://") || it.startsWith("http://127.0.0.1")) { "github urls must be https" }
        }
    }

    internal val api: String get() = apiUrl.trimEnd('/')
    internal val web: String get() = webUrl.trimEnd('/')

    // a url github handed back (a Link header, a cursor) is followed only when it points at the configured api:
    // the platform adds the connection's token to whatever url a connector asks for
    internal fun isApiUrl(url: String): Boolean {
        val base = URI.create(api)
        val candidate = runCatching { URI.create(url) }.getOrNull() ?: return false
        return candidate.scheme == base.scheme &&
            candidate.rawUserInfo == null &&
            candidate.host.equals(base.host, ignoreCase = true) &&
            candidate.port == base.port &&
            (candidate.rawPath ?: "").startsWith((base.rawPath ?: "").trimEnd('/') + "/")
    }
}
