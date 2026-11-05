package wasichai.integrations.connector.slack

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import wasichai.integrations.connector.slack.SlackFixtures.slackBasics
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.testing.ConnectorContractTest
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.TestConnectorContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlackContractTest : ConnectorContractTest() {
    private val stub = StubProviderServer().apply { slackBasics() }

    override fun connector(): Connector = SlackConnector(SlackConfig(webUrl = stub.baseUrl, apiUrl = stub.baseUrl))

    override fun discoveryContext() = TestConnectorContext()

    @AfterAll
    fun stop() = stub.close()
}
