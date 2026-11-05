package wasichai.integrations.connector.openapi

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.testing.ConnectorContractTest
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.TestConnectorContext

// the full petstore mapping: discovery, read, change feed and write
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiContractTest : ConnectorContractTest() {
    private val stub = StubProviderServer()

    override fun connector(): Connector = OpenApiConnector.of(OpenApiFixtures.petstore("${stub.baseUrl}/v1"))

    override fun discoveryContext() = TestConnectorContext()

    @AfterAll
    fun stop() = stub.close()
}

// the reading resources alone: the connector must not implement what the mapping does not provide
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenApiReadOnlyContractTest : ConnectorContractTest() {
    private val stub = StubProviderServer()

    override fun connector(): Connector = OpenApiConnector.of(OpenApiFixtures.readOnly("${stub.baseUrl}/v1"))

    override fun discoveryContext() = TestConnectorContext()

    @AfterAll
    fun stop() = stub.close()
}
