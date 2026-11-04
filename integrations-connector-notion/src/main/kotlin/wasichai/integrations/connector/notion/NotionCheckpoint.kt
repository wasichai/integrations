package wasichai.integrations.connector.notion

import tools.jackson.databind.node.JsonNodeFactory
import java.time.Instant

// the change feed position. since = lower bound of the current pass, cursor = where the pass stands,
// maxSeen = newest edit seen so far in it. opaque to the platform.
internal data class NotionCheckpoint(
    val since: Instant?,
    val cursor: String?,
    val maxSeen: Instant?
) {
    fun render(): String {
        val node = JsonNodeFactory.instance.objectNode()
        since?.let { node.put("since", it.toString()) }
        cursor?.let { node.put("cursor", it) }
        maxSeen?.let { node.put("maxSeen", it.toString()) }
        return node.toString()
    }

    companion object {
        fun parse(text: String?): NotionCheckpoint {
            if (text.isNullOrBlank()) return NotionCheckpoint(null, null, null)
            val node = NotionProperties.json.readTree(text)
            return NotionCheckpoint(
                node.path("since").textOrNull()?.let(Instant::parse),
                node.path("cursor").textOrNull(),
                node.path("maxSeen").textOrNull()?.let(Instant::parse)
            )
        }
    }
}
