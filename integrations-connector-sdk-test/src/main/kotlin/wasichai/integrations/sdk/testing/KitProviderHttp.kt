package wasichai.integrations.sdk.testing

import io.netty.buffer.Unpooled
import io.netty.handler.codec.http.HttpHeaderNames
import kotlinx.coroutines.reactor.awaitSingle
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.HttpClientResponse
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ProviderHttp
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.ProviderResponse
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock

// a plain ProviderHttp for connector tests: sends what the connector asks, adds a bearer token.
// the platform's real one also rate limits, checks hosts and redacts; tests of those live with it.
class KitProviderHttp(
    private val bearerToken: String? = "test-token"
) : ProviderHttp {
    private val client = HttpClient.create()

    override suspend fun exchange(request: ProviderRequest): ProviderResponse {
        val url = withQuery(request.url, request.query)
        val sender =
            client
                .headers { headers ->
                    bearerToken?.let { headers.set(HttpHeaderNames.AUTHORIZATION, "Bearer $it") }
                    request.contentType?.let { headers.set(HttpHeaderNames.CONTENT_TYPE, it) }
                    request.headers.forEach { (k, v) -> headers.set(k, v) }
                }.request(
                    io.netty.handler.codec.http.HttpMethod
                        .valueOf(request.method.name)
                ).uri(url)
        val receiver: HttpClient.ResponseReceiver<*> = request.body?.let { body -> sender.send(Mono.just(Unpooled.wrappedBuffer(body))) } ?: sender
        return receiver
            .responseSingle { response: HttpClientResponse, content ->
                content.asByteArray().defaultIfEmpty(ByteArray(0)).map { bytes ->
                    ProviderResponse(
                        response.status().code(),
                        response.responseHeaders().groupBy({ it.key.lowercase() }, { it.value }),
                        bytes
                    )
                }
            }.awaitSingle()
    }

    companion object {
        fun withQuery(
            url: String,
            query: Map<String, String>
        ): String {
            if (query.isEmpty()) return url
            val encoded =
                query.entries.joinToString("&") { (k, v) ->
                    URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8).replace("+", "%20")
                }
            return url + (if ('?' in url) "&" else "?") + encoded
        }
    }
}

data class TestConnectorContext(
    override val http: ProviderHttp = KitProviderHttp(),
    override val metadata: Map<String, String> = emptyMap(),
    override val connectionId: String = "test-connection",
    override val clock: Clock = Clock.systemUTC()
) : ConnectorContext
