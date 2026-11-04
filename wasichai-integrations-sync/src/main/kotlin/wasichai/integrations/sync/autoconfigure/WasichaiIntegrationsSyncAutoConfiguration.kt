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
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.IntegrationsMeters
import wasichai.integrations.JobRepository
import wasichai.integrations.Transactions
import wasichai.integrations.autoconfigure.WasichaiIntegrationsAutoConfiguration
import wasichai.integrations.sync.DefinitionValidator
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
        clock: Clock
    ): SyncEngine = SyncEngine(definitions, state, runs, registry, connections, contexts, jobs, integrationsTransactions, meters, properties, clock)

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
