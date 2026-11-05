package wasichai.integrations.app

import org.springframework.boot.context.properties.ConfigurationProperties

// app.connectors.*: which shipped connectors this deployment registers, and the settings each needs.
// env: APP_CONNECTORS_<ID>_ENABLED, APP_CONNECTORS_JIRA_SITEURL... a blank value means unset (compose passes "")
@ConfigurationProperties("app.connectors")
data class ConnectorsProperties(
    val notion: Notion = Notion(),
    val salesforce: Salesforce = Salesforce(),
    val jira: Jira = Jira(),
    val github: GitHub = GitHub(),
    val slack: Slack = Slack()
) {
    data class Notion(
        val enabled: Boolean = true
    )

    data class Salesforce(
        val enabled: Boolean = true,
        val loginUrl: String = "https://login.salesforce.com",
        val apiVersion: String? = null,
        // Object=/data/Channel pairs, comma separated: those objects read changes through change data capture
        // (ADR-0024). one string, not a map: env var names would lowercase the object names
        val cdcChannels: String? = null
    ) {
        fun channels(): Map<String, String> =
            cdcChannels
                .orEmpty()
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .associate { pair ->
                    val parts = pair.split('=', limit = 2).map { it.trim() }
                    require(parts.size == 2 && parts.all { it.isNotEmpty() }) {
                        "app.connectors.salesforce.cdc-channels: '$pair' is not Object=/data/Channel"
                    }
                    parts[0] to parts[1]
                }
    }

    data class Jira(
        val enabled: Boolean = true,
        // the site of api token connections (https://acme.atlassian.net); unset refuses them
        val siteUrl: String? = null,
        val cloudId: String? = null
    )

    data class GitHub(
        val enabled: Boolean = true,
        // GitHub Enterprise Server: https://ghe.example.com and https://ghe.example.com/api/v3
        val webUrl: String = "https://github.com",
        val apiUrl: String = "https://api.github.com"
    )

    data class Slack(
        val enabled: Boolean = true
    )
}

internal fun String?.orNullIfBlank(): String? = this?.takeIf { it.isNotBlank() }
