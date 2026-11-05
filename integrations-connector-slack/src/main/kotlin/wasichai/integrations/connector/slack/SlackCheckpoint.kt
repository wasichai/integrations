package wasichai.integrations.connector.slack

import tools.jackson.databind.node.JsonNodeFactory
import wasichai.integrations.sdk.ConnectorException

// the change feed position, opaque to the platform. oldest = the `oldest` of the current pass; cursor = slack's cursor
// inside that pass (history pages newest first); high = the newest ts the pass has seen so far
internal data class SlackCheckpoint(
    val oldest: String?,
    val cursor: String? = null,
    val high: String? = null
) {
    fun render(): String {
        val node = JsonNodeFactory.instance.objectNode()
        oldest?.let { node.put("oldest", it) }
        cursor?.let { node.put("cursor", it) }
        high?.let { node.put("high", it) }
        return node.toString()
    }

    companion object {
        fun parse(text: String?): SlackCheckpoint {
            if (text.isNullOrBlank()) return SlackCheckpoint(null)
            return runCatching {
                val node = SlackMessages.json.readTree(text)
                require(node.isObject)
                val oldest = node.path("oldest").asString(null)
                val high = node.path("high").asString(null)
                require(oldest == null || SlackTs.isTs(oldest))
                require(high == null || SlackTs.isTs(high))
                SlackCheckpoint(oldest, node.path("cursor").asString(null)?.takeIf { it.isNotEmpty() }, high)
            }.getOrElse { throw ConnectorException.Permanent("unreadable Slack checkpoint") }
        }
    }
}
