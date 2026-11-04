package wasichai.integrations

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.sdk.AuthType
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

enum class ConnectionStatus {
    ACTIVE,
    NEEDS_REAUTH,
    DISABLED,
    ERROR
}

enum class ConnectionHealth {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    FAILING
}

data class Connection(
    val id: UUID,
    val organizationId: UUID,
    val connectorId: String,
    val name: String,
    val providerAccountId: String?,
    val displayName: String?,
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

// what a connection authenticates with. sealed as one json value; never leaves the server
data class ConnectionSecrets(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val apiKey: String? = null,
    val username: String? = null,
    val password: String? = null
)

data class StoredCredentials(
    val secrets: ConnectionSecrets,
    val accessExpiresAt: Instant?
)

class ConnectionRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    private val cipher: CredentialCipher,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata
    private val columns =
        "id, organization_id, connector_id, name, provider_account_id, display_name, status, auth_type, " +
            "scopes::text AS scopes, metadata::text AS metadata, health, " +
            "last_validated_at, last_error, version, created_at, updated_at"

    suspend fun list(organizationId: UUID): List<Connection> =
        db
            .sql("SELECT $columns FROM $m.integration_connections WHERE organization_id = :org ORDER BY connector_id, name, id")
            .bind("org", organizationId)
            .map { row, _ -> read(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun find(
        organizationId: UUID,
        id: UUID
    ): Connection? =
        db
            .sql("SELECT $columns FROM $m.integration_connections WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun findByAccount(
        organizationId: UUID,
        connectorId: String,
        providerAccountId: String
    ): Connection? =
        db
            .sql(
                "SELECT $columns FROM $m.integration_connections " +
                    "WHERE organization_id = :org AND connector_id = :connector AND provider_account_id = :account"
            ).bind("org", organizationId)
            .bind("connector", connectorId)
            .bind("account", providerAccountId)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun insert(
        connection: Connection,
        createdBy: UUID?
    ) {
        db
            .sql(
                "INSERT INTO $m.integration_connections (id, organization_id, connector_id, name, provider_account_id, display_name, status, " +
                    "auth_type, scopes, metadata, health, last_validated_at, created_by, created_at, updated_at) VALUES (:id, :org, :connector, :name, " +
                    ":account, :display, :status, :auth, CAST(:scopes AS jsonb), CAST(:metadata AS jsonb), :health, :validated, :createdBy, :now, :now)"
            ).bind("id", connection.id)
            .bind("org", connection.organizationId)
            .bind("connector", connection.connectorId)
            .bind("name", connection.name)
            .bindNullable("account", connection.providerAccountId)
            .bindNullable("display", connection.displayName)
            .bind("status", connection.status.name)
            .bind("auth", connection.authType.name)
            .bind("scopes", json.writeValueAsString(connection.scopes))
            .bind("metadata", json.writeValueAsString(connection.metadata))
            .bind("health", connection.health.name)
            .let { spec -> connection.lastValidatedAt?.let { spec.bind("validated", it.odt()) } ?: spec.bindNull("validated", OffsetDateTime::class.java) }
            .let { spec -> createdBy?.let { spec.bind("createdBy", it) } ?: spec.bindNull("createdBy", UUID::class.java) }
            .bind("now", connection.createdAt.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // a reconnect of the same account: new scopes, metadata and status; the id and the syncs on it stay
    suspend fun reauthorized(
        connection: Connection,
        now: Instant
    ) {
        db
            .sql(
                "UPDATE $m.integration_connections SET status = :status, scopes = CAST(:scopes AS jsonb), metadata = CAST(:metadata AS jsonb), " +
                    "display_name = :display, health = :health, last_validated_at = :now, last_error = NULL, version = version + 1, updated_at = :now " +
                    "WHERE organization_id = :org AND id = :id"
            ).bind("status", ConnectionStatus.ACTIVE.name)
            .bind("scopes", json.writeValueAsString(connection.scopes))
            .bind("metadata", json.writeValueAsString(connection.metadata))
            .bindNullable("display", connection.displayName)
            .bind("health", ConnectionHealth.HEALTHY.name)
            .bind("now", now.odt())
            .bind("org", connection.organizationId)
            .bind("id", connection.id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun markValidated(
        organizationId: UUID,
        id: UUID,
        now: Instant
    ) = setState(organizationId, id, null, ConnectionHealth.HEALTHY, null, now, validated = true)

    suspend fun markFailed(
        organizationId: UUID,
        id: UUID,
        status: ConnectionStatus?,
        health: ConnectionHealth,
        error: String,
        now: Instant
    ) = setState(organizationId, id, status, health, error.take(1000), now, validated = false)

    private suspend fun setState(
        organizationId: UUID,
        id: UUID,
        status: ConnectionStatus?,
        health: ConnectionHealth,
        error: String?,
        now: Instant,
        validated: Boolean
    ) {
        db
            .sql(
                "UPDATE $m.integration_connections SET " +
                    // a good check clears a past ERROR; NEEDS_REAUTH and DISABLED stay until a person acts
                    (if (validated) "status = CASE WHEN status = 'ERROR' THEN 'ACTIVE' ELSE status END, last_validated_at = :now, " else "") +
                    (if (!validated) "status = COALESCE(:status, status), " else "") +
                    "health = :health, last_error = :error, updated_at = :now WHERE organization_id = :org AND id = :id"
            ).let { spec -> if (validated) spec else spec.bindNullable("status", status?.name) }
            .bind("health", health.name)
            .bindNullable("error", error)
            .bind("now", now.odt())
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM $m.integration_connections WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() > 0

    suspend fun saveCredentials(
        organizationId: UUID,
        connectionId: UUID,
        credentials: StoredCredentials,
        now: Instant,
        refreshed: Boolean = false
    ) {
        val sealed = cipher.seal(json.writeValueAsString(credentials.secrets), credentialsContext(organizationId, connectionId))
        db
            .sql(
                "INSERT INTO $m.integration_connection_credentials (connection_id, organization_id, sealed, access_expires_at, refreshed_at, updated_at) " +
                    "VALUES (:id, :org, :sealed, :expires, :refreshed, :now) ON CONFLICT (connection_id) DO UPDATE SET sealed = EXCLUDED.sealed, " +
                    "access_expires_at = EXCLUDED.access_expires_at, refreshed_at = EXCLUDED.refreshed_at, updated_at = EXCLUDED.updated_at"
            ).bind("id", connectionId)
            .bind("org", organizationId)
            .bind("sealed", sealed)
            .let { spec -> credentials.accessExpiresAt?.let { spec.bind("expires", it.odt()) } ?: spec.bindNull("expires", OffsetDateTime::class.java) }
            .let { spec -> if (refreshed) spec.bind("refreshed", now.odt()) else spec.bindNull("refreshed", OffsetDateTime::class.java) }
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // forUpdate inside a transaction serialises refreshes of one connection across replicas
    suspend fun credentials(
        organizationId: UUID,
        connectionId: UUID,
        forUpdate: Boolean = false
    ): StoredCredentials? =
        db
            .sql(
                "SELECT sealed, access_expires_at FROM $m.integration_connection_credentials " +
                    "WHERE organization_id = :org AND connection_id = :id" + (if (forUpdate) " FOR UPDATE" else "")
            ).bind("org", organizationId)
            .bind("id", connectionId)
            .map { row, _ -> row.get("sealed", String::class.java)!! to row.get("access_expires_at", OffsetDateTime::class.java)?.toInstant() }
            .one()
            .awaitFirstOrNull()
            ?.let { (sealed, expires) ->
                StoredCredentials(json.readValue(cipher.open(sealed, credentialsContext(organizationId, connectionId)), ConnectionSecrets::class.java), expires)
            }

    private fun read(row: Readable): Connection =
        Connection(
            id = row.get("id", UUID::class.java)!!,
            organizationId = row.get("organization_id", UUID::class.java)!!,
            connectorId = row.get("connector_id", String::class.java)!!,
            name = row.get("name", String::class.java)!!,
            providerAccountId = row.get("provider_account_id", String::class.java),
            displayName = row.get("display_name", String::class.java),
            status = ConnectionStatus.valueOf(row.get("status", String::class.java)!!),
            authType = AuthType.valueOf(row.get("auth_type", String::class.java)!!),
            scopes = json.readTree(jsonText(row, "scopes")).let { n -> (0 until n.size()).map { n.get(it).asString() } },
            metadata = json.readTree(jsonText(row, "metadata")).properties().associate { (k, v) -> k to v.asString() },
            health = ConnectionHealth.valueOf(row.get("health", String::class.java)!!),
            lastValidatedAt = row.get("last_validated_at", OffsetDateTime::class.java)?.toInstant(),
            lastError = row.get("last_error", String::class.java),
            version = row.get("version", Int::class.javaObjectType)!!,
            createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
            updatedAt = row.get("updated_at", OffsetDateTime::class.java)!!.toInstant()
        )

    companion object {
        fun credentialsContext(
            organizationId: UUID,
            connectionId: UUID
        ) = SealContext(organizationId, "integration_connection_credentials", connectionId, "sealed")
    }
}
