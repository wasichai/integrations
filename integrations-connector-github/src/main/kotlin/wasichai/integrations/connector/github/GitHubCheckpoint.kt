package wasichai.integrations.connector.github

import tools.jackson.databind.node.JsonNodeFactory
import wasichai.integrations.sdk.ConnectorException
import java.time.Instant

// the change feed position, opaque to the platform. since = the `since` of the next query, page = github's page number
// inside it (only past 1 when a whole page sits in one second, see GitHubConnector.changes)
internal data class GitHubCheckpoint(
    val since: Instant?,
    val page: Int = 1
) {
    fun render(): String {
        val node = JsonNodeFactory.instance.objectNode()
        since?.let { node.put("since", it.toString()) }
        if (page > 1) node.put("page", page)
        return node.toString()
    }

    companion object {
        fun parse(text: String?): GitHubCheckpoint {
            if (text.isNullOrBlank()) return GitHubCheckpoint(null)
            return runCatching {
                val node = GitHubIssues.json.readTree(text)
                require(node.isObject)
                val page = node.path("page").asInt(1)
                require(page >= 1)
                GitHubCheckpoint(node.path("since").asString(null)?.let(Instant::parse), page)
            }.getOrElse { throw ConnectorException.Permanent("unreadable GitHub checkpoint") }
        }
    }
}
