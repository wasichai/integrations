package wasichai.integrations.it

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import wasichai.integrations.connector.notion.NotionConfig
import wasichai.integrations.connector.notion.NotionConnector
import wasichai.integrations.connector.salesforce.SalesforceConfig
import wasichai.integrations.connector.salesforce.SalesforceConnector

// the app the suites boot: wasichai plus the integration modules, through auto-configuration only, and the two
// real connectors pointed at stateful stub providers (one per jvm: the beans and the tests share them)
@SpringBootConfiguration
@EnableAutoConfiguration
class IntegrationsTestApplication {
    @Bean
    fun notionConnector(): NotionConnector = NotionConnector(NotionConfig(apiBaseUrl = notion.baseUrl))

    @Bean
    fun salesforceConnector(): SalesforceConnector = SalesforceConnector(SalesforceConfig(loginUrl = salesforce.baseUrl))

    companion object {
        val notion: NotionStub by lazy { NotionStub() }
        val salesforce: SalesforceStub by lazy { SalesforceStub() }
    }
}
