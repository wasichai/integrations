package wasichai.integrations.workflows.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectionUsage
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.Transactions
import wasichai.integrations.autoconfigure.WasichaiIntegrationsAutoConfiguration
import wasichai.integrations.workflows.WorkflowController
import wasichai.integrations.workflows.WorkflowRepository
import wasichai.integrations.workflows.WorkflowService
import wasichai.integrations.workflows.WorkflowValidator
import java.time.Clock

// integration workflows (ADR-0022): definitions, immutable versions and validation. needs the integrations module
// and its tables; it does not need the sync module
@AutoConfiguration(after = [WasichaiIntegrationsAutoConfiguration::class])
@ConditionalOnBean(ConnectorRegistry::class)
@ConditionalOnProperty(prefix = "wasichai.integrations.workflows", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WasichaiIntegrationsWorkflowsAutoConfiguration {
    // never @ConditionalOnMissingBean: another module's ModuleMigration would make it back off
    @Bean
    fun wasichaiIntegrationsWorkflowsMigration(): ModuleMigration =
        ModuleMigration("integrations_workflows", "classpath:db/wasichai/integrations-workflows", WasichaiIntegrationsAutoConfiguration.ORDER + 20)

    @Bean
    @ConditionalOnMissingBean
    fun workflowRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): WorkflowRepository = WorkflowRepository(db, json, schemas)

    // a connection a workflow stands on is not deleted under it
    @Bean
    fun workflowConnectionUsage(repository: WorkflowRepository): ConnectionUsage =
        ConnectionUsage { org, connection -> repository.namesOnConnection(org, connection).map { "workflow '$it'" } }

    @Bean
    @ConditionalOnMissingBean
    fun workflowValidator(
        registry: ConnectorRegistry,
        connections: ConnectionRepository,
        contexts: ConnectorContexts
    ): WorkflowValidator = WorkflowValidator(registry, connections, contexts)

    @Bean
    @ConditionalOnMissingBean
    fun workflowService(
        access: IntegrationsAccess,
        repository: WorkflowRepository,
        validator: WorkflowValidator,
        integrationsTransactions: Transactions,
        json: JsonMapper,
        clock: Clock
    ): WorkflowService = WorkflowService(access, repository, validator, integrationsTransactions, json, clock)

    @Bean
    fun workflowController(workflows: WorkflowService): WorkflowController = WorkflowController(workflows)
}
