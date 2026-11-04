package wasichai.integrations.it

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.Recorded
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// a salesforce org in memory: Account records over the rest api (v66.0), the oauth web server flow with pkce,
// refresh tokens and sessions that expire on demand. SystemModstamp moves at least one second per write, so
// If-Unmodified-Since (second precision) always tells a later write apart. query pages are small on purpose:
// nextRecordsUrl paging is exercised with a handful of records.
class SalesforceStub : AutoCloseable {
    class Account(
        val id: String,
        @Volatile var fields: Map<String, JsonNode>,
        @Volatile var stamp: Instant
    )

    val server = StubProviderServer()
    private val json = JsonMapper.builder().build()
    private val nodes = JsonNodeFactory.instance
    private val accounts = ConcurrentHashMap<String, Account>()
    private val deleted = ConcurrentHashMap<String, Instant>()
    private val ids = AtomicLong()
    private val clock = AtomicLong()
    private val codes = ConcurrentHashMap<String, Pair<String, String?>>()
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val refreshTokens = ConcurrentHashMap.newKeySet<String>()
    private val cursors = ConcurrentHashMap<String, List<ObjectNode>>()
    private val throttled = AtomicInteger()

    @Volatile
    private var retryAfterSeconds = 1

    // records per query page; real salesforce honours batchSize loosely, this one caps it to page through
    @Volatile
    var queryPageSize = 2

    val apiWrites = AtomicInteger()
    val refreshes = AtomicInteger()
    val baseUrl: String get() = server.baseUrl
    val clientId = "sf-consumer-key"
    val clientSecret = "sf-consumer-secret"
    val orgId = "00D000000000001EAA"

    init {
        routes()
    }

    // ---- what a person does in salesforce

    fun addAccount(
        name: String,
        industry: String? = null,
        employees: Int? = null
    ): String {
        val id = "001%012dAAA".format(ids.incrementAndGet())
        accounts[id] = Account(id, values(name, industry, employees), tick())
        return id
    }

    fun edit(
        id: String,
        name: String? = null,
        industry: String? = null,
        employees: Int? = null
    ) {
        val account = accounts.getValue(id)
        val changed = account.fields.toMutableMap()
        name?.let { changed["Name"] = nodes.stringNode(it) }
        industry?.let { changed["Industry"] = nodes.stringNode(it) }
        employees?.let { changed["NumberOfEmployees"] = nodes.numberNode(it) }
        account.fields = changed
        account.stamp = tick()
    }

    // gone with no trace in the deleted resource (past its 30 days, a hard delete): only a full read can tell
    fun vanish(id: String) {
        accounts.remove(id)
    }

    fun account(id: String): Account? = accounts[id]

    fun all(): List<Account> = accounts.values.sortedBy { it.id }

    fun byName(name: String): Account? = all().firstOrNull { name(it) == name }

    fun name(account: Account): String? = account.fields["Name"]?.takeIf { it.isString }?.asString()

    fun industry(account: Account): String? = account.fields["Industry"]?.takeIf { it.isString }?.asString()

    fun employees(account: Account): Int? = account.fields["NumberOfEmployees"]?.takeIf { it.isNumber }?.asInt()

    // every access token issued so far stops working: the next api call answers INVALID_SESSION_ID
    fun expireSessions() {
        sessions.clear()
    }

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
        server.on(HttpMethod.GET, "/services/oauth2/authorize") { call, _ ->
            val redirect = call.query["redirect_uri"]
            if (call.query["client_id"] != clientId || redirect == null || call.query["response_type"] != "code") {
                return@on StubResponse.json("""{"error":"invalid_request"}""", 400)
            }
            if (call.query["code_challenge"] != null && call.query["code_challenge_method"] != "S256") {
                return@on StubResponse.json("""{"error":"invalid_request","error_description":"S256 only"}""", 400)
            }
            val code = "aPrx" + UUID.randomUUID().toString().replace("-", "")
            codes[code] = redirect to call.query["code_challenge"]
            val location = redirect + (if ('?' in redirect) "&" else "?") + "code=${enc(code)}&state=${enc(call.query["state"].orEmpty())}"
            StubResponse(302, headers = mapOf("Location" to location))
        }
        server.on(HttpMethod.POST, "/services/oauth2/token") { call, _ ->
            val form = form(call.bodyText())
            if (form["client_id"] != clientId || form["client_secret"] != clientSecret) return@on oauthError("invalid_client", 401)
            when (form["grant_type"]) {
                "authorization_code" -> {
                    val (redirect, challenge) = codes.remove(form["code"].orEmpty()) ?: return@on oauthError("invalid_grant")
                    if (redirect != form["redirect_uri"]) return@on oauthError("invalid_grant")
                    if (challenge != null && challenge != s256(form["code_verifier"].orEmpty())) return@on oauthError("invalid_grant")
                    val refresh = "5Aep" + UUID.randomUUID().toString().replace("-", "")
                    refreshTokens += refresh
                    grant(refresh)
                }
                "refresh_token" -> {
                    if (form["refresh_token"] !in refreshTokens) return@on oauthError("invalid_grant")
                    refreshes.incrementAndGet()
                    // salesforce keeps the refresh token: the answer carries none
                    grant(null)
                }
                else -> oauthError("unsupported_grant_type")
            }
        }
        api(HttpMethod.GET, "/services/oauth2/userinfo") { _, _ ->
            StubResponse.json(
                """{"sub":"$baseUrl/id/$orgId/005000000000001AAA","user_id":"005000000000001AAA","organization_id":"$orgId",""" +
                    """"preferred_username":"admin@stub.example","name":"Stub Admin"}"""
            )
        }
        api(HttpMethod.GET, "/services/data/") { _, _ ->
            StubResponse.json("""[{"label":"Winter '26","url":"/services/data/v65.0","version":"65.0"},{"label":"Spring '26","url":"$API","version":"66.0"}]""")
        }
        api(HttpMethod.GET, "$API/sobjects/") { _, _ ->
            StubResponse.json(
                """{"encoding":"UTF-8","maxBatchSize":200,"sobjects":[""" +
                    """{"name":"Account","label":"Account","queryable":true,"retrieveable":true,"createable":true,"updateable":true},""" +
                    """{"name":"AccountHistory","label":"Account History","queryable":true,"retrieveable":false}]}"""
            )
        }
        api(HttpMethod.GET, "$API/sobjects/Account/describe/") { _, _ -> StubResponse.json(DESCRIBE) }
        api(HttpMethod.GET, "$API/query/") { call, _ -> query(call) }
        api(HttpMethod.GET, "$API/query/([A-Za-z0-9-]+)") { _, match ->
            val rest = cursors.remove(match.groupValues[1]) ?: return@api sfError(400, "INVALID_QUERY_LOCATOR", "invalid query locator")
            page(rest)
        }
        api(HttpMethod.GET, "$API/sobjects/Account/updated/") { call, _ ->
            val (start, end) = window(call) ?: return@api sfError(400, "INVALID_REPLICATION_DATE", "start and end are required")
            val found =
                accounts.values
                    .filter { !it.stamp.isBefore(start) && it.stamp.isBefore(end) }
                    .map { it.id }
                    .sorted()
            val out = nodes.objectNode().put("latestDateCovered", native(end))
            out.set("ids", nodes.arrayNode().apply { found.forEach { add(it) } })
            StubResponse.json(out.toString())
        }
        api(HttpMethod.GET, "$API/sobjects/Account/deleted/") { call, _ ->
            val (start, end) = window(call) ?: return@api sfError(400, "INVALID_REPLICATION_DATE", "start and end are required")
            val records = nodes.arrayNode()
            deleted.entries
                .filter { !it.value.isBefore(start) && it.value.isBefore(end) }
                .sortedBy { it.value }
                .forEach { records.add(nodes.objectNode().put("id", it.key).put("deletedDate", native(it.value))) }
            val out = nodes.objectNode().put("earliestDateAvailable", native(end.minus(30, ChronoUnit.DAYS))).put("latestDateCovered", native(end))
            out.set("deletedRecords", records)
            StubResponse.json(out.toString())
        }
        api(HttpMethod.POST, "$API/sobjects/Account/") { call, _ ->
            val body = json.readTree(call.bodyText())
            val values = validated(body) ?: return@api sfError(400, "INVALID_FIELD", "unknown or read-only field")
            if (values["Name"]?.takeIf { it.isString && it.asString().isNotBlank() } == null) {
                return@api sfError(400, "REQUIRED_FIELD_MISSING", "Required fields are missing: [Name]", "Name")
            }
            val id = "001%012dAAA".format(ids.incrementAndGet())
            accounts[id] = Account(id, values(null, null, null) + values, tick())
            apiWrites.incrementAndGet()
            StubResponse.json("""{"id":"$id","success":true,"errors":[]}""", 201)
        }
        api(HttpMethod.GET, "$API/sobjects/Account/([A-Za-z0-9]{18})") { call, match ->
            val account = accounts[match.groupValues[1]] ?: return@api sfError(404, "NOT_FOUND", "The requested resource does not exist")
            val fields = call.query["fields"]?.split(",")?.map { it.trim() } ?: SELECTABLE
            StubResponse.json(render(account, fields).toString())
        }
        api(HttpMethod.PATCH, "$API/sobjects/Account/([A-Za-z0-9]{18})") { call, match ->
            val account = accounts[match.groupValues[1]] ?: return@api sfError(404, "NOT_FOUND", "The requested resource does not exist")
            call.header("If-Unmodified-Since")?.let { header ->
                val since = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                if (account.stamp.isAfter(since)) return@api sfError(412, "PRECONDITION_FAILED", "The record changed since $header")
            }
            val values = validated(json.readTree(call.bodyText())) ?: return@api sfError(400, "INVALID_FIELD", "unknown or read-only field")
            account.fields = account.fields + values
            account.stamp = tick()
            apiWrites.incrementAndGet()
            StubResponse(204)
        }
        api(HttpMethod.DELETE, "$API/sobjects/Account/([A-Za-z0-9]{18})") { _, match ->
            val id = match.groupValues[1]
            accounts.remove(id) ?: return@api sfError(404, "ENTITY_IS_DELETED", "entity is deleted")
            deleted[id] = tick()
            apiWrites.incrementAndGet()
            StubResponse(204)
        }
    }

    private fun api(
        method: HttpMethod,
        path: String,
        handler: (Recorded, MatchResult) -> StubResponse
    ) {
        server.on(method, path) { call, match ->
            when {
                call.header("Authorization")?.removePrefix("Bearer ") !in sessions -> sfError(401, "INVALID_SESSION_ID", "Session expired or invalid")
                throttled.getAndUpdate { if (it > 0) it - 1 else 0 } > 0 ->
                    StubResponse.json(
                        """[{"message":"TotalRequests Limit exceeded.","errorCode":"REQUEST_LIMIT_EXCEEDED"}]""",
                        429,
                        mapOf("Retry-After" to retryAfterSeconds.toString())
                    )
                else -> handler(call, match)
            }
        }
    }

    // the soql the connector builds: SELECT f, ... FROM Account [WHERE SystemModstamp >= dt | F = 'v'] [ORDER BY f, ...] [LIMIT n]
    private fun query(call: Recorded): StubResponse {
        val soql = call.query["q"] ?: return sfError(400, "MALFORMED_QUERY", "q is required")
        val parsed = SOQL.matchEntire(soql.trim()) ?: return sfError(400, "MALFORMED_QUERY", "unsupported query: $soql")
        val fields = parsed.groupValues[1].split(",").map { it.trim() }
        if (parsed.groupValues[2] != "Account") return sfError(400, "INVALID_TYPE", "sObject type '${parsed.groupValues[2]}' is not supported")
        fields.firstOrNull { it !in SELECTABLE }?.let { return sfError(400, "INVALID_FIELD", "No such column '$it' on entity 'Account'") }
        val where = parsed.groupValues[3].takeIf { it.isNotEmpty() }
        val stampSince = where?.let { STAMP.matchEntire(it) }?.let { Instant.parse(it.groupValues[1]) }
        val equals = where?.let { EQUALS.matchEntire(it) }?.destructured
        if (where != null && stampSince == null && equals == null) return sfError(400, "MALFORMED_QUERY", "unsupported where: $where")
        val filter: (Account) -> Boolean = { a ->
            when {
                stampSince != null -> !a.stamp.isBefore(stampSince)
                equals != null -> a.fields[equals.component1()]?.takeIf { it.isString }?.asString() == equals.component2().replace("\\'", "'")
                else -> true
            }
        }
        val order =
            parsed.groupValues[4]
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        var found = accounts.values.filter(filter)
        found =
            if (order.firstOrNull() == "SystemModstamp") {
                found.sortedWith(compareBy<Account>({ it.stamp }, { it.id }))
            } else {
                found.sortedBy { it.id }
            }
        parsed.groupValues[5].takeIf { it.isNotEmpty() }?.let { found = found.take(it.toInt()) }
        val batch =
            call
                .header("Sforce-Query-Options")
                ?.substringAfter("batchSize=")
                ?.toIntOrNull() ?: 2000
        return page(found.map { render(it, fields) }, minOf(batch, queryPageSize))
    }

    private fun page(
        records: List<ObjectNode>,
        size: Int = queryPageSize
    ): StubResponse {
        val head = records.take(size)
        val rest = records.drop(size)
        val out = nodes.objectNode().put("totalSize", records.size).put("done", rest.isEmpty())
        if (rest.isNotEmpty()) {
            val locator =
                "01g" +
                    UUID
                        .randomUUID()
                        .toString()
                        .replace("-", "")
                        .take(15) + "-$size"
            cursors[locator] = rest
            out.put("nextRecordsUrl", "$API/query/$locator")
        }
        out.set("records", nodes.arrayNode().apply { head.forEach { add(it) } })
        return StubResponse.json(out.toString())
    }

    private fun render(
        account: Account,
        fields: List<String>
    ): ObjectNode {
        val out = nodes.objectNode()
        out.set("attributes", nodes.objectNode().put("type", "Account").put("url", "$API/sobjects/Account/${account.id}"))
        fields.forEach { f ->
            when (f) {
                "Id" -> out.put("Id", account.id)
                "SystemModstamp" -> out.put("SystemModstamp", native(account.stamp))
                else -> out.set(f, account.fields[f] ?: nodes.nullNode())
            }
        }
        return out
    }

    // writable fields only; a picklist value must be one of its active values (a restricted picklist)
    private fun validated(body: JsonNode): Map<String, JsonNode>? {
        val out = mutableMapOf<String, JsonNode>()
        for ((name, value) in body.properties()) {
            if (name !in WRITABLE) return null
            if (name == "Industry" && !value.isNull && value.asString() !in INDUSTRIES) return null
            if (name == "NumberOfEmployees" && !value.isNull && !value.isNumber) return null
            out[name] = if (name == "NumberOfEmployees" && value.isNumber) nodes.numberNode(value.asInt()) else value
        }
        return out
    }

    private fun values(
        name: String?,
        industry: String?,
        employees: Int?
    ): Map<String, JsonNode> =
        mapOf(
            "Name" to (name?.let { nodes.stringNode(it) } ?: nodes.nullNode()),
            "Industry" to (industry?.let { nodes.stringNode(it) } ?: nodes.nullNode()),
            "NumberOfEmployees" to (employees?.let { nodes.numberNode(it) } ?: nodes.nullNode()),
            "Description" to nodes.nullNode(),
            "External_Id__c" to nodes.nullNode()
        )

    private fun grant(refresh: String?): StubResponse {
        val access = "00D!" + UUID.randomUUID().toString().replace("-", "")
        sessions += access
        val out =
            nodes
                .objectNode()
                .put("access_token", access)
                .put("instance_url", baseUrl)
                .put("id", "$baseUrl/id/$orgId/005000000000001AAA")
                .put("token_type", "Bearer")
                .put("issued_at", Instant.now().toEpochMilli().toString())
                .put("signature", "c3R1Yg==")
                .put("scope", "api refresh_token")
        refresh?.let { out.put("refresh_token", it) }
        return StubResponse.json(out.toString())
    }

    private fun window(call: Recorded): Pair<Instant, Instant>? {
        val start = call.query["start"]?.let { OffsetDateTime.parse(it).toInstant() } ?: return null
        val end = call.query["end"]?.let { OffsetDateTime.parse(it).toInstant() } ?: return null
        return start to end
    }

    // at least a second after the previous write, never behind the wall clock
    private fun tick(): Instant {
        val now = Instant.now().toEpochMilli()
        return Instant.ofEpochMilli(clock.updateAndGet { last -> maxOf(now, last + 1000) })
    }

    private fun native(instant: Instant): String = NATIVE.format(instant)

    private fun sfError(
        status: Int,
        code: String,
        message: String,
        field: String? = null
    ): StubResponse {
        val error = nodes.objectNode().put("message", message).put("errorCode", code)
        error.set("fields", nodes.arrayNode().apply { field?.let { add(it) } })
        return StubResponse.json(nodes.arrayNode().add(error).toString(), status)
    }

    private fun oauthError(
        error: String,
        status: Int = 400
    ) = StubResponse.json("""{"error":"$error","error_description":"$error"}""", status)

    private fun form(body: String): Map<String, String> =
        body
            .split("&")
            .filter { it.isNotEmpty() }
            .associate {
                URLDecoder.decode(it.substringBefore("="), StandardCharsets.UTF_8) to
                    URLDecoder.decode(it.substringAfter("=", ""), StandardCharsets.UTF_8)
            }

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun s256(verifier: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    override fun close() = server.close()

    companion object {
        const val API = "/services/data/v66.0"
        val INDUSTRIES = NotionStub.INDUSTRIES
        private val SELECTABLE = listOf("Id", "Name", "Industry", "NumberOfEmployees", "Description", "External_Id__c", "SystemModstamp")
        private val WRITABLE = setOf("Name", "Industry", "NumberOfEmployees", "Description", "External_Id__c")
        private val NATIVE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX").withZone(ZoneOffset.UTC)
        private val SOQL = Regex("""^SELECT (.+?) FROM (\w+)(?: WHERE (.+?))?(?: ORDER BY (.+?))?(?: LIMIT (\d+))?$""")
        private val STAMP = Regex("""^SystemModstamp >= (\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z)$""")
        private val EQUALS = Regex("""^(\w+) = '((?:[^'\\]|\\.)*)'$""")

        private val DESCRIBE =
            """
            {"name":"Account","label":"Account","queryable":true,"retrieveable":true,"fields":[
              {"name":"Id","label":"Account ID","type":"id","nillable":false,"createable":false,"updateable":false,"defaultedOnCreate":true},
              {"name":"Name","label":"Account Name","type":"string","nillable":false,"createable":true,"updateable":true,"defaultedOnCreate":false},
              {"name":"Industry","label":"Industry","type":"picklist","nillable":true,"createable":true,"updateable":true,"restrictedPicklist":true,
               "picklistValues":[{"value":"Banking","active":true},{"value":"Energy","active":true},{"value":"Retail","active":true},
                                 {"value":"Technology","active":true},{"value":"Retired","active":false}]},
              {"name":"NumberOfEmployees","label":"Employees","type":"int","nillable":true,"createable":true,"updateable":true},
              {"name":"Description","label":"Account Description","type":"textarea","nillable":true,"createable":true,"updateable":true},
              {"name":"External_Id__c","label":"External Id","type":"string","nillable":true,"createable":true,"updateable":true,
               "externalId":true,"unique":true},
              {"name":"SystemModstamp","label":"System Modstamp","type":"datetime","nillable":false,"createable":false,"updateable":false}
            ]}
            """.trimIndent()
    }
}
