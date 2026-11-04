package wasichai.integrations.autoconfigure

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
import wasichai.core.autoconfigure.WasichaiDataAutoConfiguration
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.ModuleMigration
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.CatalogController
import wasichai.integrations.CatalogService
import wasichai.integrations.ConfiguredCredentialKeys
import wasichai.integrations.ConnectionController
import wasichai.integrations.ConnectionCredentials
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectionService
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.CredentialCipher
import wasichai.integrations.CredentialKeyProvider
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.OAuthStateRepository
import wasichai.integrations.PlatformHttp
import wasichai.integrations.ProviderConfigController
import wasichai.integrations.ProviderConfigRepository
import wasichai.integrations.ProviderConfigService
import wasichai.integrations.TokenClient
import wasichai.integrations.Transactions
import wasichai.integrations.sdk.Connector
import java.time.Clock

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
        clock: Clock
    ): ConnectorContexts = ConnectorContexts(credentials, http, clock)

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
        clock: Clock
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
            clock
        )

    @Bean
    fun connectionController(connections: ConnectionService): ConnectionController = ConnectionController(connections)

    companion object {
        // after every wasichai module (they start at 100): integrations tables may reference theirs later
        const val ORDER = ModuleMigration.MODULE_ORDER + 100
    }
}
