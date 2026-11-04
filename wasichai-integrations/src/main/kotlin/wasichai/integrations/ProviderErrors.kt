package wasichai.integrations

import org.springframework.http.HttpStatus
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.RetryLaterException
import wasichai.core.common.WasichaiException
import wasichai.integrations.sdk.ConnectorException
import java.time.Duration

// the provider failed, not the caller: 502 with what kind of failure it was, never the provider's body
class ProviderFailedException(
    message: String
) : WasichaiException(HttpStatus.BAD_GATEWAY, message)

// a provider failure inside an api call, as wasichai's problem+json
object ProviderErrors {
    suspend fun <T> mapped(block: suspend () -> T): T =
        try {
            block()
        } catch (e: ConnectorException) {
            throw toHttp(e)
        }

    fun toHttp(e: ConnectorException): WasichaiException =
        when (e) {
            is ConnectorException.RateLimited ->
                RetryLaterException(
                    "The provider is rate limiting this connection",
                    e.retryAfter ?: Duration.ofSeconds(30),
                    status = HttpStatus.TOO_MANY_REQUESTS
                )
            is ConnectorException.AuthExpired -> ConflictException("The connection needs a new authorization")
            is ConnectorException.NotFound -> NotFoundException("The provider has no such resource")
            is ConnectorException.PermissionDenied -> ProviderFailedException("The provider denied access: ${e.message}")
            is ConnectorException.Transient -> ProviderFailedException("The provider is unavailable, try again later")
            is ConnectorException.Conflict -> ConflictException("The provider record changed meanwhile")
            is ConnectorException.Permanent -> ProviderFailedException("The provider refused the request: ${e.message}")
        }
}
