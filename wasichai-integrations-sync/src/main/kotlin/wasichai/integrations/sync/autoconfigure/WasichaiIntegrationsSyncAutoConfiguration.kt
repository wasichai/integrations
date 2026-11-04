package wasichai.integrations.sync.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.autoconfigure.WasichaiIntegrationsAutoConfiguration
import wasichai.integrations.sync.DefinitionValidator
import wasichai.integrations.sync.SyncDefinitionController
import wasichai.integrations.sync.SyncDefinitionRepository
import wasichai.integrations.sync.SyncDefinitionService
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
}
