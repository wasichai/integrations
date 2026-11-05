package wasichai.integrations

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import wasichai.core.platform.ModuleMigration
import wasichai.integrations.autoconfigure.IntegrationsProperties
import wasichai.integrations.autoconfigure.WasichaiIntegrationsAutoConfiguration
import wasichai.test.WasichaiContextRunner
import java.util.Base64

class WasichaiIntegrationsAutoConfigurationTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val runner =
        WasichaiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(WasichaiIntegrationsAutoConfiguration::class.java))

    private fun keyed() = runner.withPropertyValues("wasichai.integrations.encryption.keys.k1=$key", "wasichai.integrations.encryption.active-key=k1")

    @Test
    fun `brings its migration after every wasichai module`() {
        keyed().run { context ->
            assertThat(context).hasNotFailed()
            val migration = context.getBean("wasichaiIntegrationsMigration", ModuleMigration::class.java)
            assertThat(migration.location).isEqualTo("classpath:db/wasichai/integrations")
            assertThat(migration.order).isGreaterThan(ModuleMigration.MODULE_ORDER)
            assertThat(context).hasSingleBean(ConnectorRegistry::class.java)
            assertThat(context).hasSingleBean(CatalogController::class.java)
        }
    }

    @Test
    fun `connector beans land in the registry`() {
        keyed().withBean("testConnector", wasichai.integrations.sdk.Connector::class.java, { TestConnectors.apiKey() }).run { context ->
            assertThat(context.getBean(ConnectorRegistry::class.java).all().map { it.manifest.id }).containsExactly("test-key")
        }
    }

    @Test
    fun `rate limits are shared by default, per replica when switched off`() {
        keyed().run { context ->
            assertThat(context).hasSingleBean(RateBuckets::class.java)
            assertThat(
                context
                    .getBean(IntegrationsProperties::class.java)
                    .http.rateLimit.shared
            ).isTrue()
        }
        keyed().withPropertyValues("wasichai.integrations.http.rate-limit.shared=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(
                context
                    .getBean(IntegrationsProperties::class.java)
                    .http.rateLimit.shared
            ).isFalse()
            assertThat(context).hasSingleBean(RateLimits::class.java)
        }
    }

    @Test
    fun `refuses to start without an encryption key`() {
        runner.run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `switched off, nothing of the module exists`() {
        runner.withPropertyValues("wasichai.integrations.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(ConnectorRegistry::class.java)
            assertThat(context).doesNotHaveBean(CatalogController::class.java)
        }
    }
}
