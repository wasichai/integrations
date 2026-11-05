package wasichai.integrations

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.integrations.autoconfigure.IntegrationsProperties
import wasichai.integrations.sdk.AccountInfo
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.AuthType
import wasichai.integrations.sdk.Authenticator
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.TokenGrant
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class CreateConnectionRequest(
    val connectorId: String? = null,
    val name: String? = null,
    val authType: AuthType? = null,
    val apiKey: String? = null,
    val username: String? = null,
    val password: String? = null,
    val scopes: List<String> = emptyList()
)

data class OAuthStartRequest(
    val connectorId: String? = null,
    val name: String? = null
)

data class OAuthStartView(
    val authorizationUrl: String,
    val expiresAt: Instant
)

data class ConnectionView(
    val id: UUID,
    val connectorId: String,
    val name: String,
    val providerAccountId: String?,
    val displayName: String?,
    // false: the connector cannot ask the provider who the account is (no Authenticator), so the credentials were
    // stored unchecked, a test only reads them back, and the same account can be connected twice
    val accountChecked: Boolean,
    val status: ConnectionStatus,
    val authType: AuthType,
    val scopes: List<String>,
    val metadata: Map<String, String>,
    val health: ConnectionHealth,
    val lastValidatedAt: Instant?,
    val lastError: String?,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ConnectionTestView(
    val ok: Boolean,
    val error: String?,
    val retryable: Boolean?,
    // whether the provider was asked; ok without it means only that the stored credentials could be read
    val accountChecked: Boolean,
    val connection: ConnectionView
)

class ConnectionService(
    private val access: IntegrationsAccess,
    private val registry: ConnectorRegistry,
    private val connections: ConnectionRepository,
    private val credentials: ConnectionCredentials,
    private val contexts: ConnectorContexts,
    private val providerConfigs: ProviderConfigRepository,
    private val providerConfigService: ProviderConfigService,
    private val states: OAuthStateRepository,
    private val tokens: TokenClient,
    private val cipher: CredentialCipher,
    private val tx: Transactions,
    private val properties: IntegrationsProperties,
    private val clock: Clock,
    private val usages: List<ConnectionUsage> = emptyList()
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    suspend fun list(): List<ConnectionView> = connections.list(access.reader().organizationId).map(::view)

    suspend fun get(id: UUID): ConnectionView = view(find(access.reader().organizationId, id))

    // api key, basic or client credentials: checked against the provider before it is stored
    suspend fun create(request: CreateConnectionRequest): ConnectionView {
        val user = access.manager()
        val violations = mutableListOf<FieldViolation>()
        val connector = request.connectorId?.let { registry.find(it) }
        if (connector == null) violations += FieldViolation("connectorId", "unknown connector")
        val type = request.authType
        if (type == null) violations += FieldViolation("authType", "required")
        if (type == AuthType.OAUTH2_AUTHORIZATION_CODE) violations += FieldViolation("authType", "start an oauth connection through /connections/oauth/start")
        if (connector != null && type != null && connector.manifest.authMethods.none { it.type == type }) {
            violations += FieldViolation("authType", "connector does not accept $type")
        }
        when (type) {
            AuthType.API_KEY -> if (request.apiKey.isNullOrBlank()) violations += FieldViolation("apiKey", "required")
            AuthType.BASIC -> {
                if (request.username.isNullOrBlank()) violations += FieldViolation("username", "required")
                if (request.password.isNullOrEmpty()) violations += FieldViolation("password", "required")
            }
            else -> Unit
        }
        val name = validName(request.name ?: connector?.manifest?.provider?.name, violations)
        if (violations.isNotEmpty()) throw ValidationException("Invalid connection", violations)
        connector!!
        val now = clock.instant()
        val id = UUID.randomUUID()
        val (secrets, expiresAt, scopes) =
            when (type!!) {
                AuthType.API_KEY -> Triple(ConnectionSecrets(apiKey = request.apiKey), null, emptyList())
                AuthType.BASIC -> Triple(ConnectionSecrets(username = request.username, password = request.password), null, emptyList())
                AuthType.OAUTH2_CLIENT_CREDENTIALS -> {
                    val method =
                        connector.manifest.authMethods
                            .filterIsInstance<AuthMethod.OAuth2ClientCredentials>()
                            .first()
                    val probe = draft(id, user.organizationId, connector.manifest.id, name, type, emptyList(), emptyMap(), now)
                    val scopes = request.scopes.ifEmpty { method.defaultScopes }
                    val grant = guarded { tokens.clientCredentials(method.tokenUrl, credentials.client(probe), scopes, " ") }
                    Triple(ConnectionSecrets(accessToken = grant.accessToken), credentials.expiry(grant), grant.scopes.ifEmpty { scopes })
                }
                AuthType.OAUTH2_AUTHORIZATION_CODE -> error("unreachable")
            }
        val draft = draft(id, user.organizationId, connector.manifest.id, name, type, scopes, emptyMap(), now)
        val headers = headersFor(draft, secrets)
        val account = guarded { account(connector.manifest.id, contexts.pending(headers, emptyMap())) }
        return view(
            store(
                draft.copy(providerAccountId = account?.providerAccountId, displayName = account?.displayName),
                StoredCredentials(secrets, expiresAt),
                user.userId
            )
        )
    }

    suspend fun startOAuth(request: OAuthStartRequest): OAuthStartView {
        val user = access.manager()
        val violations = mutableListOf<FieldViolation>()
        val connector = request.connectorId?.let { registry.find(it) }
        val method =
            connector
                ?.manifest
                ?.authMethods
                ?.filterIsInstance<AuthMethod.OAuth2AuthorizationCode>()
                ?.firstOrNull()
        if (connector == null) violations += FieldViolation("connectorId", "unknown connector")
        if (connector != null && method == null) violations += FieldViolation("connectorId", "connector has no oauth authorization")
        val name = validName(request.name ?: connector?.manifest?.provider?.name, violations)
        if (violations.isNotEmpty()) throw ValidationException("Invalid oauth start", violations)
        val config =
            providerConfigs.find(user.organizationId, connector!!.manifest.id)
                ?: throw ConflictException("Register the provider app of '${connector.manifest.id}' first (provider-configs)")
        val now = clock.instant()
        states.purge(now)
        val stateValue = token(32)
        val verifier = if (method!!.pkce) token(48) else null
        val stateId = UUID.randomUUID()
        val expiresAt = now.plus(properties.oauth.stateTtl)
        states.insert(
            OAuthState(
                stateId,
                user.organizationId,
                user.userId,
                connector.manifest.id,
                verifier?.let { cipher.seal(it, verifierContext(user.organizationId, stateId)) },
                callbackUrl(),
                name
            ),
            stateValue,
            expiresAt
        )
        val scopes = (method.defaultScopes + config.scopes).distinct()
        val params =
            linkedMapOf(
                "response_type" to "code",
                "client_id" to config.clientId,
                "redirect_uri" to callbackUrl(),
                "state" to stateValue
            )
        if (scopes.isNotEmpty()) params["scope"] = scopes.joinToString(method.scopeSeparator)
        verifier?.let {
            params["code_challenge"] = challenge(it)
            params["code_challenge_method"] = "S256"
        }
        (connector as? Authenticator)?.authorizationParameters()?.forEach { (k, v) -> params.putIfAbsent(k, v) }
        return OAuthStartView(PlatformHttp.withQuery(method.authorizeUrl, params).toString(), expiresAt)
    }

    // the browser comes back from the provider. no session here: the state row says who and which tenant.
    // every outcome is a redirect to the configured return url; nothing about the failure leaks but a code.
    suspend fun completeOAuth(
        code: String?,
        stateValue: String?,
        error: String?
    ): URI {
        val now = clock.instant()
        val state = stateValue?.takeIf { it.length in 16..200 }?.let { states.consume(it, now) } ?: return back("error", "invalid_state")
        if (error != null) return back("error", if (error == "access_denied") "access_denied" else "provider_error")
        if (code.isNullOrBlank()) return back("error", "missing_code")
        val connector = registry.find(state.connectorId) ?: return back("error", "unknown_connector")
        val method =
            connector.manifest.authMethods
                .filterIsInstance<AuthMethod.OAuth2AuthorizationCode>()
                .first()
        return try {
            val config = providerConfigs.find(state.organizationId, state.connectorId) ?: return back("error", "provider_not_configured")
            val client = OAuthClient(config.clientId, providerConfigService.clientSecret(config), method.clientAuthentication)
            val verifier = state.verifierSealed?.let { cipher.open(it, verifierContext(state.organizationId, state.id)) }
            val grant = tokens.exchangeCode(method.tokenUrl, client, code, state.redirectUri, verifier, method.scopeSeparator)
            val metadata = (connector as? Authenticator)?.connectionMetadata(grant).orEmpty()
            val secrets = ConnectionSecrets(accessToken = grant.accessToken, refreshToken = grant.refreshToken)
            val account = account(connector.manifest.id, contexts.pending(mapOf("Authorization" to "Bearer ${grant.accessToken}"), metadata))
            val draft =
                draft(
                    UUID.randomUUID(),
                    state.organizationId,
                    connector.manifest.id,
                    state.connectionName,
                    AuthType.OAUTH2_AUTHORIZATION_CODE,
                    scopes(grant, method),
                    metadata,
                    now
                ).copy(providerAccountId = account?.providerAccountId, displayName = account?.displayName)
            val stored = store(draft, StoredCredentials(secrets, credentials.expiry(grant)), state.userId)
            back("connected", null, stored.id)
        } catch (e: TokenRefusedException) {
            log.warn("integrations: code exchange refused for {} ({})", state.connectorId, e.error)
            back("error", "token_refused")
        } catch (e: ConnectorException) {
            log.warn("integrations: oauth callback for {} failed: {}", state.connectorId, e.javaClass.simpleName)
            back("error", "provider_unavailable")
        }
    }

    // a manager's action: it calls the provider and changes the connection's status and health
    suspend fun test(id: UUID): ConnectionTestView {
        val user = access.manager()
        val connection = find(user.organizationId, id)
        val now = clock.instant()
        var asked = false
        return try {
            // credentials no key opens fail here, even for a connector that cannot check its account
            credentials.check(connection)
            asked = registry.find(connection.connectorId) is Authenticator
            account(connection.connectorId, contexts.forConnection(connection))
            connections.markValidated(user.organizationId, id, now)
            ConnectionTestView(true, null, null, asked, view(find(user.organizationId, id)))
        } catch (e: ConnectorException) {
            val status =
                if (e is ConnectorException.AuthExpired) {
                    ConnectionStatus.NEEDS_REAUTH
                } else if (e.retryable) {
                    null
                } else {
                    ConnectionStatus.ERROR
                }
            connections.markFailed(
                user.organizationId,
                id,
                status,
                if (e.retryable &&
                    e !is ConnectorException.AuthExpired
                ) {
                    ConnectionHealth.DEGRADED
                } else {
                    ConnectionHealth.FAILING
                },
                describe(e),
                now
            )
            ConnectionTestView(false, describe(e), e.retryable, asked, view(find(user.organizationId, id)))
        }
    }

    suspend fun delete(id: UUID) {
        val user = access.manager()
        val connection = find(user.organizationId, id)
        val users = usages.flatMap { it.usedBy(user.organizationId, id) }
        if (users.isNotEmpty()) throw ConflictException("Connection $id is still used by ${users.joinToString(", ")}; delete those first")
        val method =
            registry
                .find(connection.connectorId)
                ?.manifest
                ?.authMethods
                ?.filterIsInstance<AuthMethod.OAuth2AuthorizationCode>()
                ?.firstOrNull()
        if (connection.authType == AuthType.OAUTH2_AUTHORIZATION_CODE && method?.revokeUrl != null) {
            runCatching {
                val stored = connections.credentials(user.organizationId, id)
                val token = stored?.secrets?.refreshToken ?: stored?.secrets?.accessToken
                if (token != null) tokens.revoke(method.revokeUrl!!, credentials.client(connection), token)
            }.onFailure { log.warn("integrations: revoke for connection {} failed ({}), deleting anyway", id, it.javaClass.simpleName) }
        }
        if (!connections.delete(user.organizationId, id)) throw NotFoundException("Connection $id not found")
    }

    // one connection per provider account: connecting the same account again refreshes it in place
    private suspend fun store(
        draft: Connection,
        stored: StoredCredentials,
        userId: UUID
    ): Connection {
        suspend fun attempt(): Connection =
            tx.run {
                val existing = draft.providerAccountId?.let { connections.findByAccount(draft.organizationId, draft.connectorId, it) }
                if (existing != null) {
                    connections.reauthorized(existing.copy(scopes = draft.scopes, metadata = draft.metadata, displayName = draft.displayName), clock.instant())
                    connections.saveCredentials(draft.organizationId, existing.id, stored, clock.instant())
                    existing
                } else {
                    connections.insert(draft, userId)
                    connections.saveCredentials(draft.organizationId, draft.id, stored, clock.instant())
                    draft
                }
            }
        val saved =
            try {
                attempt()
            } catch (_: DataIntegrityViolationException) {
                // a concurrent connect of the same account won the insert: now it is a reconnect
                attempt()
            }
        return connections.find(draft.organizationId, saved.id)!!
    }

    private suspend fun account(
        connectorId: String,
        context: wasichai.integrations.sdk.ConnectorContext
    ): AccountInfo? = (registry.require(connectorId) as? Authenticator)?.accountInfo(context)

    private suspend fun headersFor(
        draft: Connection,
        secrets: ConnectionSecrets
    ): Map<String, String> =
        when (draft.authType) {
            AuthType.API_KEY -> {
                val method =
                    registry
                        .require(draft.connectorId)
                        .manifest.authMethods
                        .filterIsInstance<AuthMethod.ApiKey>()
                        .first()
                mapOf(method.headerName to (method.prefix.orEmpty() + secrets.apiKey.orEmpty()))
            }
            AuthType.BASIC ->
                mapOf("Authorization" to "Basic " + Base64.getEncoder().encodeToString("${secrets.username}:${secrets.password}".toByteArray()))
            else -> mapOf("Authorization" to "Bearer ${secrets.accessToken}")
        }

    // provider failures at connect time are the caller's to fix: a 400 naming what went wrong, no secret
    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block()
        } catch (e: TokenRefusedException) {
            throw ValidationException("The provider refused the credentials", "credentials", e.error)
        } catch (e: ConnectorException) {
            throw ValidationException("The provider did not accept the connection", "credentials", describe(e))
        }

    private fun describe(e: ConnectorException): String = "${e.javaClass.simpleName}: ${e.message}".take(500)

    private fun scopes(
        grant: TokenGrant,
        method: AuthMethod.OAuth2AuthorizationCode
    ): List<String> = grant.scopes.ifEmpty { method.defaultScopes }

    private fun draft(
        id: UUID,
        organizationId: UUID,
        connectorId: String,
        name: String,
        type: AuthType,
        scopes: List<String>,
        metadata: Map<String, String>,
        now: Instant
    ) = Connection(
        id,
        organizationId,
        connectorId,
        name,
        null,
        null,
        ConnectionStatus.ACTIVE,
        type,
        scopes,
        metadata,
        ConnectionHealth.HEALTHY,
        now,
        null,
        1,
        now,
        now
    )

    private suspend fun find(
        organizationId: UUID,
        id: UUID
    ): Connection = connections.find(organizationId, id) ?: throw NotFoundException("Connection $id not found")

    private fun validName(
        name: String?,
        violations: MutableList<FieldViolation>
    ): String {
        val trimmed = name?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 200) violations += FieldViolation("name", "1 to 200 characters")
        return trimmed
    }

    private fun callbackUrl(): String {
        val base = properties.publicBaseUrl ?: throw ConflictException("OAuth needs wasichai.integrations.public-base-url")
        return base.trimEnd('/') + CALLBACK_PATH
    }

    // the return url is configuration, never a request value: no open redirect
    private fun back(
        status: String,
        error: String?,
        connectionId: UUID? = null
    ): URI {
        val params = linkedMapOf("status" to status)
        error?.let { params["error"] = it }
        connectionId?.let { params["connectionId"] = it.toString() }
        return PlatformHttp.withQuery(properties.oauth.returnUrl, params)
    }

    private fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    fun view(c: Connection) =
        ConnectionView(
            c.id,
            c.connectorId,
            c.name,
            c.providerAccountId,
            c.displayName,
            registry.find(c.connectorId) is Authenticator,
            c.status,
            c.authType,
            c.scopes,
            c.metadata,
            c.health,
            c.lastValidatedAt,
            c.lastError,
            c.version,
            c.createdAt,
            c.updatedAt
        )

    companion object {
        const val CALLBACK_PATH = "/api/integrations/oauth/callback"

        // rfc 7636 S256
        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        fun verifierContext(
            organizationId: UUID,
            stateId: UUID
        ) = SealContext(organizationId, "integration_oauth_states", stateId, "verifier_sealed")
    }
}
