package wasichai.integrations.workflows

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import wasichai.core.platform.ModuleMigration
import wasichai.integrations.ConnectionUsage
import wasichai.integrations.IntegrationEventListener
import wasichai.integrations.JobHandler
import wasichai.integrations.autoconfigure.WasichaiIntegrationsAutoConfiguration
import wasichai.integrations.workflows.autoconfigure.WasichaiIntegrationsWorkflowsAutoConfiguration
import wasichai.test.WasichaiContextRunner
import java.util.Base64

class WasichaiIntegrationsWorkflowsAutoConfigurationTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val runner =
        WasichaiContextRunner
            .core()
            .withConfiguration(
                AutoConfigurations.of(WasichaiIntegrationsAutoConfiguration::class.java, WasichaiIntegrationsWorkflowsAutoConfiguration::class.java)
            ).withPropertyValues("wasichai.integrations.encryption.keys.k1=$key", "wasichai.integrations.encryption.active-key=k1")

    @Test
    fun `migrates after the integrations and sync modules and guards the connections it uses`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val migration = context.getBean("wasichaiIntegrationsWorkflowsMigration", ModuleMigration::class.java)
            assertThat(migration.order).isEqualTo(WasichaiIntegrationsAutoConfiguration.ORDER + 20)
            assertThat(context).hasSingleBean(WorkflowController::class.java)
            assertThat(context).hasSingleBean(WorkflowValidator::class.java)
            assertThat(context.getBeansOfType(ConnectionUsage::class.java)).containsKey("workflowConnectionUsage")
            // the engine runs as a job kind, the event trigger listens to events, the scheduler is a lifecycle
            assertThat(context.getBeansOfType(JobHandler::class.java)).containsKey("workflowEngine")
            assertThat(context.getBean(WorkflowEngine::class.java).kind).isEqualTo("integrations.workflow.step")
            assertThat(context.getBeansOfType(IntegrationEventListener::class.java)).containsKey("workflowEventTrigger")
            assertThat(context).hasSingleBean(WorkflowScheduler::class.java)
            assertThat(context).hasSingleBean(WorkflowWebhookController::class.java)
        }
    }

    @Test
    fun `switched off, no workflow beans and so no routes`() {
        runner.withPropertyValues("wasichai.integrations.workflows.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(WorkflowController::class.java)
            assertThat(context).doesNotHaveBean(WorkflowService::class.java)
            assertThat(context).doesNotHaveBean(WorkflowEngine::class.java)
            assertThat(context).doesNotHaveBean(WorkflowScheduler::class.java)
            assertThat(context).doesNotHaveBean("wasichaiIntegrationsWorkflowsMigration")
        }
    }

    @Test
    fun `without the integrations module, nothing`() {
        runner.withPropertyValues("wasichai.integrations.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(WorkflowController::class.java)
        }
    }
}
