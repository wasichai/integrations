package wasichai.integrations.sync.autoconfigure

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
import wasichai.integrations.autoconfigure.WasichaiIntegrationsAutoConfiguration
import wasichai.integrations.sync.DefinitionValidator
import wasichai.integrations.sync.SyncConnectionData
import wasichai.integrations.sync.SyncDefinitionController
import wasichai.integrations.sync.SyncDefinitionRepository
import wasichai.integrations.sync.SyncDefinitionService
import wasichai.integrations.sync.SyncEngine
import wasichai.integrations.sync.SyncOperationsController
import wasichai.integrations.sync.SyncOperationsService
import wasichai.integrations.sync.SyncRunRepository
import wasichai.integrations.sync.SyncScheduler
import wasichai.integrations.sync.SyncStateRepository
import java.time.Clock
import java.time.Duration

// synchronization between two connections (ADR-0009). needs the integrations module and its tables.
@AutoConfiguration(after = [WasichaiIntegrationsAutoConfiguration::class])
@ConditionalOnBean(ConnectorRegistry::class)
@ConditionalOnProperty(prefix = "wasichai.integrations.sync", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SyncProperties::class)
class WasichaiIntegrationsSyncAutoConfiguration {
    @Bean
    fun wasichaiIntegrationsSyncMigration(): ModuleMigration =
        ModuleMigration("integrations_sync", "classpath:db/wasichai/integrations-sync", WasichaiIntegrationsAutoConfiguration.ORDER + 10)

    @Bean
    @ConditionalOnMissingBean
    fun syncDefinitionRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): SyncDefinitionRepository = SyncDefinitionRepository(db, json, schemas)

    // a connection a definition stands on is not deleted under it (issue #51)
    @Bean
    fun syncConnectionUsage(repository: SyncDefinitionRepository): ConnectionUsage =
        ConnectionUsage { org, connection -> repository.namesOnConnection(org, connection).map { "sync definition '$it'" } }

    @Bean
    @ConditionalOnMissingBean
    fun definitionValidator(
        registry: ConnectorRegistry,
        connections: ConnectionRepository,
        contexts: ConnectorContexts
    ): DefinitionValidator = DefinitionValidator(registry, connections, contexts)

    @Bean
    @ConditionalOnMissingBean
    fun syncDefinitionService(
        access: IntegrationsAccess,
        repository: SyncDefinitionRepository,
        validator: DefinitionValidator,
        clock: Clock
    ): SyncDefinitionService = SyncDefinitionService(access, repository, validator, clock)

    @Bean
    fun syncDefinitionController(definitions: SyncDefinitionService): SyncDefinitionController = SyncDefinitionController(definitions)

    @Bean
    @ConditionalOnMissingBean
    fun syncStateRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): SyncStateRepository = SyncStateRepository(db, json, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun syncRunRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): SyncRunRepository = SyncRunRepository(db, json, schemas)

    // a JobHandler and an IntegrationEventListener: the integrations module picks it up as both
    @Bean
    @ConditionalOnMissingBean
    fun syncEngine(
        definitions: SyncDefinitionRepository,
        state: SyncStateRepository,
        runs: SyncRunRepository,
        registry: ConnectorRegistry,
        connections: ConnectionRepository,
        contexts: ConnectorContexts,
        jobs: JobRepository,
        integrationsTransactions: Transactions,
        meters: IntegrationsMeters,
        properties: SyncProperties,
        clock: Clock,
        usage: UsageMeter
    ): SyncEngine = SyncEngine(definitions, state, runs, registry, connections, contexts, jobs, integrationsTransactions, meters, properties, clock, usage)

    // GDPR export and erasure of a connection (ADR-0029): the definitions on it and their state
    @Bean
    @ConditionalOnMissingBean
    fun syncConnectionData(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): SyncConnectionData = SyncConnectionData(db, json, schemas)

    // retention (ADR-0029). the last run of each definition and trigger, and its last successful one, are kept:
    // the scheduler and the status read them
    @Bean
    fun syncRunsRetention(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): RetentionTask {
        val m = schemas.metadata
        return SqlRetentionTask(
            "sync.runs",
            "integration_sync_runs",
            Duration.ofDays(90),
            db,
            "DELETE FROM $m.integration_sync_runs WHERE id IN (SELECT r.id FROM $m.integration_sync_runs r " +
                "WHERE r.status IN ('SUCCEEDED', 'PARTIAL', 'FAILED') AND r.finished_at < :cutoff " +
                "AND EXISTS (SELECT 1 FROM $m.integration_sync_runs n WHERE n.definition_id = r.definition_id AND n.trigger = r.trigger " +
                "AND n.queued_at > r.queued_at) " +
                "AND (r.status <> 'SUCCEEDED' OR EXISTS (SELECT 1 FROM $m.integration_sync_runs s WHERE s.definition_id = r.definition_id " +
                "AND s.status = 'SUCCEEDED' AND s.queued_at > r.queued_at)) " +
                "ORDER BY r.finished_at LIMIT :limit FOR UPDATE SKIP LOCKED)"
        )
    }

    // only resolved conflicts: an open one waits for a person, whatever its age
    @Bean
    fun syncConflictsRetention(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): RetentionTask {
        val m = schemas.metadata
        return SqlRetentionTask(
            "sync.conflicts",
            "integration_sync_conflicts",
            Duration.ofDays(180),
            db,
            "DELETE FROM $m.integration_sync_conflicts WHERE id IN (SELECT id FROM $m.integration_sync_conflicts " +
                "WHERE status = 'RESOLVED' AND resolved_at < :cutoff ORDER BY resolved_at LIMIT :limit FOR UPDATE SKIP LOCKED)"
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun syncOperationsService(
        access: IntegrationsAccess,
        definitions: SyncDefinitionRepository,
        state: SyncStateRepository,
        runs: SyncRunRepository,
        engine: SyncEngine,
        clock: Clock
    ): SyncOperationsService = SyncOperationsService(access, definitions, state, runs, engine, clock)

    @Bean
    fun syncOperationsController(operations: SyncOperationsService): SyncOperationsController = SyncOperationsController(operations)

    @Bean
    @ConditionalOnMissingBean
    fun syncScheduler(
        runs: SyncRunRepository,
        engine: SyncEngine,
        clusterLock: ClusterLock,
        properties: SyncProperties,
        clock: Clock
    ): SyncScheduler = SyncScheduler(runs, engine, clusterLock, properties, clock)
}
