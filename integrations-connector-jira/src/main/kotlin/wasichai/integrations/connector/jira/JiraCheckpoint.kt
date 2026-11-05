package wasichai.integrations.connector.jira

import tools.jackson.databind.node.JsonNodeFactory
import wasichai.integrations.sdk.ConnectorException
import java.time.Instant

// the change feed position, opaque to the platform. since = lower bound of the current pass, token = jira's
// nextPageToken inside it, maxSeen = newest `updated` seen so far in it
internal data class JiraCheckpoint(
    val since: Instant?,
    val token: String?,
    val maxSeen: Instant?
) {
    fun render(): String {
        val node = JsonNodeFactory.instance.objectNode()
        since?.let { node.put("since", it.toString()) }
        token?.let { node.put("token", it) }
        maxSeen?.let { node.put("maxSeen", it.toString()) }
        return node.toString()
    }

    companion object {
        fun parse(text: String?): JiraCheckpoint {
            if (text.isNullOrBlank()) return JiraCheckpoint(null, null, null)
            val node =
                runCatching { JiraFields.json.readTree(text) }.getOrNull()?.takeIf { it.isObject }
                    ?: throw ConnectorException.Permanent("unreadable Jira checkpoint")

            fun instant(key: String) = node.path(key).asString(null)?.let(Instant::parse)
            return JiraCheckpoint(instant("since"), node.path("token").asString(null), instant("maxSeen"))
        }
    }
}
