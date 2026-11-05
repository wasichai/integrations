package wasichai.integrations.connector.jira

// oauth 2.0 (3lo) connections go through the atlassian api gateway (/ex/jira/{cloudId}); api token connections
// (basic: email + token) talk to the site itself, which the platform cannot store per connection, so it comes from here.
data class JiraConfig(
    val authUrl: String = "https://auth.atlassian.com",
    val gatewayUrl: String = "https://api.atlassian.com",
    // offline_access is what makes atlassian answer a refresh token
    val defaultScopes: List<String> = listOf("read:jira-work", "write:jira-work", "read:jira-user", "manage:jira-webhook", "offline_access"),
    // pins one site when a grant reaches several; null takes the only one
    val cloudId: String? = null,
    // the site of api token connections (https://acme.atlassian.net); null refuses them
    val siteUrl: String? = null
) {
    init {
        listOfNotNull(authUrl, gatewayUrl, siteUrl).forEach {
            // loopback http only for a stub provider in tests, as the other connectors allow
            require(it.startsWith("https://") || it.startsWith("http://127.0.0.1")) { "jira urls must be https" }
        }
        require(cloudId == null || CLOUD_ID.matches(cloudId)) { "cloudId must be a uuid" }
    }

    companion object {
        internal val CLOUD_ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
