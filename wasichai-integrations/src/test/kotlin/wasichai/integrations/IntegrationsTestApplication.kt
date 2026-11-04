package wasichai.integrations

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean
import wasichai.integrations.sdk.Connector

// core plus this module through auto-configuration only, and two connectors with no provider behind them
@SpringBootConfiguration
@EnableAutoConfiguration
class IntegrationsTestApplication {
    @Bean
    fun testOauthConnector(): Connector = TestConnectors.oauth()

    @Bean
    fun testKeyConnector(): Connector = TestConnectors.apiKey()

    @Bean
    fun stubOauthConnector(): Connector = StubProvider.oauth()

    @Bean
    fun stubKeyConnector(): Connector = StubProvider.apiKey()

    @Bean
    fun recordingJobs(): RecordingJobs = RecordingJobs()

    @Bean
    fun stubHooks(): Connector = StubHooks()

    @Bean
    fun recordingListener(): RecordingListener = RecordingListener()

    @Bean
    fun heldConnections(): HeldConnections = HeldConnections()
}

// a module holding on to connections, as the sync module does
class HeldConnections : ConnectionUsage {
    private val held = java.util.concurrent.ConcurrentHashMap<java.util.UUID, String>()

    fun hold(
        connection: java.util.UUID,
        by: String
    ) = held.put(connection, by)

    fun release(connection: java.util.UUID) = held.remove(connection)

    override suspend fun usedBy(
        organizationId: java.util.UUID,
        connectionId: java.util.UUID
    ): List<String> = listOfNotNull(held[connectionId])
}
