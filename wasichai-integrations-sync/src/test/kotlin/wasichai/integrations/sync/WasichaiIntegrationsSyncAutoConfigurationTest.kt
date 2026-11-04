package wasichai.integrations.sync

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import wasichai.core.platform.ModuleMigration
import wasichai.integrations.EventJobs
import wasichai.integrations.JobRunner
import wasichai.integrations.autoconfigure.WasichaiIntegrationsAutoConfiguration
import wasichai.integrations.sync.autoconfigure.WasichaiIntegrationsSyncAutoConfiguration
import wasichai.test.WasichaiContextRunner
import java.util.Base64

class WasichaiIntegrationsSyncAutoConfigurationTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val runner =
        WasichaiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(WasichaiIntegrationsAutoConfiguration::class.java, WasichaiIntegrationsSyncAutoConfiguration::class.java))
            .withPropertyValues("wasichai.integrations.encryption.keys.k1=$key", "wasichai.integrations.encryption.active-key=k1")

    @Test
    fun `migrates after the integrations module and joins its jobs and events`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val migration = context.getBean("wasichaiIntegrationsSyncMigration", ModuleMigration::class.java)
            assertThat(migration.order).isGreaterThan(WasichaiIntegrationsAutoConfiguration.ORDER)
            assertThat(context).hasSingleBean(SyncEngine::class.java)
            assertThat(context).hasSingleBean(JobRunner::class.java)
            assertThat(context).hasSingleBean(EventJobs::class.java)
        }
    }

    @Test
    fun `switched off, no sync beans`() {
        runner.withPropertyValues("wasichai.integrations.sync.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(SyncEngine::class.java)
        }
    }
}
