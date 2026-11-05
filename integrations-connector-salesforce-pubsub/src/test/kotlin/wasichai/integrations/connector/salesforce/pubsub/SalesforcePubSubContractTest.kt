package wasichai.integrations.connector.salesforce.pubsub

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import wasichai.integrations.connector.salesforce.SalesforceConfig
import wasichai.integrations.connector.salesforce.SalesforceConnector
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.salesforceBasics
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.testing.ConnectorContractTest
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.TestConnectorContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SalesforcePubSubContractTest : ConnectorContractTest() {
    private val stub = StubProviderServer().apply { salesforceBasics() }
    private val pubsub = StubPubSub()
    private val connector =
        SalesforcePubSubConnector(
            SalesforceConnector(SalesforceConfig(apiVersion = "66.0")),
            SalesforcePubSubConfig(mapOf("Account" to CdcFixtures.CHANNEL)),
            pubsub.channel()
        )

    override fun connector(): Connector = connector

    override fun discoveryContext() = TestConnectorContext(metadata = mapOf("instance_url" to stub.baseUrl))

    @AfterAll
    fun stop() {
        connector.close()
        pubsub.close()
        stub.close()
    }
}
