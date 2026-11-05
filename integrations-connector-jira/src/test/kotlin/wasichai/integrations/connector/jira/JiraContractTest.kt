package wasichai.integrations.connector.jira

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import wasichai.integrations.connector.jira.JiraFixtures.jiraBasics
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.testing.ConnectorContractTest
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.TestConnectorContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JiraContractTest : ConnectorContractTest() {
    private val stub = StubProviderServer().apply { jiraBasics() }

    override fun connector(): Connector = JiraConnector(JiraConfig(gatewayUrl = stub.baseUrl))

    override fun discoveryContext() = TestConnectorContext(metadata = mapOf(JiraConnector.ROUTE to JiraConnector.GATEWAY))

    @AfterAll
    fun stop() = stub.close()
}
