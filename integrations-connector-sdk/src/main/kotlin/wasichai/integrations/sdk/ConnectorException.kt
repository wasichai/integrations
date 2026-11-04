package wasichai.integrations.sdk

import java.time.Duration

// every failure a connector reports. retryable says whether the platform may try again later
sealed class ConnectorException(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    class Transient(
        message: String,
        cause: Throwable? = null
    ) : ConnectorException(message, true, cause)

    class RateLimited(
        message: String,
        val retryAfter: Duration?
    ) : ConnectorException(message, true)

    // the credentials no longer work: refresh once, then the connection needs the user
    class AuthExpired(
        message: String
    ) : ConnectorException(message, true)

    class PermissionDenied(
        message: String
    ) : ConnectorException(message, false)

    class NotFound(
        message: String
    ) : ConnectorException(message, false)

    // the record changed since the version the write expected
    class Conflict(
        message: String,
        val currentVersion: String? = null
    ) : ConnectorException(message, false)

    class Permanent(
        message: String,
        val details: Map<String, String> = emptyMap()
    ) : ConnectorException(message, false)
}
