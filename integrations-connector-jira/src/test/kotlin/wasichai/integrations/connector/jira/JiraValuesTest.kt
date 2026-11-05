package wasichai.integrations.connector.jira

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset

class JiraValuesTest {
    private val json = JsonMapper.builder().build()

    @Test
    fun `urls are https, or loopback http for a stub provider, and a pinned cloud id is a uuid`() {
        JiraConfig(siteUrl = "https://acme.atlassian.net", cloudId = JiraFixtures.CLOUD)
        JiraConfig(gatewayUrl = "http://127.0.0.1:8123")
        assertThrows<IllegalArgumentException> { JiraConfig(siteUrl = "http://acme.atlassian.net") }
        assertThrows<IllegalArgumentException> { JiraConfig(cloudId = "../x") }
    }

    @Test
    fun `adf reads as text per block and plain text writes back to the same text`() {
        val doc =
            json.readTree(
                """{"type":"doc","version":1,"content":[
                  |{"type":"heading","attrs":{"level":2},"content":[{"type":"text","text":"Title"}]},
                  |{"type":"bulletList","content":[
                  |  {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"text","text":"one"}]}]},
                  |  {"type":"listItem","content":[{"type":"paragraph","content":[{"type":"mention","attrs":{"id":"x","text":"@Ana"}}]}]}]},
                  |{"type":"paragraph","content":[{"type":"inlineCard","attrs":{"url":"https://x.example"}}]}]}
                """.trimMargin()
            )
        assertEquals("Title\none\n@Ana\nhttps://x.example", JiraAdf.toText(doc))
        listOf("", "a", "a\nb", "a\n\nb").forEach { assertEquals(it, JiraAdf.toText(JiraAdf.fromText(it))) }
    }

    @Test
    fun `jql strings escape quotes and backslashes`() {
        assertEquals("\"PROJ\"", Jql.string("PROJ"))
        assertEquals("\"a\\\" OR x = \\\"b\"", Jql.string("a\" OR x = \"b"))
        assertEquals("\"a\\\\b\"", Jql.string("a\\b"))
    }

    @Test
    fun `jira timestamps parse, format, and give a minute lower bound`() {
        assertEquals(Instant.parse("2026-11-04T12:00:00.123Z"), JiraTimes.parse("2026-11-04T12:00:00.123+0000"))
        assertEquals(Instant.parse("2026-11-04T10:00:00Z"), JiraTimes.parse("2026-11-04T12:00:00.000+0200"))
        assertEquals("2026-11-04T12:00:00.000+0000", JiraTimes.format(Instant.parse("2026-11-04T12:00:00Z")))
        assertEquals("2026-11-04 11:59", JiraTimes.jqlLowerBound(Instant.parse("2026-11-04T12:00:00Z"), ZoneOffset.UTC))
    }
}
