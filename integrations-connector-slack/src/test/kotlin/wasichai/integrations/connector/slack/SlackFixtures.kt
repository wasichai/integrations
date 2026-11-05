package wasichai.integrations.connector.slack

import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.testing.StubProviderServer

// payload shapes from slack's web api method reference (auth.test, conversations.list/info/history, chat.*) and its
// events api docs, trimmed to what the connector reads plus a few fields it must ignore
internal object SlackFixtures {
    const val CHANNEL = "C012AB3CD"

    val authTest =
        """
        {"ok":true,"url":"https://subarachnoid.slack.com/","team":"Subarachnoid Workspace","user":"grace","team_id":"T12345678",
         "user_id":"W12345678","bot_id":"B0123ABCD","is_enterprise_install":false}
        """.trimIndent()

    fun channel(
        id: String = CHANNEL,
        name: String = "general",
        member: Boolean = true
    ) = """
        {"id":"$id","name":"$name","is_channel":true,"is_group":false,"is_im":false,"created":1449252889,"is_archived":false,
         "is_general":true,"is_private":false,"is_member":$member,"topic":{"value":"","creator":"","last_set":0},
         "purpose":{"value":"This channel is for team-wide communication.","creator":"","last_set":0},"num_members":4}
        """.trimIndent()

    fun channels(
        items: List<String>,
        next: String = ""
    ) = """{"ok":true,"channels":${items.joinToString(",", "[", "]")},"response_metadata":{"next_cursor":"$next"}}"""

    fun message(
        ts: String = "1512085950.000216",
        text: String = "Hello world",
        edited: String? = null,
        subtype: String? = null,
        thread: String? = null,
        replies: Int? = null
    ) = """
        {"type":"message","user":"U012AB3CDE","text":"$text","ts":"$ts",
         ${subtype?.let { "\"subtype\":\"$it\"," } ?: ""}
         ${edited?.let { "\"edited\":{\"user\":\"U012AB3CDE\",\"ts\":\"$it\"}," } ?: ""}
         ${thread?.let { "\"thread_ts\":\"$it\"," } ?: ""}
         ${replies?.let { "\"reply_count\":$it," } ?: ""}
         "blocks":[{"type":"rich_text","block_id":"Eit","elements":[{"type":"rich_text_section","elements":[{"type":"text","text":"$text"}]}]}],
         "team":"T12345678","reactions":[{"name":"+1","users":["U012AB3CDE"],"count":1}]}
        """.trimIndent()

    fun history(
        messages: List<String>,
        next: String = ""
    ) = """
        {"ok":true,"messages":${messages.joinToString(",", "[", "]")},"has_more":${next.isNotEmpty()},"pin_count":0,
         "response_metadata":{"next_cursor":"$next"}}
        """.trimIndent()

    fun error(
        code: String,
        extra: String = ""
    ) = """{"ok":false,"error":"$code"$extra}"""

    fun StubProviderServer.slackBasics() {
        onJson(HttpMethod.GET, "/auth.test", authTest)
        onJson(HttpMethod.GET, "/conversations.list", channels(listOf(channel())))
        onJson(HttpMethod.GET, "/conversations.info", """{"ok":true,"channel":${channel()}}""")
    }
}
