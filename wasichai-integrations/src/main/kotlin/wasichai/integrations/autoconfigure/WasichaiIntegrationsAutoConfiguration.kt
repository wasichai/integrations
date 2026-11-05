package wasichai.integrations.autoconfigure

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditService
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.ClusterLock
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.CatalogController
import wasichai.integrations.CatalogService
import wasichai.integrations.ConfiguredCredentialKeys
import wasichai.integrations.ConnectionController
import wasichai.integrations.ConnectionCredentials
import wasichai.integrations.ConnectionDataService
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectionService
import wasichai.integrations.ConnectionUsage
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.CoreConnectionData
import wasichai.integrations.CredentialCipher
import wasichai.integrations.CredentialKeyProvider
import wasichai.integrations.ErasureContributor
import wasichai.integrations.EventJobs
import wasichai.integrations.EventRepository
import wasichai.integrations.ExportContributor
import wasichai.integrations.IntegrationEventListener
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.IntegrationsMeters
import wasichai.integrations.JobHandler
import wasichai.integrations.JobRepository
import wasichai.integrations.JobRunner
import wasichai.integrations.JobWorker
import wasichai.integrations.OAuthStateRepository
import wasichai.integrations.OperationsController
import wasichai.integrations.OperationsService
import wasichai.integrations.PlatformHttp
import wasichai.integrations.ProviderConfigController
import wasichai.integrations.ProviderConfigRepository
import wasichai.integrations.ProviderConfigService
import wasichai.integrations.RateLimits
import wasichai.integrations.Retention
import wasichai.integrations.RetentionScheduler
import wasichai.integrations.RetentionTask
import wasichai.integrations.SqlRetentionTask
import wasichai.integrations.TokenClient
import wasichai.integrations.Transactions
import wasichai.integrations.UsageController
import wasichai.integrations.UsageFlusher
import wasichai.integrations.UsageMeter
import wasichai.integrations.UsageRepository
import wasichai.integrations.UsageService
import wasichai.integrations.WebhookController
import wasichai.integrations.WebhookEndpointRepository
import wasichai.integrations.WebhookService
import wasichai.integrations.sdk.Connector
import java.time.Clock
import java.time.Duration

// the integrations control plane (ADR-0001). connectors are Connector beans the app (or a connector's own
// auto-configuration) declares; this module never names one.
@AutoConfiguration(after = [WasichaiDataAutoConfiguration::class])
@ConditionalOnProperty(prefix = "wasichai.integrations", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IntegrationsProperties::class)
@Import(IntegrationsSecurityConfiguration::class)
class WasichaiIntegrationsAutoConfiguration {
    // never @ConditionalOnMissingBean: core's own ModuleMigration would make it back off
    @Bean
    fun wasichaiIntegrationsMigration(): ModuleMigration = ModuleMigration("integrations", "classpath:db/wasichai/integrations", ORDER)

    @Bean
    @ConditionalOnMissingBean
    fun integrationsClock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnMissingBean
    fun credentialKeyProvider(properties: IntegrationsProperties): CredentialKeyProvider =
        ConfiguredCredentialKeys(properties.encryption.keys, properties.encryption.activeKey)

    @Bean
    @ConditionalOnMissingBean
    fun credentialCipher(keys: CredentialKeyProvider): CredentialCipher = CredentialCipher(keys)

    @Bean
    @ConditionalOnMissingBean
    fun connectorRegistry(connectors: ObjectProvider<Connector>): ConnectorRegistry = ConnectorRegistry(connectors.orderedStream().toList())

    @Bean
    @ConditionalOnMissingBean
    fun integrationsAccess(currentUser: CurrentUser): IntegrationsAccess = IntegrationsAccess(currentUser)

    @Bean
    @ConditionalOnMissingBean
    fun providerConfigRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): ProviderConfigRepository = ProviderConfigRepository(db, json, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun providerConfigService(
        access: IntegrationsAccess,
        registry: ConnectorRegistry,
        repository: ProviderConfigRepository,
        cipher: CredentialCipher,
        clock: Clock
    ): ProviderConfigService = ProviderConfigService(access, registry, repository, cipher, clock)

    @Bean
    @ConditionalOnMissingBean
    fun catalogService(
        access: IntegrationsAccess,
        registry: ConnectorRegistry,
        configs: ProviderConfigRepository
    ): CatalogService = CatalogService(access, registry, configs)

    @Bean
    fun catalogController(catalog: CatalogService): CatalogController = CatalogController(catalog)

    @Bean
    fun providerConfigController(configs: ProviderConfigService): ProviderConfigController = ProviderConfigController(configs)

    @Bean
    @ConditionalOnMissingBean
    fun platformHttp(properties: IntegrationsProperties): PlatformHttp = PlatformHttp(properties.http)

    @Bean
    @ConditionalOnMissingBean
    fun tokenClient(
        http: PlatformHttp,
        json: JsonMapper
    ): TokenClient = TokenClient(http, json)

    @Bean
    @ConditionalOnMissingBean
    fun integrationsTransactions(manager: ObjectProvider<ReactiveTransactionManager>): Transactions =
        Transactions { TransactionalOperator.create(manager.getObject()) }

    @Bean
    @ConditionalOnMissingBean
    fun connectionRepository(
        db: DatabaseClient,
        json: JsonMapper,
        cipher: CredentialCipher,
        schemas: WasichaiSchemas
    ): ConnectionRepository = ConnectionRepository(db, json, cipher, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun oauthStateRepository(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): OAuthStateRepository = OAuthStateRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun connectionCredentials(
        connections: ConnectionRepository,
        registry: ConnectorRegistry,
        providerConfigs: ProviderConfigRepository,
        providerConfigService: ProviderConfigService,
        tokens: TokenClient,
        integrationsTransactions: Transactions,
        properties: IntegrationsProperties,
        clock: Clock
    ): ConnectionCredentials =
        ConnectionCredentials(connections, registry, providerConfigs, providerConfigService, tokens, integrationsTransactions, properties.oauth, clock)

    @Bean
    @ConditionalOnMissingBean
    fun connectorContexts(
        credentials: ConnectionCredentials,
        http: PlatformHttp,
        registry: ConnectorRegistry,
        limits: RateLimits,
        meters: IntegrationsMeters,
        clock: Clock,
        usage: UsageMeter
    ): ConnectorContexts = ConnectorContexts(credentials, http, registry, limits, meters.registry, clock, usage)

    @Bean
    @ConditionalOnMissingBean
    fun integrationsMeters(registry: ObjectProvider<MeterRegistry>): IntegrationsMeters = IntegrationsMeters(registry.getIfAvailable { SimpleMeterRegistry() })

    @Bean
    @ConditionalOnMissingBean
    fun rateLimits(clock: Clock): RateLimits = RateLimits(clock)

    @Bean
    @ConditionalOnMissingBean
    fun jobRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): JobRepository = JobRepository(db, json, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun jobRunner(
        repository: JobRepository,
        handlers: ObjectProvider<JobHandler>,
        properties: IntegrationsProperties,
        meters: IntegrationsMeters,
        clock: Clock
    ): JobRunner = JobRunner(repository, handlers.orderedStream().toList(), properties.jobs, meters.registry, clock)

    @Bean
    @ConditionalOnMissingBean
    fun jobWorker(
        runner: JobRunner,
        properties: IntegrationsProperties
    ): JobWorker = JobWorker(runner, properties.jobs)

    @Bean
    @ConditionalOnMissingBean
    fun operationsService(
        access: IntegrationsAccess,
        registry: ConnectorRegistry,
        connections: ConnectionRepository,
        contexts: ConnectorContexts,
        jobs: JobRepository,
        limits: RateLimits,
        clock: Clock
    ): OperationsService = OperationsService(access, registry, connections, contexts, jobs, limits, clock)

    @Bean
    fun operationsController(operations: OperationsService): OperationsController = OperationsController(operations)

    @Bean
    @ConditionalOnMissingBean
    fun eventRepository(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): EventRepository = EventRepository(db, json, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun webhookEndpointRepository(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): WebhookEndpointRepository = WebhookEndpointRepository(db, schemas)

    // a JobHandler bean: the runner picks it up with the others
    @Bean
    @ConditionalOnMissingBean
    fun eventJobs(
        events: EventRepository,
        listeners: ObjectProvider<IntegrationEventListener>,
        clock: Clock
    ): EventJobs = EventJobs(events, listeners.orderedStream().toList(), clock)

    @Bean
    @ConditionalOnMissingBean
    fun webhookService(
        access: IntegrationsAccess,
        registry: ConnectorRegistry,
        connections: ConnectionRepository,
        endpoints: WebhookEndpointRepository,
        events: EventRepository,
        jobs: JobRepository,
        cipher: CredentialCipher,
        json: JsonMapper,
        integrationsTransactions: Transactions,
        properties: IntegrationsProperties,
        clock: Clock
    ): WebhookService = WebhookService(access, registry, connections, endpoints, events, jobs, cipher, json, integrationsTransactions, properties, clock)

    @Bean
    fun webhookController(webhooks: WebhookService): WebhookController = WebhookController(webhooks)

    @Bean
    @ConditionalOnMissingBean
    fun connectionService(
        access: IntegrationsAccess,
        registry: ConnectorRegistry,
        connections: ConnectionRepository,
        credentials: ConnectionCredentials,
        contexts: ConnectorContexts,
        providerConfigs: ProviderConfigRepository,
        providerConfigService: ProviderConfigService,
        states: OAuthStateRepository,
        tokens: TokenClient,
        cipher: CredentialCipher,
        integrationsTransactions: Transactions,
        properties: IntegrationsProperties,
        clock: Clock,
        usages: ObjectProvider<ConnectionUsage>
    ): ConnectionService =
        ConnectionService(
            access,
            registry,
            connections,
            credentials,
            contexts,
            providerConfigs,
            providerConfigService,
            states,
            tokens,
            cipher,
            integrationsTransactions,
            properties,
            clock,
            usages.orderedStream().toList()
        )

    @Bean
    fun connectionController(
        connections: ConnectionService,
        data: ConnectionDataService
    ): ConnectionController = ConnectionController(connections, data)

    // GDPR export and erasure (ADR-0029): the core's own tables, then every module's contributors
    @Bean
    @ConditionalOnMissingBean
    fun coreConnectionData(
        db: DatabaseClient,
        json: JsonMapper,
        schemas: WasichaiSchemas
    ): CoreConnectionData = CoreConnectionData(db, json, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun connectionDataService(
        access: IntegrationsAccess,
        connections: ConnectionRepository,
        credentials: ConnectionCredentials,
        connectionService: ConnectionService,
        exporters: ObjectProvider<ExportContributor>,
        erasers: ObjectProvider<ErasureContributor>,
        audit: AuditService,
        integrationsTransactions: Transactions,
        properties: IntegrationsProperties,
        clock: Clock
    ): ConnectionDataService =
        ConnectionDataService(
            access,
            connections,
            credentials,
            connectionService,
            exporters.orderedStream().toList(),
            erasers.orderedStream().toList(),
            audit,
            integrationsTransactions,
            properties.export.pageSize,
            properties.export.maxPageSize,
            clock
        )

    // usage metering (ADR-0029): modules call the meter; the flusher writes it
    @Bean
    @ConditionalOnMissingBean
    fun usageRepository(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): UsageRepository = UsageRepository(db, schemas)

    @Bean
    @ConditionalOnMissingBean
    fun usageMeter(
        repository: UsageRepository,
        clock: Clock
    ): UsageMeter = UsageMeter(clock, repository::add)

    @Bean
    @ConditionalOnMissingBean
    fun usageFlusher(
        meter: UsageMeter,
        properties: IntegrationsProperties
    ): UsageFlusher = UsageFlusher(meter, properties.usage.flushInterval)

    @Bean
    @ConditionalOnMissingBean
    fun usageService(
        access: IntegrationsAccess,
        repository: UsageRepository,
        clock: Clock
    ): UsageService = UsageService(access, repository, clock)

    @Bean
    fun usageController(usage: UsageService): UsageController = UsageController(usage)

    // retention (ADR-0029): the core's tables; other modules add RetentionTask beans of their own
    @Bean
    fun integrationsEventsRetention(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): RetentionTask {
        val m = schemas.metadata
        // an event not processed yet (RECEIVED) is never purged
        return SqlRetentionTask(
            "events",
            "integration_events",
            Duration.ofDays(30),
            db,
            "DELETE FROM $m.integration_events WHERE id IN (SELECT id FROM $m.integration_events WHERE status <> 'RECEIVED' " +
                "AND received_at < :cutoff ORDER BY received_at LIMIT :limit FOR UPDATE SKIP LOCKED)"
        )
    }

    @Bean
    fun integrationsJobsRetention(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): RetentionTask {
        val m = schemas.metadata
        return SqlRetentionTask(
            "jobs",
            "integration_jobs",
            Duration.ofDays(30),
            db,
            "DELETE FROM $m.integration_jobs WHERE id IN (SELECT id FROM $m.integration_jobs WHERE status IN ('SUCCEEDED', 'DEAD') " +
                "AND finished_at < :cutoff ORDER BY finished_at LIMIT :limit FOR UPDATE SKIP LOCKED)"
        )
    }

    @Bean
    fun integrationsUsageRetention(
        db: DatabaseClient,
        schemas: WasichaiSchemas
    ): RetentionTask {
        val m = schemas.metadata
        return SqlRetentionTask(
            "usage",
            "integration_usage_daily",
            Duration.ofDays(400),
            db,
            "DELETE FROM $m.integration_usage_daily WHERE (organization_id, day, metric) IN (SELECT organization_id, day, metric " +
                "FROM $m.integration_usage_daily WHERE day < :cutoff ORDER BY day LIMIT :limit FOR UPDATE SKIP LOCKED)",
            byDay = true
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun retention(
        tasks: ObjectProvider<RetentionTask>,
        properties: IntegrationsProperties,
        meters: IntegrationsMeters
    ): Retention = Retention(tasks.orderedStream().toList(), properties.retention, meters.registry)

    @Bean
    @ConditionalOnMissingBean
    fun retentionScheduler(
        retention: Retention,
        clusterLock: ClusterLock,
        properties: IntegrationsProperties,
        clock: Clock
    ): RetentionScheduler = RetentionScheduler(retention, clusterLock, properties.retention, clock)

    companion object {
        // after every wasichai module (they start at 100): integrations tables may reference theirs later
        const val ORDER = ModuleMigration.MODULE_ORDER + 100
    }
}
