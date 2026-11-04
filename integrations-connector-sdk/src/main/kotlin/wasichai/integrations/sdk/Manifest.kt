package wasichai.integrations.sdk

// what a connector says about itself. the platform trusts nothing it does not declare here,
// and the validator checks the declaration against the interfaces the connector really implements.
data class ConnectorManifest(
    val id: String,
    val provider: ProviderInfo,
    val version: String,
    val sdkVersion: SdkVersion,
    val description: String,
    val authMethods: List<AuthMethod>,
    val capabilities: Map<Capability, Maturity>,
    val resources: List<ResourceDeclaration> = emptyList(),
    val actions: List<ActionDeclaration> = emptyList(),
    val syncDirections: Set<SyncDirectionSupport> = emptySet(),
    val pagination: Set<PaginationStrategy> = emptySet(),
    val rateLimit: RateLimitHint? = null,
    val webhooks: WebhookSupport? = null,
    val requiredPermissions: List<String> = emptyList()
) {
    fun maturityOf(capability: Capability): Maturity? = capabilities[capability]

    fun supports(capability: Capability): Boolean = capabilities[capability]?.let { it >= Maturity.IMPLEMENTED } ?: false
}

data class ProviderInfo(
    val name: String,
    val homepage: String? = null,
    val documentation: String? = null
)

// what a connector can do. each one maps to an optional interface (ManifestValidator)
enum class Capability {
    AUTHENTICATION,
    DISCOVERY,
    READ,
    CHANGE_FEED,
    WRITE,
    UPSERT,
    WEBHOOKS,
    ACTIONS
}

// honest labels: DECLARED is planned, IMPLEMENTED has code, TESTED has contract tests that pass
enum class Maturity {
    DECLARED,
    IMPLEMENTED,
    TESTED
}

enum class SyncDirectionSupport {
    SOURCE,
    TARGET
}

enum class PaginationStrategy {
    CURSOR,
    OFFSET,
    NEXT_URL
}

data class RateLimitHint(
    val requestsPerSecond: Double,
    val burst: Int,
    val scope: String
) {
    init {
        require(requestsPerSecond > 0) { "requestsPerSecond must be positive" }
        require(burst >= 1) { "burst must be at least 1" }
    }
}

data class WebhookSupport(
    val signatureScheme: String,
    val events: List<String>,
    // events are hints to fetch, not the data itself: ordering and completeness are not trusted
    val payloadIsAuthoritative: Boolean = false
)

data class ResourceDeclaration(
    val name: String,
    val label: String,
    // dynamic resources (a Notion data source, a Salesforce custom object) are found by discovery
    val dynamic: Boolean = false
)

data class ActionDeclaration(
    val name: String,
    val label: String,
    val sensitive: Boolean = true
)
