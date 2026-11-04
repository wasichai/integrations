package wasichai.integrations

import io.micrometer.core.instrument.MeterRegistry
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.HttpErrors
import wasichai.integrations.sdk.ProviderHttp
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.ProviderResponse
import wasichai.integrations.sdk.RateLimitHint
import java.time.Clock
import java.time.Duration

// a connector's ProviderHttp for one connection: adds the credentials, and on a 401 refreshes an oauth
// token once and tries again. what a connector sends never carries a secret of its own.
class ConnectionHttp(
    private val connection: Connection,
    private val credentials: ConnectionCredentials,
    private val http: PlatformHttp,
    private val limits: RateLimits,
    private val hint: RateLimitHint?,
    private val meters: MeterRegistry
) : ProviderHttp {
    override suspend fun exchange(request: ProviderRequest): ProviderResponse {
        val headers = credentials.headers(connection)
        val response = send(request.copy(headers = request.headers + headers))
        if (response.status != 401 || !credentials.canRefresh(connection)) return response
        val stale = headers["Authorization"]?.removePrefix("Bearer ")
        val fresh = credentials.refresh(connection, stale)
        return send(request.copy(headers = request.headers + ("Authorization" to "Bearer ${fresh.orEmpty()}")))
    }

    // the connector's declared rate first; a 429 pauses the connection for what the provider asked
    private suspend fun send(request: ProviderRequest): ProviderResponse {
        val key = connection.id.toString()
        limits.acquire(key, hint)
        val started = System.nanoTime()
        val response =
            try {
                http.send(request)
            } catch (e: Exception) {
                record(request, "io_error", started)
                throw e
            }
        record(request, "${response.status / 100}xx", started)
        if (response.status == 429) HttpErrors.retryAfter(response)?.let { limits.pause(key, hint, it) }
        return response
    }

    // tagged by connector and outcome, never by tenant or url: bounded cardinality, nothing personal
    private fun record(
        request: ProviderRequest,
        status: String,
        started: Long
    ) {
        meters
            .timer("integrations.http.requests", "connector", connection.connectorId, "method", request.method.name, "status", status)
            .record(Duration.ofNanos(System.nanoTime() - started))
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
    private val registry: ConnectorRegistry,
    private val limits: RateLimits,
    private val meters: MeterRegistry,
    private val clock: Clock
) {
    fun forConnection(connection: Connection): ConnectorContext {
        val hint = registry.find(connection.connectorId)?.manifest?.rateLimit
        return PlatformConnectorContext(
            connection.id.toString(),
            ConnectionHttp(connection, credentials, http, limits, hint, meters),
            connection.metadata,
            clock
        )
    }

    fun pending(
        headers: Map<String, String>,
        metadata: Map<String, String>
    ): ConnectorContext = PlatformConnectorContext("pending", StaticHttp(headers, http), metadata, clock)
}
