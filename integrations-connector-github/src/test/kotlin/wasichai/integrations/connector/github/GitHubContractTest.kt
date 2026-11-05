package wasichai.integrations.connector.github

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import wasichai.integrations.connector.github.GitHubFixtures.githubBasics
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.testing.ConnectorContractTest
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.TestConnectorContext

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitHubContractTest : ConnectorContractTest() {
    private val stub = StubProviderServer().apply { githubBasics() }

    override fun connector(): Connector = GitHubConnector(GitHubConfig(webUrl = stub.baseUrl, apiUrl = stub.baseUrl))

    override fun discoveryContext() = TestConnectorContext()

    @AfterAll
    fun stop() = stub.close()
}
