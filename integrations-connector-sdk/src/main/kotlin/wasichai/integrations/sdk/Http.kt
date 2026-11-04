package wasichai.integrations.sdk

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

// the only way a connector reaches its provider. the platform adds credentials, rate limits,
// ssrf checks, redaction and metrics; the connector never sees a secret.
interface ProviderHttp {
    suspend fun exchange(request: ProviderRequest): ProviderResponse
}

enum class HttpMethod {
    GET,
    POST,
    PUT,
    PATCH,
    DELETE
}

data class ProviderRequest(
    val method: HttpMethod,
    // absolute url; the platform checks it against the connection's allowed hosts
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val contentType: String? = null
) {
    companion object {
        private val json = JsonMapper.builder().build()

        fun json(
            method: HttpMethod,
            url: String,
            body: Any,
            headers: Map<String, String> = emptyMap()
        ) = ProviderRequest(method, url, headers, body = json.writeValueAsBytes(body), contentType = "application/json")
    }

    override fun equals(other: Any?): Boolean =
        other is ProviderRequest &&
            method == other.method &&
            url == other.url &&
            headers == other.headers &&
            query == other.query &&
            contentType == other.contentType &&
            java.util.Arrays.equals(body, other.body)

    override fun hashCode(): Int = listOf(method, url, headers, query, contentType, body?.contentHashCode()).hashCode()
}

class ProviderResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray
) {
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    fun json(): JsonNode = mapper.readTree(body)

    val successful: Boolean get() = status in 200..299

    companion object {
        private val mapper = JsonMapper.builder().build()
    }
}

// default mapping of an unsuccessful response; connectors refine it with provider error codes
object HttpErrors {
    fun classify(response: ProviderResponse): ConnectorException {
        val text = "provider answered ${response.status}"
        return when (response.status) {
            401 -> ConnectorException.AuthExpired(text)
            403 -> ConnectorException.PermissionDenied(text)
            404, 410 -> ConnectorException.NotFound(text)
            409, 412 -> ConnectorException.Conflict(text)
            429 -> ConnectorException.RateLimited(text, retryAfter(response))
            in 500..599 -> ConnectorException.Transient(text)
            else -> ConnectorException.Permanent(text)
        }
    }

    // Retry-After in seconds. the http-date form is rare among apis and read as unknown
    fun retryAfter(response: ProviderResponse): Duration? =
        response
            .header("Retry-After")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?.let(Duration::ofSeconds)
}
