package wasichai.integrations

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.WasichaiSchemas
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.UUID

data class OAuthState(
    val id: UUID,
    val organizationId: UUID,
    val userId: UUID,
    val connectorId: String,
    val verifierSealed: String?,
    val redirectUri: String,
    val connectionName: String
)

class OAuthStateRepository(
    private val db: DatabaseClient,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata

    suspend fun insert(
        state: OAuthState,
        stateValue: String,
        expiresAt: Instant
    ) {
        db
            .sql(
                "INSERT INTO $m.integration_oauth_states (id, organization_id, user_id, connector_id, state_hash, verifier_sealed, redirect_uri, " +
                    "connection_name, expires_at) VALUES (:id, :org, :user, :connector, :hash, :verifier, :redirect, :name, :expires)"
            ).bind("id", state.id)
            .bind("org", state.organizationId)
            .bind("user", state.userId)
            .bind("connector", state.connectorId)
            .bind("hash", hash(stateValue))
            .bindNullable("verifier", state.verifierSealed)
            .bind("redirect", state.redirectUri)
            .bind("name", state.connectionName)
            .bind("expires", expiresAt.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // single use: of two callbacks with one state only the first gets the row; an expired one gets nothing
    suspend fun consume(
        stateValue: String,
        now: Instant
    ): OAuthState? =
        db
            .sql(
                "UPDATE $m.integration_oauth_states SET consumed_at = :now " +
                    "WHERE state_hash = :hash AND consumed_at IS NULL AND expires_at > :now " +
                    "RETURNING id, organization_id, user_id, connector_id, verifier_sealed, redirect_uri, connection_name"
            ).bind("now", now.odt())
            .bind("hash", hash(stateValue))
            .map { row, _ ->
                OAuthState(
                    row.get("id", UUID::class.java)!!,
                    row.get("organization_id", UUID::class.java)!!,
                    row.get("user_id", UUID::class.java)!!,
                    row.get("connector_id", String::class.java)!!,
                    row.get("verifier_sealed", String::class.java),
                    row.get("redirect_uri", String::class.java)!!,
                    row.get("connection_name", String::class.java)!!
                )
            }.one()
            .awaitFirstOrNull()

    // old rows are useless; called on every start, cheap thanks to the expiry index
    suspend fun purge(before: Instant) {
        db
            .sql("DELETE FROM $m.integration_oauth_states WHERE expires_at < :before")
            .bind("before", before.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    companion object {
        fun hash(value: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
    }
}
