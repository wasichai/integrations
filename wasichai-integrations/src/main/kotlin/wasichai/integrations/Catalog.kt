package wasichai.integrations

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.Maturity

// what the catalog shows of a connector: its manifest as declared, plus whether this tenant configured it.
// no urls of token endpoints are hidden here: they are public provider facts, not secrets.
data class ConnectorView(
    val id: String,
    val provider: String,
    val homepage: String?,
    val documentation: String?,
    val version: String,
    val sdkVersion: String,
    val description: String,
    val authMethods: List<AuthMethodView>,
    val capabilities: Map<Capability, Maturity>,
    val resources: List<ResourceView>,
    val actions: List<ActionView>,
    val syncDirections: List<String>,
    val webhooks: WebhookView?,
    val rateLimit: RateLimitView?,
    val requiredPermissions: List<String>,
    // an oauth connector needs the tenant's app registered first; an api key connector does not
    val needsProviderConfig: Boolean,
    val providerConfigured: Boolean
)

data class AuthMethodView(
    val type: String,
    val pkce: Boolean? = null,
    val defaultScopes: List<String> = emptyList()
)

data class ResourceView(
    val name: String,
    val label: String,
    val dynamic: Boolean
)

data class ActionView(
    val name: String,
    val label: String,
    val sensitive: Boolean
)

data class WebhookView(
    val signatureScheme: String,
    val events: List<String>
)

data class RateLimitView(
    val requestsPerSecond: Double,
    val burst: Int,
    val scope: String
)

data class CapabilitiesView(
    val connectorId: String,
    val capabilities: Map<Capability, Maturity>
)

class CatalogService(
    private val access: IntegrationsAccess,
    private val registry: ConnectorRegistry,
    private val configs: ProviderConfigRepository
) {
    suspend fun list(): List<ConnectorView> {
        val user = access.reader()
        val configured = configs.list(user.organizationId).map { it.connectorId }.toSet()
        return registry.all().map { view(it, it.manifest.id in configured) }
    }

    suspend fun get(id: String): ConnectorView {
        val user = access.reader()
        val connector = registry.require(id)
        return view(connector, configs.find(user.organizationId, id) != null)
    }

    suspend fun capabilities(id: String): CapabilitiesView {
        access.reader()
        return CapabilitiesView(id, registry.require(id).manifest.capabilities)
    }

    private fun view(
        connector: Connector,
        configured: Boolean
    ): ConnectorView {
        val m = connector.manifest
        return ConnectorView(
            id = m.id,
            provider = m.provider.name,
            homepage = m.provider.homepage,
            documentation = m.provider.documentation,
            version = m.version,
            sdkVersion = m.sdkVersion.toString(),
            description = m.description,
            authMethods =
                m.authMethods.map { method ->
                    when (method) {
                        is AuthMethod.OAuth2AuthorizationCode -> AuthMethodView(method.type.name, method.pkce, method.defaultScopes)
                        is AuthMethod.OAuth2ClientCredentials -> AuthMethodView(method.type.name, null, method.defaultScopes)
                        else -> AuthMethodView(method.type.name)
                    }
                },
            capabilities = m.capabilities.toSortedMap(),
            resources = m.resources.map { ResourceView(it.name, it.label, it.dynamic) },
            actions = m.actions.map { ActionView(it.name, it.label, it.sensitive) },
            syncDirections = m.syncDirections.map { it.name }.sorted(),
            webhooks = m.webhooks?.let { WebhookView(it.signatureScheme, it.events) },
            rateLimit = m.rateLimit?.let { RateLimitView(it.requestsPerSecond, it.burst, it.scope) },
            requiredPermissions = m.requiredPermissions,
            needsProviderConfig = m.authMethods.any { it is AuthMethod.OAuth2AuthorizationCode || it is AuthMethod.OAuth2ClientCredentials },
            providerConfigured = configured
        )
    }
}

@RestController
@RequestMapping("/api/integrations/connectors")
class CatalogController(
    private val catalog: CatalogService
) {
    @GetMapping
    suspend fun list(): List<ConnectorView> = catalog.list()

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: String
    ): ConnectorView = catalog.get(id)

    @GetMapping("/{id}/capabilities")
    suspend fun capabilities(
        @PathVariable id: String
    ): CapabilitiesView = catalog.capabilities(id)
}
