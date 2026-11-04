package wasichai.integrations.sdk.testing

import io.netty.handler.codec.http.HttpResponseStatus
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import wasichai.integrations.sdk.HttpMethod
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

// a provider on localhost for connector tests: routes answer from handlers that may keep state,
// and every request is recorded. deterministic, no network, no real account.
class StubProviderServer : AutoCloseable {
    class Recorded(
        val method: HttpMethod,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray
    ) {
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        fun bodyText(): String = body.toString(StandardCharsets.UTF_8)
    }

    class StubResponse(
        val status: Int = 200,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap()
    ) {
        companion object {
            fun json(
                body: String,
                status: Int = 200,
                headers: Map<String, String> = emptyMap()
            ) = StubResponse(status, body.toByteArray(StandardCharsets.UTF_8), headers + ("Content-Type" to "application/json"))
        }
    }

    private class Route(
        val method: HttpMethod,
        val path: Regex,
        val handler: (Recorded, MatchResult) -> StubResponse
    )

    private val routes = CopyOnWriteArrayList<Route>()
    private val recorded = CopyOnWriteArrayList<Recorded>()
    private val server: DisposableServer =
        HttpServer
            .create()
            .host("127.0.0.1")
            .port(0)
            .handle { request, response ->
                request
                    .receive()
                    .aggregate()
                    .asByteArray()
                    .defaultIfEmpty(ByteArray(0))
                    .flatMap { body ->
                        val uri = URI.create(request.uri())
                        val call =
                            Recorded(
                                HttpMethod.valueOf(request.method().name()),
                                uri.rawPath,
                                parseQuery(uri.rawQuery),
                                request.requestHeaders().associate { it.key to it.value },
                                body
                            )
                        recorded += call
                        val answer = answer(call)
                        answer.headers.forEach { (k, v) -> response.header(k, v) }
                        response.status(HttpResponseStatus.valueOf(answer.status)).sendByteArray(Mono.just(answer.body)).then()
                    }
            }.bindNow()

    val baseUrl: String get() = "http://127.0.0.1:${server.port()}"

    val requests: List<Recorded> get() = recorded.toList()

    // later routes win, so a test can override a default answer
    fun on(
        method: HttpMethod,
        path: String,
        handler: (Recorded, MatchResult) -> StubResponse
    ) {
        routes.add(0, Route(method, Regex(path), handler))
    }

    fun onJson(
        method: HttpMethod,
        path: String,
        body: String,
        status: Int = 200
    ) = on(method, path) { _, _ -> StubResponse.json(body, status) }

    fun reset() {
        routes.clear()
        recorded.clear()
    }

    private fun answer(call: Recorded): StubResponse {
        for (route in routes) {
            if (route.method != call.method) continue
            val match = route.path.matchEntire(call.path) ?: continue
            return route.handler(call, match)
        }
        return StubResponse.json("""{"error":"no stub for ${call.method} ${call.path}"}""", 404)
    }

    override fun close() {
        server.disposeNow()
    }

    private fun parseQuery(raw: String?): Map<String, String> =
        raw
            ?.split("&")
            ?.filter { it.isNotEmpty() }
            ?.associate { part ->
                val key = part.substringBefore("=")
                val value = part.substringAfter("=", "")
                URLDecoder.decode(key, StandardCharsets.UTF_8) to URLDecoder.decode(value, StandardCharsets.UTF_8)
            } ?: emptyMap()
}
