package wasichai.integrations.it

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.Recorded
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import java.math.BigDecimal
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// a notion workspace in memory, answering the api version the connector pins (2025-09-03): one data source of
// accounts, its pages, search, the oauth app. last_edited_time is rounded down to the minute like notion's,
// so the change feed sees the same page twice. deterministic: no clock but the wall clock, no randomness in answers.
class NotionStub : AutoCloseable {
    class Page(
        val id: String,
        val createdTime: Instant,
        @Volatile var properties: ObjectNode,
        @Volatile var lastEdited: Instant,
        @Volatile var inTrash: Boolean,
        // a tie breaker for pages edited in the same minute: the order they were edited in
        @Volatile var seq: Long
    )

    val server = StubProviderServer()
    private val json = JsonMapper.builder().build()
    private val nodes = JsonNodeFactory.instance
    private val pages = ConcurrentHashMap<String, Page>()
    private val seq = AtomicLong()
    private val codes = ConcurrentHashMap<String, String>()
    private val tokens = ConcurrentHashMap.newKeySet<String>()
    private val throttled = AtomicInteger()

    @Volatile
    private var retryAfterSeconds = 1

    // writes made through the api (the connector), not by a person in the stub
    val apiWrites = AtomicInteger()

    val baseUrl: String get() = server.baseUrl
    val clientId = "notion-client-id"
    val clientSecret = "notion-client-secret"
    val workspaceId = "ws-0001"
    val botId = "b0700000-0000-4000-8000-000000000001"
    val dataSourceId = "2f1b7c3e-1d4a-4b6f-9a2e-6c0d1e2f3a4b"

    init {
        routes()
    }

    // ---- what a person does in notion

    fun addPage(
        name: String,
        industry: String? = null,
        employees: Int? = null
    ): String {
        val now = Instant.now()
        val id = UUID.randomUUID().toString()
        val props = nodes.objectNode()
        props.set("Name", title(name))
        props.set("Industry", select(industry))
        props.set("Employees", number(employees?.toBigDecimal()))
        props.set("Notes", richText(""))
        pages[id] = Page(id, now, props, minute(now), false, seq.incrementAndGet())
        return id
    }

    fun edit(
        id: String,
        name: String? = null,
        industry: String? = null,
        employees: Int? = null
    ) {
        val page = pages.getValue(id)
        val props = page.properties.deepCopy()
        name?.let { props.set("Name", title(it)) }
        industry?.let { props.set("Industry", select(it)) }
        employees?.let { props.set("Employees", number(it.toBigDecimal())) }
        touch(page, props)
    }

    fun page(id: String): Page? = pages[id]

    fun live(): List<Page> = pages.values.filterNot { it.inTrash }.sortedBy { it.seq }

    fun byName(name: String): Page? = live().firstOrNull { name(it) == name }

    fun name(page: Page): String =
        page.properties
            .path("Name")
            .path("title")
            .items()
            .joinToString("") { it.path("plain_text").asString() }

    fun industry(page: Page): String? =
        page.properties
            .path("Industry")
            .path("select")
            .takeIf { it.isObject }
            ?.path("name")
            ?.asString()

    fun employees(page: Page): BigDecimal? =
        page.properties
            .path("Employees")
            .path("number")
            .takeIf { it.isNumber }
            ?.decimalValue()

    // the next n api calls answer 429 rate_limited with Retry-After
    fun throttle(
        times: Int,
        retryAfter: Int = 1
    ) {
        retryAfterSeconds = retryAfter
        throttled.set(times)
    }

    // by method name: tests hold spring's HttpMethod, the stub server the sdk's
    fun requests(
        method: String,
        path: Regex
    ): List<Recorded> = server.requests.filter { it.method.name == method && path.matches(it.path) }

    // ---- the api

    private fun routes() {
        // the browser at notion's consent screen: the person allows, notion sends it back with a code
        server.on(HttpMethod.GET, "/v1/oauth/authorize") { call, _ ->
            val redirect = call.query["redirect_uri"]
            if (call.query["client_id"] != clientId || redirect == null || call.query["response_type"] != "code") {
                return@on error(400, "invalid_request", "bad authorize request")
            }
            val code = "notion-code-" + UUID.randomUUID()
            codes[code] = redirect
            val location = redirect + (if ('?' in redirect) "&" else "?") + "code=$code&state=" + enc(call.query["state"].orEmpty())
            StubResponse(302, headers = mapOf("Location" to location))
        }
        server.on(HttpMethod.POST, "/v1/oauth/token") { call, _ ->
            val basic = call.header("Authorization")?.removePrefix("Basic ")?.let { String(Base64.getDecoder().decode(it)) }
            if (basic != "$clientId:$clientSecret") return@on oauthError(401, "invalid_client")
            val form = form(call.bodyText())
            if (form["grant_type"] != "authorization_code") return@on oauthError(400, "unsupported_grant_type")
            val redirect = codes.remove(form["code"].orEmpty()) ?: return@on oauthError(400, "invalid_grant")
            if (redirect != form["redirect_uri"]) return@on oauthError(400, "invalid_grant")
            val token = "ntn_" + UUID.randomUUID().toString().replace("-", "")
            tokens += token
            StubResponse.json(
                """{"access_token":"$token","token_type":"bearer","bot_id":"$botId","workspace_id":"$workspaceId",""" +
                    """"workspace_name":"Stub Workspace","workspace_icon":null,"owner":{"type":"user","user":{"object":"user","id":"u-1"}},""" +
                    """"duplicated_template_id":null,"request_id":"${UUID.randomUUID()}"}"""
            )
        }
        api(HttpMethod.GET, "/v1/users/me") { _, _ ->
            StubResponse.json(
                """{"object":"user","id":"$botId","type":"bot","name":"Wasichai","bot":{"owner":{"type":"workspace","workspace":true},""" +
                    """"workspace_name":"Stub Workspace","workspace_id":"$workspaceId"}}"""
            )
        }
        api(HttpMethod.POST, "/v1/search") { call, _ ->
            val body = json.readTree(call.bodyText())
            val filter = body.path("filter")
            val results = nodes.arrayNode()
            if (filter.isMissingNode || filter.path("value").asString() == "data_source") results.add(dataSource())
            val page =
                nodes
                    .objectNode()
                    .put("object", "list")
                    .put("has_more", false)
                    .putNull("next_cursor")
            page.set("results", results)
            StubResponse.json(page.toString())
        }
        api(HttpMethod.GET, "/v1/data_sources/([^/]+)") { _, match ->
            if (match.groupValues[1] != dataSourceId) return@api error(404, "object_not_found", "no data source")
            StubResponse.json(dataSource().toString())
        }
        api(HttpMethod.POST, "/v1/data_sources/([^/]+)/query") { call, match ->
            if (match.groupValues[1] != dataSourceId) return@api error(404, "object_not_found", "no data source")
            query(json.readTree(call.bodyText().ifBlank { "{}" }))
        }
        api(HttpMethod.POST, "/v1/pages") { call, _ ->
            val body = json.readTree(call.bodyText())
            if (body.path("parent").path("data_source_id").asString() != dataSourceId) return@api error(400, "validation_error", "bad parent")
            val props = nodes.objectNode()
            props.set("Name", title(""))
            props.set("Industry", select(null))
            props.set("Employees", number(null))
            props.set("Notes", richText(""))
            apply(props, body.path("properties"))?.let { return@api it }
            val now = Instant.now()
            val id = UUID.randomUUID().toString()
            val page = Page(id, now, props, minute(now), false, seq.incrementAndGet())
            pages[id] = page
            apiWrites.incrementAndGet()
            StubResponse.json(render(page).toString())
        }
        api(HttpMethod.GET, "/v1/pages/([^/]+)") { _, match ->
            val page = pages[match.groupValues[1]] ?: return@api error(404, "object_not_found", "no page")
            StubResponse.json(render(page).toString())
        }
        api(HttpMethod.PATCH, "/v1/pages/([^/]+)") { call, match ->
            val page = pages[match.groupValues[1]] ?: return@api error(404, "object_not_found", "no page")
            val body = json.readTree(call.bodyText())
            val props = page.properties.deepCopy()
            apply(props, body.path("properties"))?.let { return@api it }
            if (body.path("in_trash").isBoolean) page.inTrash = body.path("in_trash").asBoolean()
            touch(page, props)
            apiWrites.incrementAndGet()
            StubResponse.json(render(page).toString())
        }
    }

    // every api route: versioned, authorized, maybe throttled
    private fun api(
        method: HttpMethod,
        path: String,
        handler: (Recorded, MatchResult) -> StubResponse
    ) {
        server.on(method, path) { call, match ->
            when {
                call.header("Notion-Version") != "2025-09-03" -> error(400, "missing_version", "Notion-Version header missing or unsupported")
                call.header("Authorization")?.removePrefix("Bearer ") !in tokens -> error(401, "unauthorized", "API token is invalid.")
                throttled.getAndUpdate { if (it > 0) it - 1 else 0 } > 0 ->
                    StubResponse.json(
                        """{"object":"error","status":429,"code":"rate_limited","message":"You have been rate limited."}""",
                        429,
                        mapOf("Retry-After" to retryAfterSeconds.toString())
                    )
                else -> handler(call, match)
            }
        }
    }

    private fun query(body: JsonNode): StubResponse {
        val since =
            body
                .path("filter")
                .takeIf { it.path("timestamp").asString() == "last_edited_time" }
                ?.path("last_edited_time")
                ?.path("on_or_after")
                ?.asString()
                ?.let(Instant::parse)
        val descending =
            body
                .path("sorts")
                .items()
                .firstOrNull()
                ?.path("direction")
                ?.asString() == "descending"
        var all =
            pages.values
                .filterNot { it.inTrash }
                .filter { since == null || !it.lastEdited.isBefore(since) }
                .sortedWith(compareBy<Page>({ it.lastEdited }, { it.seq }))
        if (descending) all = all.reversed()
        val size = body.path("page_size").asInt(100).coerceIn(1, 100)
        val start =
            body
                .path("start_cursor")
                .takeIf { it.isString }
                ?.asString()
                ?.toIntOrNull() ?: 0
        val slice = all.drop(start).take(size)
        val more = start + size < all.size
        val out =
            nodes
                .objectNode()
                .put("object", "list")
                .put("type", "page_or_data_source")
                .put("has_more", more)
        if (more) out.put("next_cursor", (start + size).toString()) else out.putNull("next_cursor")
        out.set("results", nodes.arrayNode().apply { slice.forEach { add(render(it)) } })
        return StubResponse.json(out.toString())
    }

    // the property values a write sends, checked against the schema and stored as notion answers them
    private fun apply(
        props: ObjectNode,
        writes: JsonNode
    ): StubResponse? {
        for ((name, value) in writes.properties()) {
            val stored =
                when (name) {
                    "Name" -> value.path("title").takeIf { it.isArray }?.let { title(plain(it)) }
                    "Notes" -> value.path("rich_text").takeIf { it.isArray }?.let { richText(plain(it)) }
                    "Industry" ->
                        value.path("select").let { s ->
                            when {
                                s.isNull -> select(null)
                                s.isObject && s.path("name").asString() in INDUSTRIES -> select(s.path("name").asString())
                                else -> null
                            }
                        }
                    "Employees" -> value.path("number").let { n -> if (n.isNull) number(null) else n.takeIf { it.isNumber }?.let { number(it.decimalValue()) } }
                    else -> return error(400, "validation_error", "$name is not a property that exists.")
                } ?: return error(400, "validation_error", "body failed validation: properties.$name")
            props.set(name, stored)
        }
        return null
    }

    private fun touch(
        page: Page,
        props: ObjectNode
    ) {
        page.properties = props
        page.lastEdited = minute(Instant.now())
        page.seq = seq.incrementAndGet()
    }

    private fun render(page: Page): ObjectNode {
        val out =
            nodes
                .objectNode()
                .put("object", "page")
                .put("id", page.id)
                .put("created_time", page.createdTime.truncatedTo(ChronoUnit.MINUTES).toString())
                .put("last_edited_time", page.lastEdited.toString())
                .put("in_trash", page.inTrash)
                .put("archived", page.inTrash)
                .put("url", "https://www.notion.so/${page.id.replace("-", "")}")
        out.set(
            "parent",
            nodes
                .objectNode()
                .put("type", "data_source_id")
                .put("data_source_id", dataSourceId)
                .put("database_id", DATABASE_ID)
        )
        val props = page.properties.deepCopy()
        props.set(
            "Last edited",
            nodes
                .objectNode()
                .put("id", "le")
                .put("type", "last_edited_time")
                .put("last_edited_time", page.lastEdited.toString())
        )
        out.set("properties", props)
        return out
    }

    private fun dataSource(): ObjectNode {
        val out = nodes.objectNode().put("object", "data_source").put("id", dataSourceId)
        out.set("title", nodes.arrayNode().add(text("Accounts")))
        out.set("parent", nodes.objectNode().put("type", "database_id").put("database_id", DATABASE_ID))
        val props = nodes.objectNode()
        props.set(
            "Name",
            nodes
                .objectNode()
                .put("id", "title")
                .put("name", "Name")
                .put("type", "title")
                .set("title", nodes.objectNode())
        )
        val options = nodes.arrayNode()
        INDUSTRIES.forEachIndexed { i, o ->
            options.add(
                nodes
                    .objectNode()
                    .put("id", "opt-$i")
                    .put("name", o)
                    .put("color", "default")
            )
        }
        props.set(
            "Industry",
            nodes
                .objectNode()
                .put("id", "ind")
                .put("name", "Industry")
                .put("type", "select")
                .set("select", nodes.objectNode().set("options", options))
        )
        props.set(
            "Employees",
            nodes
                .objectNode()
                .put("id", "emp")
                .put("name", "Employees")
                .put("type", "number")
                .set("number", nodes.objectNode().put("format", "number"))
        )
        props.set(
            "Notes",
            nodes
                .objectNode()
                .put("id", "nts")
                .put("name", "Notes")
                .put("type", "rich_text")
                .set("rich_text", nodes.objectNode())
        )
        props.set(
            "Last edited",
            nodes
                .objectNode()
                .put("id", "le")
                .put("name", "Last edited")
                .put("type", "last_edited_time")
                .set("last_edited_time", nodes.objectNode())
        )
        out.set("properties", props)
        return out
    }

    private fun text(content: String): ObjectNode {
        val node =
            nodes
                .objectNode()
                .put("type", "text")
                .put("plain_text", content)
                .putNull("href")
        node.set("text", nodes.objectNode().put("content", content).putNull("link"))
        return node
    }

    private fun title(content: String): ObjectNode =
        nodes
            .objectNode()
            .put(
                "id",
                "title"
            ).put("type", "title")
            .set("title", if (content.isEmpty()) nodes.arrayNode() else nodes.arrayNode().add(text(content)))

    private fun richText(content: String): ObjectNode =
        nodes
            .objectNode()
            .put("id", "nts")
            .put("type", "rich_text")
            .set("rich_text", if (content.isEmpty()) nodes.arrayNode() else nodes.arrayNode().add(text(content)))

    private fun select(name: String?): ObjectNode {
        val node = nodes.objectNode().put("id", "ind").put("type", "select")
        if (name == null) node.putNull("select") else node.set("select", nodes.objectNode().put("name", name).put("color", "default"))
        return node
    }

    private fun number(value: BigDecimal?): ObjectNode {
        val node = nodes.objectNode().put("id", "emp").put("type", "number")
        if (value == null) node.putNull("number") else node.put("number", value)
        return node
    }

    private fun plain(rich: JsonNode): String = rich.items().joinToString("") { it.path("text").path("content").asString(it.path("plain_text").asString("")) }

    private fun error(
        status: Int,
        code: String,
        message: String
    ) = StubResponse.json("""{"object":"error","status":$status,"code":"$code","message":"$message"}""", status)

    private fun oauthError(
        status: Int,
        error: String
    ) = StubResponse.json("""{"error":"$error"}""", status)

    private fun form(body: String): Map<String, String> =
        body
            .split("&")
            .filter { it.isNotEmpty() }
            .associate {
                URLDecoder.decode(it.substringBefore("="), StandardCharsets.UTF_8) to
                    URLDecoder.decode(it.substringAfter("=", ""), StandardCharsets.UTF_8)
            }

    private fun enc(value: String) = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun minute(instant: Instant): Instant = instant.truncatedTo(ChronoUnit.MINUTES)

    override fun close() = server.close()

    companion object {
        const val DATABASE_ID = "7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
        val INDUSTRIES = listOf("Banking", "Energy", "Retail", "Technology")

        // the query string of an authorization url, decoded
        fun params(url: String): Map<String, String> =
            URI
                .create(url)
                .rawQuery
                .split("&")
                .associate {
                    URLDecoder.decode(it.substringBefore("="), StandardCharsets.UTF_8) to
                        URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8)
                }
    }
}

private fun JsonNode.items(): List<JsonNode> = if (isArray) (0 until size()).map { get(it) } else emptyList()
