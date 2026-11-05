package wasichai.integrations.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import wasichai.integrations.connector.salesforce.SalesforceConnector
import wasichai.integrations.connector.salesforce.pubsub.SalesforcePubSubConnector
import wasichai.integrations.sdk.Connector

class ConnectorsConfigurationTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(ConnectorsConfiguration::class.java)

    private fun ids(context: org.springframework.context.ApplicationContext): List<String> =
        context
            .getBeansOfType(Connector::class.java)
            .values
            .map { it.manifest.id }
            .sorted()

    @Test
    fun `every shipped connector is registered by default, openapi is not`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(ids(context)).containsExactly("github", "jira", "notion", "salesforce", "slack")
            assertThat(context.getBean("salesforceConnector")).isExactlyInstanceOf(SalesforceConnector::class.java)
        }
    }

    @Test
    fun `an operator switches a connector off`() {
        runner
            .withPropertyValues("app.connectors.jira.enabled=false", "app.connectors.slack.enabled=false")
            .run { context -> assertThat(ids(context)).containsExactly("github", "notion", "salesforce") }
    }

    @Test
    fun `blank settings are unset, as compose passes empty variables`() {
        runner
            .withPropertyValues("app.connectors.jira.site-url=", "app.connectors.jira.cloud-id=", "app.connectors.salesforce.api-version=")
            .run { context -> assertThat(ids(context)).contains("jira", "salesforce") }
    }

    @Test
    fun `cdc channels switch salesforce to change data capture under the same id`() {
        runner
            .withPropertyValues("app.connectors.salesforce.cdc-channels=Account=/data/AccountChangeEvent, Contact=/data/ContactChangeEvent")
            .run { context ->
                val salesforce = context.getBean("salesforceConnector")
                assertThat(salesforce).isInstanceOf(SalesforcePubSubConnector::class.java)
                assertThat((salesforce as Connector).manifest.id).isEqualTo("salesforce")
                assertThat(salesforce.manifest.description).contains("Account, Contact")
            }
    }

    @Test
    fun `a malformed cdc channel or an invalid url stops the app`() {
        runner
            .withPropertyValues("app.connectors.salesforce.cdc-channels=Account")
            .run { context -> assertThat(context).hasFailed() }
        runner
            .withPropertyValues("app.connectors.github.api-url=http://ghe.example.com/api/v3")
            .run { context -> assertThat(context).hasFailed() }
    }
}
