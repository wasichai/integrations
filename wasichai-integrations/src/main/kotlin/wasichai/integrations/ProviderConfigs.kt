package wasichai.integrations

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.PreconditionFailedException
import wasichai.core.common.ValidationException
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.sdk.AuthType
import java.time.Clock
import java.time.Instant
import java.util.UUID

// a tenant's oauth app at one provider. the secret is write-only: answers say whether one is set, never what
data class ProviderConfig(
    val id: UUID,
    val organizationId: UUID,
    val connectorId: String,
    val clientId: String,
    val clientSecretSealed: String?,
    val scopes: List<String>,
    val options: Map<String, String>,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ProviderConfigRequest(
    val clientId: String? = null,
    // absent keeps the stored secret, "" clears it
    val clientSecret: String? = null,
    val scopes: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap()
)

data class ProviderConfigView(
    val id: UUID,
    val connectorId: String,
    val clientId: String,
    val clientSecretSet: Boolean,
    val scopes: List<String>,
    val options: Map<String, String>,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

class ProviderConfigRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata
    private val columns = "id, organization_id, connector_id, client_id, client_secret_sealed, scopes, options, version, created_at, updated_at"

    suspend fun list(organizationId: UUID): List<ProviderConfig> =
        db
            .sql("SELECT $columns FROM $m.integration_provider_configs WHERE organization_id = :org ORDER BY connector_id")
            .bind("org", organizationId)
            .map { row, _ -> read(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun find(
        organizationId: UUID,
        connectorId: String
    ): ProviderConfig? =
        db
            .sql("SELECT $columns FROM $m.integration_provider_configs WHERE organization_id = :org AND connector_id = :connector")
            .bind("org", organizationId)
            .bind("connector", connectorId)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun insert(config: ProviderConfig): Boolean =
        db
            .sql(
                "INSERT INTO $m.integration_provider_configs " +
                    "(id, organization_id, connector_id, client_id, client_secret_sealed, scopes, options, created_at, updated_at) " +
                    "VALUES (:id, :org, :connector, :clientId, :secret, CAST(:scopes AS jsonb), CAST(:options AS jsonb), :now, :now) " +
                    "ON CONFLICT (organization_id, connector_id) DO NOTHING"
            ).bind("id", config.id)
            .bind("org", config.organizationId)
            .bind("connector", config.connectorId)
            .bind("clientId", config.clientId)
            .bindNullable("secret", config.clientSecretSealed)
            .bind("scopes", json.writeValueAsString(config.scopes))
            .bind("options", json.writeValueAsString(config.options))
            .bind("now", config.createdAt.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    // optimistic lock: the version the caller read must still be the stored one
    suspend fun update(
        config: ProviderConfig,
        expectedVersion: Int
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_provider_configs SET client_id = :clientId, client_secret_sealed = :secret, " +
                    "scopes = CAST(:scopes AS jsonb), options = CAST(:options AS jsonb), version = version + 1, updated_at = :now " +
                    "WHERE organization_id = :org AND id = :id AND version = :version"
            ).bind("clientId", config.clientId)
            .bindNullable("secret", config.clientSecretSealed)
            .bind("scopes", json.writeValueAsString(config.scopes))
            .bind("options", json.writeValueAsString(config.options))
            .bind("now", config.updatedAt.odt())
            .bind("org", config.organizationId)
            .bind("id", config.id)
            .bind("version", expectedVersion)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    suspend fun delete(
        organizationId: UUID,
        connectorId: String
    ): Boolean =
        db
            .sql("DELETE FROM $m.integration_provider_configs WHERE organization_id = :org AND connector_id = :connector")
            .bind("org", organizationId)
            .bind("connector", connectorId)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    private fun read(row: io.r2dbc.spi.Readable): ProviderConfig =
        ProviderConfig(
            id = row.get("id", UUID::class.java)!!,
            organizationId = row.get("organization_id", UUID::class.java)!!,
            connectorId = row.get("connector_id", String::class.java)!!,
            clientId = row.get("client_id", String::class.java)!!,
            clientSecretSealed = row.get("client_secret_sealed", String::class.java),
            scopes = json.readTree(jsonText(row, "scopes")).let { n -> (0 until n.size()).map { n.get(it).asString() } },
            options = json.readTree(jsonText(row, "options")).properties().associate { (k, v) -> k to v.asString() },
            version = row.get("version", Int::class.javaObjectType)!!,
            createdAt = row.get("created_at", java.time.OffsetDateTime::class.java)!!.toInstant(),
            updatedAt = row.get("updated_at", java.time.OffsetDateTime::class.java)!!.toInstant()
        )
}

// jsonb reads back as io.r2dbc.postgresql.codec.Json; its toString is the json text, and String works too
internal fun jsonText(
    row: io.r2dbc.spi.Readable,
    column: String
): String = row.get(column)?.let { (it as? String) ?: it.toString() } ?: "null"

internal fun DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: String?
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, String::class.java) else bind(name, value)

class ProviderConfigService(
    private val access: IntegrationsAccess,
    private val registry: ConnectorRegistry,
    private val repository: ProviderConfigRepository,
    private val cipher: CredentialCipher,
    private val clock: Clock
) {
    suspend fun list(): List<ProviderConfigView> {
        val user = access.manager()
        return repository.list(user.organizationId).map(::view)
    }

    suspend fun get(connectorId: String): ProviderConfigView {
        val user = access.manager()
        return view(repository.find(user.organizationId, connectorId) ?: throw notFound(connectorId))
    }

    // create or replace. ifMatch is the version the caller last read; a replace without it is refused
    suspend fun put(
        connectorId: String,
        request: ProviderConfigRequest,
        ifMatch: Int?
    ): ProviderConfigView {
        val user = access.manager()
        val connector = registry.require(connectorId)
        validate(request, connector.manifest.authMethods.map { it.type })
        val now = clock.instant()
        val existing = repository.find(user.organizationId, connectorId)
        if (existing == null) {
            val id = UUID.randomUUID()
            val config =
                ProviderConfig(
                    id,
                    user.organizationId,
                    connectorId,
                    request.clientId!!.trim(),
                    request.clientSecret?.takeIf { it.isNotEmpty() }?.let { cipher.seal(it, secretContext(user.organizationId, id)) },
                    request.scopes.map(String::trim),
                    request.options,
                    1,
                    now,
                    now
                )
            if (!repository.insert(config)) throw ConflictException("Provider config for '$connectorId' was created meanwhile")
            return view(repository.find(user.organizationId, connectorId)!!)
        }
        if (ifMatch == null) throw PreconditionFailedException("If-Match is required to replace a provider config")
        val secret =
            when {
                request.clientSecret == null -> existing.clientSecretSealed?.let { rotateIfOld(it, existing) }
                request.clientSecret.isEmpty() -> null
                else -> cipher.seal(request.clientSecret, secretContext(user.organizationId, existing.id))
            }
        val updated =
            existing.copy(
                clientId = request.clientId!!.trim(),
                clientSecretSealed = secret,
                scopes = request.scopes.map(String::trim),
                options = request.options,
                updatedAt = now
            )
        if (!repository.update(updated, ifMatch)) throw PreconditionFailedException("Provider config for '$connectorId' changed meanwhile")
        return view(repository.find(user.organizationId, connectorId)!!)
    }

    suspend fun delete(connectorId: String) {
        val user = access.manager()
        if (!repository.delete(user.organizationId, connectorId)) throw notFound(connectorId)
    }

    // for the oauth flow (issue #5): the clear secret, only inside the server
    suspend fun clientSecret(config: ProviderConfig): String? =
        config.clientSecretSealed?.let { cipher.open(it, secretContext(config.organizationId, config.id)) }

    private fun rotateIfOld(
        sealed: String,
        config: ProviderConfig
    ): String {
        if (!cipher.needsRotation(sealed)) return sealed
        val context = secretContext(config.organizationId, config.id)
        return cipher.seal(cipher.open(sealed, context), context)
    }

    private fun validate(
        request: ProviderConfigRequest,
        authTypes: List<AuthType>
    ) {
        val violations = mutableListOf<FieldViolation>()
        if (request.clientId.isNullOrBlank()) violations += FieldViolation("clientId", "must not be blank")
        if ((request.clientId?.length ?: 0) > 500) violations += FieldViolation("clientId", "at most 500 characters")
        if ((request.clientSecret?.length ?: 0) > 4000) violations += FieldViolation("clientSecret", "at most 4000 characters")
        if (authTypes.none { it == AuthType.OAUTH2_AUTHORIZATION_CODE || it == AuthType.OAUTH2_CLIENT_CREDENTIALS }) {
            violations += FieldViolation("connectorId", "this connector does not use an oauth app")
        }
        request.scopes.forEachIndexed { i, scope ->
            if (scope.isBlank() || scope.length > 200 || scope.any(Char::isWhitespace)) violations += FieldViolation("scopes[$i]", "not a scope")
        }
        request.options.forEach { (key, value) ->
            if (!OPTION_KEY.matches(key)) violations += FieldViolation("options.$key", "option names are lower_snake_case")
            if (value.length > 2000) violations += FieldViolation("options.$key", "at most 2000 characters")
        }
        if (violations.isNotEmpty()) throw ValidationException("Invalid provider config", violations)
    }

    private fun view(config: ProviderConfig) =
        ProviderConfigView(
            config.id,
            config.connectorId,
            config.clientId,
            config.clientSecretSealed != null,
            config.scopes,
            config.options,
            config.version,
            config.createdAt,
            config.updatedAt
        )

    private fun notFound(connectorId: String) = NotFoundException("No provider config for connector '$connectorId'")

    companion object {
        private val OPTION_KEY = Regex("^[a-z][a-z0-9_]{0,63}$")

        fun secretContext(
            organizationId: UUID,
            id: UUID
        ) = SealContext(organizationId, "integration_provider_configs", id, "client_secret_sealed")
    }
}
