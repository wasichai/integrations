package wasichai.integrations.connector.jira

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode

// atlassian document format, the rich text of api v3, as plain text only: read keeps the text of every node,
// one line per block; write makes one paragraph per line. marks, lists, tables and media are not kept, so
// writing a field replaces its formatting. the manifest says so.
internal object JiraAdf {
    private val nodes = JsonNodeFactory.instance

    // blocks whose children are inline: their text joins without a separator
    private val INLINE_PARENTS = setOf("paragraph", "heading", "codeBlock")

    fun toText(doc: JsonNode): String = text(doc)

    private fun text(node: JsonNode): String =
        when (node.path("type").asString("")) {
            "text" -> node.path("text").asString("")
            "hardBreak" -> "\n"
            "mention", "emoji" -> node.path("attrs").path("text").asString(node.path("attrs").path("shortName").asString(""))
            "inlineCard", "blockCard" -> node.path("attrs").path("url").asString("")
            in INLINE_PARENTS -> node.path("content").values().joinToString("") { text(it) }
            else -> node.path("content").values().joinToString("\n") { text(it) }
        }

    fun fromText(text: String): ObjectNode {
        val content = nodes.arrayNode()
        if (text.isNotEmpty()) {
            text.split("\n").forEach { line ->
                val paragraph = nodes.objectNode().put("type", "paragraph")
                // adf refuses an empty text node: an empty line is a paragraph without content
                if (line.isNotEmpty()) {
                    paragraph.set("content", nodes.arrayNode().add(nodes.objectNode().put("type", "text").put("text", line)))
                }
                content.add(paragraph)
            }
        }
        return nodes
            .objectNode()
            .put("type", "doc")
            .put("version", 1)
            .set("content", content)
    }
}
