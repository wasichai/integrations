package wasichai.integrations.app

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import wasichai.integrations.connector.github.GitHubConfig
import wasichai.integrations.connector.github.GitHubConnector
import wasichai.integrations.connector.jira.JiraConfig
import wasichai.integrations.connector.jira.JiraConnector
import wasichai.integrations.connector.notion.NotionConnector
import wasichai.integrations.connector.salesforce.SalesforceConfig
import wasichai.integrations.connector.salesforce.SalesforceConnector
import wasichai.integrations.connector.salesforce.pubsub.SalesforcePubSubConfig
import wasichai.integrations.connector.salesforce.pubsub.SalesforcePubSubConnector
import wasichai.integrations.connector.slack.SlackConnector
import wasichai.integrations.sdk.Connector

// connectors are plain libraries: a dependency registers nothing, the app declares the beans (ADR-0017).
// every shipped connector is on by default; an operator switches one off with APP_CONNECTORS_<ID>_ENABLED=false.
// the openapi connector is not here: its mapping is code, written per api (ADR-0021, ADR-0028)
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConnectorsProperties::class)
class ConnectorsConfiguration {
    @Bean
    @ConditionalOnProperty("app.connectors.notion.enabled", havingValue = "true", matchIfMissing = true)
    fun notionConnector(): NotionConnector = NotionConnector()

    // change data capture channels turn the polling connector into the pub/sub one; same id, same connections
    @Bean
    @ConditionalOnProperty("app.connectors.salesforce.enabled", havingValue = "true", matchIfMissing = true)
    fun salesforceConnector(properties: ConnectorsProperties): Connector {
        val salesforce = properties.salesforce
        val config = SalesforceConfig(loginUrl = salesforce.loginUrl, apiVersion = salesforce.apiVersion.orNullIfBlank())
        val channels = salesforce.channels()
        return if (channels.isEmpty()) SalesforceConnector(config) else SalesforcePubSubConnector(SalesforcePubSubConfig(channels = channels), config)
    }

    @Bean
    @ConditionalOnProperty("app.connectors.jira.enabled", havingValue = "true", matchIfMissing = true)
    fun jiraConnector(properties: ConnectorsProperties): JiraConnector =
        JiraConnector(JiraConfig(siteUrl = properties.jira.siteUrl.orNullIfBlank(), cloudId = properties.jira.cloudId.orNullIfBlank()))

    @Bean
    @ConditionalOnProperty("app.connectors.github.enabled", havingValue = "true", matchIfMissing = true)
    fun gitHubConnector(properties: ConnectorsProperties): GitHubConnector =
        GitHubConnector(GitHubConfig(webUrl = properties.github.webUrl, apiUrl = properties.github.apiUrl))

    @Bean
    @ConditionalOnProperty("app.connectors.slack.enabled", havingValue = "true", matchIfMissing = true)
    fun slackConnector(): SlackConnector = SlackConnector()
}
