package wasichai.integrations

import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ProviderHttp
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.ProviderResponse
import java.time.Clock

// a connector's ProviderHttp for one connection: adds the credentials, and on a 401 refreshes an oauth
// token once and tries again. what a connector sends never carries a secret of its own.
class ConnectionHttp(
    private val connection: Connection,
    private val credentials: ConnectionCredentials,
    private val http: PlatformHttp
) : ProviderHttp {
    override suspend fun exchange(request: ProviderRequest): ProviderResponse {
        val headers = credentials.headers(connection)
        val response = http.send(request.copy(headers = request.headers + headers))
        if (response.status != 401 || !credentials.canRefresh(connection)) return response
        val stale = headers["Authorization"]?.removePrefix("Bearer ")
        val fresh = credentials.refresh(connection, stale)
        return http.send(request.copy(headers = request.headers + ("Authorization" to "Bearer ${fresh.orEmpty()}")))
    }
}

// before a connection exists (connect, reconnect): fixed headers, no refresh
class StaticHttp(
    private val headers: Map<String, String>,
    private val http: PlatformHttp
) : ProviderHttp {
    override suspend fun exchange(request: ProviderRequest): ProviderResponse = http.send(request.copy(headers = request.headers + headers))
}

data class PlatformConnectorContext(
    override val connectionId: String,
    override val http: ProviderHttp,
    override val metadata: Map<String, String>,
    override val clock: Clock
) : ConnectorContext

class ConnectorContexts(
    private val credentials: ConnectionCredentials,
    private val http: PlatformHttp,
    private val clock: Clock
) {
    fun forConnection(connection: Connection): ConnectorContext =
        PlatformConnectorContext(connection.id.toString(), ConnectionHttp(connection, credentials, http), connection.metadata, clock)

    fun pending(
        headers: Map<String, String>,
        metadata: Map<String, String>
    ): ConnectorContext = PlatformConnectorContext("pending", StaticHttp(headers, http), metadata, clock)
}
