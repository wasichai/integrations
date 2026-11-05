package wasichai.integrations.connector.salesforce.pubsub

import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.Base64

// opaque to the platform. channel: what the replay id belongs to. replay: salesforce's replay id as it sent it
// (base64), never computed. since: when the full read that started this feed began; events committed before it are
// in that read. full: the polling checkpoint while that read is still paging
internal data class CdcCheckpoint(
    val channel: String,
    val replay: ByteArray?,
    val since: Instant?,
    val full: String?
) {
    fun format(): String =
        mapper.writeValueAsString(
            mapOf(
                "channel" to channel,
                "replay" to replay?.let { Base64.getEncoder().encodeToString(it) },
                "since" to since?.toString(),
                "full" to full
            ).filterValues { it != null }
        )

    override fun equals(other: Any?): Boolean =
        other is CdcCheckpoint && channel == other.channel && replay.contentEquals(other.replay) && since == other.since && full == other.full

    override fun hashCode(): Int = listOf(channel, replay?.contentHashCode(), since, full).hashCode()

    companion object {
        private val mapper = JsonMapper.builder().build()

        // null: not a change capture checkpoint (none at all, or one the polling feed wrote)
        fun parseOrNull(text: String): CdcCheckpoint? {
            val node = runCatching { mapper.readTree(text) }.getOrNull()?.takeIf { it.isObject } ?: return null
            val channel = node.path("channel").asString(null) ?: return null
            return CdcCheckpoint(
                channel,
                node.path("replay").asString(null)?.let { Base64.getDecoder().decode(it) },
                node.path("since").asString(null)?.let(Instant::parse),
                node.path("full").asString(null)
            )
        }
    }
}
