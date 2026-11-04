package wasichai.integrations.connector.salesforce

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import wasichai.integrations.connector.salesforce.Fixtures.salesforceBasics
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.testing.ConnectorContractTest
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.TestConnectorContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SalesforceContractTest : ConnectorContractTest() {
    private val stub = StubProviderServer().apply { salesforceBasics() }

    override fun connector(): Connector = SalesforceConnector()

    override fun discoveryContext() = TestConnectorContext(metadata = mapOf("instance_url" to stub.baseUrl))

    @AfterAll
    fun stop() = stub.close()
}
