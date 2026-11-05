package wasichai.integrations.ai.autoconfigure

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditService
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectionUsage
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.IntegrationsMeters
import wasichai.integrations.Transactions
import wasichai.integrations.ai.ToolCatalog
import wasichai.integrations.ai.ToolController
import wasichai.integrations.ai.ToolExecutions
import wasichai.integrations.ai.ToolRateLimits
import wasichai.integrations.ai.ToolRepository
import wasichai.integrations.autoconfigure.WasichaiIntegrationsAutoConfiguration
import java.time.Clock

// AI tools (ADR-0026): the catalog of explicitly exposed tools and the one execution path. needs the integrations
// module and its tables, nothing of sync or workflows
@AutoConfiguration(after = [WasichaiIntegrationsAutoConfiguration::class])
@ConditionalOnBean(ConnectorRegistry::class)
@ConditionalOnProperty(prefix = "wasichai.integrations.ai", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WasichaiIntegrationsAiAutoConfiguration {
    // never @ConditionalOnMissingBean: another module's ModuleMigration would make it back off
    @Bean
    fun wasichaiIntegrationsAiMigration(): ModuleMigration =
        ModuleMigration("integrations_ai", "classpath:db/wasichai/integrations-ai", WasichaiIntegrationsAutoConfiguration.ORDER + 30)

    @Bean
    @ConditionalOnMissingBean
    fun toolRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): ToolRepository = ToolRepository(db, json, schemas)

    // a connection a tool stands on is not deleted under it
    @Bean
    fun toolConnectionUsage(repository: ToolRepository): ConnectionUsage =
        ConnectionUsage { org, connection -> repository.namesOnConnection(org, connection).map { "tool '$it'" } }

    @Bean
    @ConditionalOnMissingBean
    fun toolRateLimits(clock: Clock): ToolRateLimits = ToolRateLimits(clock)

    @Bean
    @ConditionalOnMissingBean
    fun toolCatalog(
        access: IntegrationsAccess,
        currentUser: CurrentUser,
        repository: ToolRepository,
        registry: ConnectorRegistry,
        connections: ConnectionRepository,
        contexts: ConnectorContexts,
        limits: ToolRateLimits,
        audit: AuditService,
        integrationsTransactions: Transactions,
        json: JsonMapper,
        clock: Clock
    ): ToolCatalog = ToolCatalog(access, currentUser, repository, registry, connections, contexts, limits, audit, integrationsTransactions, json, clock)

    @Bean
    @ConditionalOnMissingBean
    fun toolExecutions(
        access: IntegrationsAccess,
        currentUser: CurrentUser,
        repository: ToolRepository,
        registry: ConnectorRegistry,
        connections: ConnectionRepository,
        contexts: ConnectorContexts,
        limits: ToolRateLimits,
        audit: AuditService,
        integrationsTransactions: Transactions,
        meters: IntegrationsMeters,
        json: JsonMapper,
        clock: Clock
    ): ToolExecutions =
        ToolExecutions(access, currentUser, repository, registry, connections, contexts, limits, audit, integrationsTransactions, meters, json, clock)

    @Bean
    fun toolController(
        catalog: ToolCatalog,
        executions: ToolExecutions
    ): ToolController = ToolController(catalog, executions)
}
