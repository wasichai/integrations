package wasichai.integrations.workflows.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectionUsage
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.IntegrationsMeters
import wasichai.integrations.JobRepository
import wasichai.integrations.RetentionTask
import wasichai.integrations.SqlRetentionTask
import wasichai.integrations.Transactions
import wasichai.integrations.UsageMeter
import wasichai.integrations.autoconfigure.IntegrationsProperties
import wasichai.integrations.autoconfigure.WasichaiIntegrationsAutoConfiguration
import wasichai.integrations.workflows.ExecutionRepository
import wasichai.integrations.workflows.WorkflowConnectionData
import wasichai.integrations.workflows.WorkflowController
import wasichai.integrations.workflows.WorkflowEngine
import wasichai.integrations.workflows.WorkflowEventTrigger
import wasichai.integrations.workflows.WorkflowRepository
import wasichai.integrations.workflows.WorkflowRunService
import wasichai.integrations.workflows.WorkflowScheduler
import wasichai.integrations.workflows.WorkflowService
import wasichai.integrations.workflows.WorkflowValidator
import wasichai.integrations.workflows.WorkflowWebhookController
import java.time.Clock
import java.time.Duration

// integration workflows (ADR-0022, ADR-0025): definitions, immutable versions, validation and execution on the
// integrations job queue. needs the integrations module and its tables; it does not need the sync module
@AutoConfiguration(after = [WasichaiIntegrationsAutoConfiguration::class])
@ConditionalOnBean(ConnectorRegistry::class)
@ConditionalOnProperty(prefix = "wasichai.integrations.workflows", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WorkflowsProperties::class)
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
    @ConditionalOnMissingBean
    fun executionRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): ExecutionRepository = ExecutionRepository(db, json, schemas)

    // a JobHandler bean: the integrations job runner picks it up with the others
    @Bean
    @ConditionalOnMissingBean
    fun workflowEngine(
        workflows: WorkflowRepository,
        executions: ExecutionRepository,
        registry: ConnectorRegistry,
        connections: ConnectionRepository,
        contexts: ConnectorContexts,
        jobs: JobRepository,
        integrationsTransactions: Transactions,
        meters: IntegrationsMeters,
        properties: WorkflowsProperties,
        clock: Clock,
        usage: UsageMeter
    ): WorkflowEngine = WorkflowEngine(workflows, executions, registry, connections, contexts, jobs, integrationsTransactions, meters, properties, clock, usage)

    // GDPR export and erasure of a connection (ADR-0029): the workflows naming it and their runs
    @Bean
    @ConditionalOnMissingBean
    fun workflowConnectionData(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): WorkflowConnectionData = WorkflowConnectionData(db, json, schemas)

    // retention (ADR-0029): finished runs only, with their steps (cascade); a waiting approval is never purged
    @Bean
    fun workflowExecutionsRetention(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): RetentionTask {
        val m = schemas.metadata
        return SqlRetentionTask(
            "workflows.executions",
            "integration_workflow_executions",
            Duration.ofDays(90),
            db,
            "DELETE FROM $m.integration_workflow_executions WHERE id IN (SELECT id FROM $m.integration_workflow_executions " +
                "WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED') AND finished_at < :cutoff ORDER BY finished_at LIMIT :limit FOR UPDATE SKIP LOCKED)"
        )
    }

    // an IntegrationEventListener bean: EventJobs hands it every event
    @Bean
    @ConditionalOnMissingBean
    fun workflowEventTrigger(
        executions: ExecutionRepository,
        engine: WorkflowEngine
    ): WorkflowEventTrigger = WorkflowEventTrigger(executions, engine)

    @Bean
    @ConditionalOnMissingBean
    fun workflowScheduler(
        executions: ExecutionRepository,
        engine: WorkflowEngine,
        clusterLock: ClusterLock,
        integrationsTransactions: Transactions,
        properties: WorkflowsProperties,
        clock: Clock
    ): WorkflowScheduler = WorkflowScheduler(executions, engine, clusterLock, integrationsTransactions, properties, clock)

    @Bean
    @ConditionalOnMissingBean
    fun workflowRunService(
        access: IntegrationsAccess,
        repository: WorkflowRepository,
        executions: ExecutionRepository,
        engine: WorkflowEngine,
        json: JsonMapper,
        integrationsProperties: IntegrationsProperties,
        clock: Clock
    ): WorkflowRunService = WorkflowRunService(access, repository, executions, engine, json, integrationsProperties, clock)

    @Bean
    fun workflowController(
        workflows: WorkflowService,
        runs: WorkflowRunService
    ): WorkflowController = WorkflowController(workflows, runs)

    @Bean
    fun workflowWebhookController(runs: WorkflowRunService): WorkflowWebhookController = WorkflowWebhookController(runs)
}
