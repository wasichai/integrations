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
}
