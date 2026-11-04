package wasichai.integrations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import org.springframework.http.HttpHeaders
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import wasichai.integrations.autoconfigure.IntegrationsProperties
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.ProviderResponse
import java.net.InetAddress
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException

// every call to a provider leaves through here: one checked url, one timeout, one size cap.
// credentials are added by the caller (ConnectionHttp), never logged here.
class PlatformHttp(
    private val properties: IntegrationsProperties.Http
) {
    // own builder: boot 4 hands out no WebClient.Builder bean, and no shared defaults is the point
    private val client =
        WebClient
            .builder()
            .codecs { it.defaultCodecs().maxInMemorySize(properties.maxResponseBytes) }
            .build()

    suspend fun send(request: ProviderRequest): ProviderResponse {
        val uri = withQuery(request.url, request.query)
        checkTarget(uri)
        return try {
            client
                .method(
                    org.springframework.http.HttpMethod
                        .valueOf(request.method.name)
                ).uri(uri)
                .headers { headers ->
                    request.headers.forEach { (k, v) -> headers.set(k, v) }
                    request.contentType?.let { headers.set(HttpHeaders.CONTENT_TYPE, it) }
                }.let { spec -> request.body?.let { spec.bodyValue(it) } ?: spec }
                .exchangeToMono { response ->
                    response.bodyToMono(ByteArray::class.java).defaultIfEmpty(ByteArray(0)).map { body ->
                        ProviderResponse(
                            response.statusCode().value(),
                            response.headers().asHttpHeaders().headerSet().associate {
                                it.key.lowercase() to
                                    it.value
                            },
                            body
                        )
                    }
                }.timeout(properties.timeout)
                .awaitSingle()
        } catch (e: WebClientRequestException) {
            throw ConnectorException.Transient("provider unreachable: ${e.rootCause?.javaClass?.simpleName ?: e.javaClass.simpleName}", e)
        } catch (e: TimeoutException) {
            throw ConnectorException.Transient("provider did not answer within ${properties.timeout}", e)
        }
    }

    // only http(s), only public addresses. dns is resolved here, off the event loop: a name pointing at
    // 127.0.0.1 is the whole attack. the resolve-then-connect gap (dns rebinding) is noted in ADR-0011.
    suspend fun checkTarget(uri: URI) {
        if (uri.scheme !in SCHEMES || uri.host.isNullOrBlank()) throw ConnectorException.Permanent("provider url must be absolute http(s)")
        if (uri.userInfo != null) throw ConnectorException.Permanent("provider url must not carry credentials")
        if (properties.allowPrivateAddresses) return
        val addresses =
            withContext(Dispatchers.IO) {
                runCatching {
                    InetAddress
                        .getAllByName(
                            uri.host
                        ).toList()
                }.getOrElse { throw ConnectorException.Transient("host ${uri.host} does not resolve") }
            }
        val blocked =
            addresses.firstOrNull {
                it.isLoopbackAddress || it.isSiteLocalAddress || it.isLinkLocalAddress || it.isAnyLocalAddress ||
                    it.isMulticastAddress
            }
        if (blocked != null) throw ConnectorException.PermissionDenied("host ${uri.host} resolves to a private address")
    }

    companion object {
        private val SCHEMES = setOf("http", "https")

        fun withQuery(
            url: String,
            query: Map<String, String>
        ): URI {
            if (query.isEmpty()) return URI.create(url)
            val encoded = query.entries.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
            return URI.create(url + (if ('?' in url) "&" else "?") + encoded)
        }

        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        fun form(values: Map<String, String?>): ByteArray =
            values.entries
                .filter { it.value != null }
                .joinToString("&") { (k, v) -> URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8) }
                .toByteArray(StandardCharsets.UTF_8)
    }
}
