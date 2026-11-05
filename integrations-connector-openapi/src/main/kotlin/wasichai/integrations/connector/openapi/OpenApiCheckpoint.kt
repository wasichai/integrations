package wasichai.integrations.connector.openapi

import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import wasichai.integrations.sdk.ConnectorException
import java.time.Instant

// the change feed position, opaque to the platform. since = the updated-since value of the running pass,
// cursor = where the pass is in its pages, newest = the newest updated-at the pass has seen so far
internal data class OpenApiCheckpoint(
    val since: Instant?,
    val cursor: String? = null,
    val newest: Instant? = null
) {
    fun render(): String {
        val node = JsonNodeFactory.instance.objectNode()
        since?.let { node.put("since", it.toString()) }
        cursor?.let { node.put("cursor", it) }
        newest?.let { node.put("newest", it.toString()) }
        return node.toString()
    }

    companion object {
        private val json = JsonMapper.builder().build()

        fun parse(text: String?): OpenApiCheckpoint {
            if (text.isNullOrBlank()) return OpenApiCheckpoint(null)
            return runCatching {
                val node = json.readTree(text)
                require(node.isObject)
                OpenApiCheckpoint(
                    node.path("since").asString(null)?.let(Instant::parse),
                    node.path("cursor").asString(null),
                    node.path("newest").asString(null)?.let(Instant::parse)
                )
            }.getOrElse { throw ConnectorException.Permanent("unreadable OpenAPI checkpoint") }
        }
    }
}
