package wasichai.integrations

import org.slf4j.LoggerFactory
import wasichai.integrations.autoconfigure.IntegrationsProperties
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.AuthType
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.TokenGrant
import java.time.Clock
import java.time.Instant
import java.util.Base64

// turns a connection's sealed secrets into the headers a call needs, refreshing oauth tokens on the way.
// one refresh at a time per connection: the credentials row is locked while the provider is asked.
class ConnectionCredentials(
    private val connections: ConnectionRepository,
    private val registry: ConnectorRegistry,
    private val providerConfigs: ProviderConfigRepository,
    private val providerConfigService: ProviderConfigService,
    private val tokens: TokenClient,
    private val tx: Transactions,
    private val properties: IntegrationsProperties.OAuth,
    private val clock: Clock
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun headers(connection: Connection): Map<String, String> {
        val stored = stored(connection)
        return when (connection.authType) {
            AuthType.API_KEY -> {
                val method =
                    registry
                        .require(connection.connectorId)
                        .manifest.authMethods
                        .filterIsInstance<AuthMethod.ApiKey>()
                        .first()
                mapOf(method.headerName to (method.prefix.orEmpty() + stored.secrets.apiKey.orEmpty()))
            }
            AuthType.BASIC -> {
                val raw = "${stored.secrets.username.orEmpty()}:${stored.secrets.password.orEmpty()}"
                mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray()))
            }
            AuthType.OAUTH2_AUTHORIZATION_CODE, AuthType.OAUTH2_CLIENT_CREDENTIALS -> {
                val token =
                    if (expiring(stored.accessExpiresAt)) refresh(connection, stored.secrets.accessToken) else stored.secrets.accessToken
                mapOf("Authorization" to "Bearer ${token.orEmpty()}")
            }
        }
    }

    // opens the stored secrets without calling the provider: a test of a connector that cannot check its account
    suspend fun check(connection: Connection) {
        stored(connection)
    }

    private suspend fun stored(connection: Connection): StoredCredentials =
        try {
            connections.credentials(connection.organizationId, connection.id) ?: throw ConnectorException.AuthExpired("connection has no credentials")
        } catch (e: CredentialUnreadableException) {
            unreadable(connection, e)
        }

    // no configured key opens the secret (a removed key, a tampered row): only new credentials help, so the
    // connection says so instead of failing every call with an unexpected error (issue #60)
    private suspend fun unreadable(
        connection: Connection,
        e: CredentialUnreadableException
    ): Nothing {
        log.warn("integrations: credentials of connection {} cannot be opened ({}); marked NEEDS_REAUTH", connection.id, e.message)
        connections.markFailed(connection.organizationId, connection.id, ConnectionStatus.NEEDS_REAUTH, ConnectionHealth.FAILING, UNREADABLE, clock.instant())
        throw ConnectorException.AuthExpired(UNREADABLE)
    }

    fun canRefresh(connection: Connection): Boolean =
        connection.authType == AuthType.OAUTH2_AUTHORIZATION_CODE || connection.authType == AuthType.OAUTH2_CLIENT_CREDENTIALS

    // staleToken: the token the caller saw fail or expire. if another caller already replaced it, use theirs.
    // a refused refresh is recorded after the transaction: inside it, the throw would roll the record back.
    suspend fun refresh(
        connection: Connection,
        staleToken: String?
    ): String? {
        var refused: TokenRefusedException? = null
        var unopened: CredentialUnreadableException? = null
        val token =
            tx.run {
                val locked =
                    try {
                        connections.credentials(connection.organizationId, connection.id, forUpdate = true)
                    } catch (e: CredentialUnreadableException) {
                        unopened = e
                        return@run null
                    } ?: throw ConnectorException.AuthExpired("connection has no credentials")
                if (locked.secrets.accessToken != staleToken && !expiring(locked.accessExpiresAt)) return@run locked.secrets.accessToken
                val grant =
                    try {
                        newGrant(connection, locked)
                    } catch (e: TokenRefusedException) {
                        refused = e
                        return@run null
                    }
                val secrets =
                    locked.secrets.copy(
                        accessToken = grant.accessToken,
                        // providers that rotate refresh tokens send a new one; the others keep the old one valid
                        refreshToken = grant.refreshToken ?: locked.secrets.refreshToken
                    )
                connections.saveCredentials(
                    connection.organizationId,
                    connection.id,
                    StoredCredentials(secrets, expiry(grant)),
                    clock.instant(),
                    refreshed = true
                )
                grant.accessToken
            }
        unopened?.let { unreadable(connection, it) }
        refused?.let { e ->
            log.warn("integrations: refresh refused for connection {} ({})", connection.id, e.error)
            connections.markFailed(
                connection.organizationId,
                connection.id,
                ConnectionStatus.NEEDS_REAUTH,
                ConnectionHealth.FAILING,
                "authorization expired: ${e.error}",
                clock.instant()
            )
            throw ConnectorException.AuthExpired("the provider refused to refresh; the connection needs a new authorization")
        }
        return token
    }

    private suspend fun newGrant(
        connection: Connection,
        stored: StoredCredentials
    ): TokenGrant {
        val methods = registry.require(connection.connectorId).manifest.authMethods
        val client = client(connection)
        return when (connection.authType) {
            AuthType.OAUTH2_CLIENT_CREDENTIALS -> {
                val method = methods.filterIsInstance<AuthMethod.OAuth2ClientCredentials>().first()
                tokens.clientCredentials(method.tokenUrl, client, connection.scopes.ifEmpty { method.defaultScopes }, " ")
            }
            else -> {
                val method = methods.filterIsInstance<AuthMethod.OAuth2AuthorizationCode>().first()
                val refreshToken = stored.secrets.refreshToken ?: throw TokenRefusedException("no_refresh_token")
                tokens.refresh(method.tokenUrl, client, refreshToken, method.scopeSeparator)
            }
        }
    }

    suspend fun client(connection: Connection): OAuthClient {
        val config =
            providerConfigs.find(connection.organizationId, connection.connectorId)
                ?: throw ConnectorException.AuthExpired("the provider app of '${connection.connectorId}' is not configured anymore")
        val method = registry.require(connection.connectorId).manifest.authMethods
        val authentication =
            method.filterIsInstance<AuthMethod.OAuth2AuthorizationCode>().firstOrNull()?.clientAuthentication
                ?: method.filterIsInstance<AuthMethod.OAuth2ClientCredentials>().first().clientAuthentication
        return OAuthClient(config.clientId, providerConfigService.clientSecret(config), authentication)
    }

    fun expiry(grant: TokenGrant): Instant? = grant.expiresInSeconds?.let { clock.instant().plusSeconds(it) }

    private fun expiring(expiresAt: Instant?): Boolean = expiresAt != null && !expiresAt.isAfter(clock.instant().plus(properties.refreshSkew))

    companion object {
        const val UNREADABLE = "credentials cannot be decrypted with the configured keys; reconnect"
    }

    // revokes an oauth authorization at the provider when the connector declares a revoke url. best effort: the
    // connection goes anyway. stored is read by the caller before the credentials row is deleted
    suspend fun revoke(
        connection: Connection,
        stored: StoredCredentials?
    ): Boolean {
        val method =
            registry
                .find(connection.connectorId)
                ?.manifest
                ?.authMethods
                ?.filterIsInstance<AuthMethod.OAuth2AuthorizationCode>()
                ?.firstOrNull()
        val url = method?.revokeUrl
        if (connection.authType != AuthType.OAUTH2_AUTHORIZATION_CODE || url == null) return false
        val token = stored?.secrets?.refreshToken ?: stored?.secrets?.accessToken ?: return false
        return runCatching { tokens.revoke(url, client(connection), token) }
            .onFailure { log.warn("integrations: revoke for connection {} failed ({})", connection.id, it.javaClass.simpleName) }
            .isSuccess
    }
}
