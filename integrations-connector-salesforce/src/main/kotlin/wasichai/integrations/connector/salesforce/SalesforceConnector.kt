package wasichai.integrations.connector.salesforce

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.integrations.sdk.AccountInfo
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Authenticator
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.ChangeFeed
import wasichai.integrations.sdk.ChangePage
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.ConnectorManifest
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.PaginationStrategy
import wasichai.integrations.sdk.ProviderInfo
import wasichai.integrations.sdk.ProviderRecord
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.ProviderResponse
import wasichai.integrations.sdk.RateLimitHint
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.RecordPage
import wasichai.integrations.sdk.ResourceDescriptor
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceSchema
import wasichai.integrations.sdk.ResourceUpserter
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.SdkVersion
import wasichai.integrations.sdk.SyncDirectionSupport
import wasichai.integrations.sdk.TokenGrant
import wasichai.integrations.sdk.WriteResult
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// salesforce through its rest api: describe, soql, sObject crud, external-id upsert and a polling change feed
// (SystemModstamp + the deleted resource). change data capture over the pub/sub api lives in
// integrations-connector-salesforce-pubsub (ADR-0024), which reuses this connector.
class SalesforceConnector(
    private val config: SalesforceConfig = SalesforceConfig()
) : Connector,
    Authenticator,
    ResourceDiscovery,
    ResourceReader,
    ChangeFeed,
    ResourceWriter,
    ResourceUpserter {
    override val manifest =
        ConnectorManifest(
            id = ID,
            provider = ProviderInfo("Salesforce", "https://www.salesforce.com", "https://developer.salesforce.com/docs/apis"),
            version = "0.1.0",
            sdkVersion = SdkVersion.CURRENT,
            description =
                "Salesforce REST API: sObject describe, SOQL reads, CRUD, upsert by external id, and incremental changes " +
                    "by polling SystemModstamp and deleted records. No Change Data Capture yet.",
            authMethods =
                listOf(
                    AuthMethod.OAuth2AuthorizationCode(
                        authorizeUrl = "${config.loginUrl}/services/oauth2/authorize",
                        tokenUrl = "${config.loginUrl}/services/oauth2/token",
                        defaultScopes = config.defaultScopes,
                        pkce = true,
                        revokeUrl = "${config.loginUrl}/services/oauth2/revoke"
                    )
                ),
            capabilities =
                mapOf(
                    Capability.AUTHENTICATION to Maturity.TESTED,
                    Capability.DISCOVERY to Maturity.TESTED,
                    Capability.READ to Maturity.TESTED,
                    Capability.CHANGE_FEED to Maturity.TESTED,
                    Capability.WRITE to Maturity.TESTED,
                    Capability.UPSERT to Maturity.TESTED
                ),
            syncDirections = setOf(SyncDirectionSupport.SOURCE, SyncDirectionSupport.TARGET),
            pagination = setOf(PaginationStrategy.NEXT_URL),
            // the real limit is the org's rolling 24-hour api allocation; this only paces one connection
            rateLimit = RateLimitHint(requestsPerSecond = 20.0, burst = 20, scope = "connection"),
            requiredPermissions = listOf("API Enabled", "read/edit on the synchronized objects")
        )

    private val versions = ConcurrentHashMap<String, String>()
    private val describes = ConcurrentHashMap<String, SObjectDescribe>()

    // ---- authentication

    override fun connectionMetadata(grant: TokenGrant): Map<String, String> =
        listOf(INSTANCE_URL, "id")
            .mapNotNull { key -> (grant.raw[key] as? String)?.let { key to it } }
            .toMap()

    override suspend fun accountInfo(context: ConnectorContext): AccountInfo {
        val body = get(context, "${instance(context)}/services/oauth2/userinfo").json()
        val org = body.path("organization_id").asString("")
        if (org.isEmpty()) throw ConnectorException.Permanent("userinfo has no organization_id")
        return AccountInfo(
            providerAccountId = org,
            displayName = body.path("preferred_username").asStringOpt().orElse(body.path("name").asString(null)),
            metadata =
                listOfNotNull(
                    body
                        .path("user_id")
                        .asStringOpt()
                        .orElse(null)
                        ?.let { "user_id" to it }
                ).toMap()
        )
    }

    // ---- discovery

    override suspend fun listResources(context: ConnectorContext): List<ResourceDescriptor> =
        get(context, "${api(context)}/sobjects/")
            .json()
            .path("sobjects")
            .values()
            .filter { it.path("queryable").asBoolean(false) && it.path("retrieveable").asBoolean(false) }
            .map { ResourceDescriptor(it.get("name").asString(), it.path("label").asString(it.get("name").asString())) }

    override suspend fun describe(
        context: ConnectorContext,
        resource: String
    ): ResourceSchema = sobject(context, resource).schema

    private suspend fun sobject(
        context: ConnectorContext,
        type: String
    ): SObjectDescribe {
        val key = "${context.connectionId}/${Soql.identifier(type)}"
        describes[key]?.let { return it }
        val describe = SalesforceValues.describe(get(context, "${api(context)}/sobjects/$type/describe/").json())
        describes[key] = describe
        return describe
    }

    // ---- reads

    override suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord? {
        val describe = sobject(context, resource)
        val response = exchange(context, ProviderRequest(HttpMethod.GET, "${api(context)}/sobjects/$resource/${recordId(id)}"))
        if (response.status == 404) return null
        if (!response.successful) throw SalesforceErrors.classify(response)
        return record(response.json(), describe)
    }

    override suspend fun list(
        context: ConnectorContext,
        request: ReadRequest
    ): RecordPage {
        val describe = sobject(context, request.resource)
        val body =
            if (request.cursor != null) {
                next(context, request.cursor!!, request.pageSize)
            } else {
                query(context, Soql.select(describe.selectable, request.resource, orderBy = listOf("Id")), request.pageSize)
            }
        return RecordPage(records(body, describe), body.path("nextRecordsUrl").asStringOpt().orElse(null))
    }

    override suspend fun changes(
        context: ConnectorContext,
        resource: String,
        checkpoint: String?,
        pageSize: Int
    ): ChangePage {
        val describe = sobject(context, resource)
        val state = ChangeCheckpoint.parse(checkpoint)
        val now = context.clock.instant()
        val body =
            if (state.next != null) {
                next(context, state.next, pageSize)
            } else {
                // >= on a second-truncated literal re-sends the boundary records: at least once, never a gap
                val where = state.since?.let { "SystemModstamp >= ${Soql.datetime(it)}" }
                query(context, Soql.select(describe.selectable, resource, where, listOf("SystemModstamp", "Id")), pageSize)
            }
        val upserts = records(body, describe)
        val seen = (upserts.mapNotNull { it.modifiedAt } + listOfNotNull(state.pending)).maxOrNull()
        val nextUrl = body.path("nextRecordsUrl").asStringOpt().orElse(null)
        val changes = mutableListOf<Change>()
        upserts.forEach { changes += Change.Upserted(it) }
        var deletedSince = state.deletedSince
        if (nextUrl == null) {
            // a new query cycle starts from the newest change seen; deletions are read once per cycle
            if (deletedSince == null) {
                deletedSince = now
            } else {
                val window = deleted(context, resource, deletedSince, now)
                changes += window.first
                deletedSince = window.second
            }
        }
        val next =
            if (nextUrl != null) {
                ChangeCheckpoint(state.since, deletedSince, nextUrl, seen)
            } else {
                ChangeCheckpoint(seen ?: state.since, deletedSince, null, null)
            }
        return ChangePage(changes, next.format(), nextUrl != null)
    }

    // the current state of records a feed only knows by id (change data capture, ADR-0024): one soql query per
    // chunk, in no particular order. an id missing from the answer is deleted or not visible to this user
    suspend fun readMany(
        context: ConnectorContext,
        resource: String,
        ids: Collection<String>
    ): List<ProviderRecord> {
        if (ids.isEmpty()) return emptyList()
        val describe = sobject(context, resource)
        val out = mutableListOf<ProviderRecord>()
        ids.map(::recordId).distinct().chunked(IDS_PER_QUERY).forEach { chunk ->
            val where = "Id IN (${chunk.joinToString(", ") { Soql.string(it) }})"
            var body = query(context, Soql.select(describe.selectable, resource, where), IDS_PER_QUERY)
            out += records(body, describe)
            while (true) {
                val nextUrl = body.path("nextRecordsUrl").asStringOpt().orElse(null) ?: break
                body = next(context, nextUrl, IDS_PER_QUERY)
                out += records(body, describe)
            }
        }
        return out
    }

    // the deleted resource covers the last 30 days and wants a window of at least a minute
    private suspend fun deleted(
        context: ConnectorContext,
        type: String,
        since: Instant,
        now: Instant
    ): Pair<List<Change>, Instant> {
        val start = maxOf(since, now.minus(Duration.ofDays(29)))
        if (Duration.between(start, now) < Duration.ofMinutes(1)) return emptyList<Change>() to since
        val body =
            get(
                context,
                "${api(context)}/sobjects/$type/deleted/",
                mapOf("start" to SalesforceTimes.window(start), "end" to SalesforceTimes.window(now))
            ).json()
        val deletions =
            body.path("deletedRecords").values().map {
                Change.Deleted(it.get("id").asString(), SalesforceTimes.parseOrNull(it.path("deletedDate").asString(null)))
            }
        val covered = SalesforceTimes.parseOrNull(body.path("latestDateCovered").asString(null)) ?: now
        return deletions to covered
    }

    // ---- writes

    override suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        Soql.identifier(resource)
        val response = send(context, HttpMethod.POST, "${api(context)}/sobjects/$resource/", SalesforceValues.toJson(fields))
        val id = response.json().path("id").asString("")
        if (id.isEmpty()) throw ConnectorException.Permanent("create answered without an id")
        return written(context, resource, id, created = true)
    }

    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): WriteResult {
        Soql.identifier(resource)
        val headers = expectedVersion?.let { mapOf("If-Unmodified-Since" to SalesforceTimes.httpDate(SalesforceTimes.parse(it))) } ?: emptyMap()
        send(context, HttpMethod.PATCH, "${api(context)}/sobjects/$resource/${recordId(id)}", SalesforceValues.toJson(fields), headers)
        return written(context, resource, id, created = false)
    }

    override suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    ) {
        Soql.identifier(resource)
        val response = exchange(context, ProviderRequest(HttpMethod.DELETE, "${api(context)}/sobjects/$resource/${recordId(id)}"))
        if (!response.successful) throw SalesforceErrors.classify(response)
    }

    override suspend fun upsert(
        context: ConnectorContext,
        resource: String,
        externalIdField: String,
        externalId: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        Soql.identifier(resource)
        Soql.identifier(externalIdField)
        // the external id travels in the url; salesforce refuses it again in the body
        val body = SalesforceValues.toJson(fields - externalIdField)
        val response = send(context, HttpMethod.PATCH, "${api(context)}/sobjects/$resource/$externalIdField/${segment(externalId)}", body)
        val created = response.status == 201
        val id =
            response.body
                .takeIf { it.isNotEmpty() }
                ?.let { response.json().path("id").asString(null) }
                ?: findByExternalId(context, resource, externalIdField, externalId)
        return written(context, resource, id, created)
    }

    private suspend fun findByExternalId(
        context: ConnectorContext,
        type: String,
        field: String,
        value: String
    ): String {
        val soql = Soql.select(listOf("Id"), type, "$field = ${Soql.string(value)}", limit = 1)
        return query(context, soql, null)
            .path("records")
            .values()
            .firstOrNull()
            ?.get("Id")
            ?.asString()
            ?: throw ConnectorException.NotFound("no $type with $field = the upserted value")
    }

    // a write answers no version: read it back so the platform can store what it wrote
    private suspend fun written(
        context: ConnectorContext,
        type: String,
        id: String,
        created: Boolean
    ): WriteResult {
        val response =
            get(
                context,
                "${api(context)}/sobjects/$type/${recordId(id)}",
                mapOf("fields" to "Id,SystemModstamp")
            ).json()
        val stamp = response.path("SystemModstamp").asString(null)
        return WriteResult(id, stamp, SalesforceTimes.parseOrNull(stamp), created)
    }

    // ---- plumbing

    private fun record(
        node: JsonNode,
        describe: SObjectDescribe
    ): ProviderRecord {
        val stamp = node.path("SystemModstamp").asString(null)
        return ProviderRecord(node.get("Id").asString(), stamp, SalesforceTimes.parseOrNull(stamp), SalesforceValues.fromJson(node, describe), node)
    }

    private fun records(
        body: JsonNode,
        describe: SObjectDescribe
    ) = body.path("records").values().map { record(it, describe) }

    private suspend fun query(
        context: ConnectorContext,
        soql: String,
        pageSize: Int?
    ): JsonNode = get(context, "${api(context)}/query/", mapOf("q" to soql), batchHeader(pageSize)).json()

    // a cursor is a nextRecordsUrl: only ever a path on the same instance
    private suspend fun next(
        context: ConnectorContext,
        nextRecordsUrl: String,
        pageSize: Int
    ): JsonNode {
        if (!NEXT_URL.matches(nextRecordsUrl)) throw ConnectorException.Permanent("not a Salesforce query cursor")
        return get(context, "${instance(context)}$nextRecordsUrl", headers = batchHeader(pageSize)).json()
    }

    private fun batchHeader(pageSize: Int?): Map<String, String> =
        pageSize?.let { mapOf("Sforce-Query-Options" to "batchSize=${it.coerceIn(200, 2000)}") } ?: emptyMap()

    private suspend fun get(
        context: ConnectorContext,
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap()
    ): ProviderResponse {
        val response = exchange(context, ProviderRequest(HttpMethod.GET, url, headers, query))
        if (!response.successful) throw SalesforceErrors.classify(response)
        return response
    }

    private suspend fun send(
        context: ConnectorContext,
        method: HttpMethod,
        url: String,
        body: JsonNode,
        headers: Map<String, String> = emptyMap()
    ): ProviderResponse {
        val response = exchange(context, ProviderRequest(method, url, headers, body = SalesforceValues.bytes(body), contentType = "application/json"))
        if (!response.successful) throw SalesforceErrors.classify(response)
        return response
    }

    private suspend fun exchange(
        context: ConnectorContext,
        request: ProviderRequest
    ): ProviderResponse = context.http.exchange(request)

    private fun instance(context: ConnectorContext): String =
        context.metadata[INSTANCE_URL]?.trimEnd('/')
            ?: throw ConnectorException.Permanent("connection has no instance_url; reconnect the account")

    private suspend fun api(context: ConnectorContext): String = "${instance(context)}/services/data/v${version(context)}"

    private suspend fun version(context: ConnectorContext): String {
        (context.metadata[API_VERSION] ?: config.apiVersion)?.let { return it }
        versions[context.connectionId]?.let { return it }
        val newest =
            get(context, "${instance(context)}/services/data/")
                .json()
                .values()
                .mapNotNull { it.path("version").asString(null) }
                .filter { SalesforceConfig.VERSION.matches(it) }
                .maxByOrNull { it.toBigDecimal() }
                ?: throw ConnectorException.Permanent("the instance lists no api version")
        versions[context.connectionId] = newest
        return newest
    }

    private fun recordId(id: String): String {
        if (!RECORD_ID.matches(id)) throw ConnectorException.Permanent("not a Salesforce record id")
        return id
    }

    private fun segment(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    companion object {
        const val ID = "salesforce"
        const val INSTANCE_URL = "instance_url"
        const val API_VERSION = "api_version"

        // 200 quoted 18-character ids keep the query far below soql's length limit
        private const val IDS_PER_QUERY = 200
        private val RECORD_ID = Regex("""^[A-Za-z0-9]{15}(?:[A-Za-z0-9]{3})?$""")
        private val NEXT_URL = Regex("""^/services/data/v\d{2,3}\.\d/query/[A-Za-z0-9-]+$""")
    }
}

// opaque to the platform. since: newest SystemModstamp of a finished cycle; pending: newest seen inside one
internal data class ChangeCheckpoint(
    val since: Instant?,
    val deletedSince: Instant?,
    val next: String?,
    val pending: Instant?
) {
    fun format(): String =
        mapper.writeValueAsString(
            mapOf(
                "since" to since?.toString(),
                "deletedSince" to deletedSince?.toString(),
                "next" to next,
                "pending" to pending?.toString()
            ).filterValues { it != null }
        )

    companion object {
        private val mapper = JsonMapper.builder().build()

        fun parse(text: String?): ChangeCheckpoint {
            if (text == null) return ChangeCheckpoint(null, null, null, null)
            val node =
                runCatching { mapper.readTree(text) }.getOrNull()?.takeIf { it.isObject }
                    ?: throw ConnectorException.Permanent("unreadable Salesforce checkpoint")

            fun instant(key: String) = node.path(key).asString(null)?.let(Instant::parse)
            return ChangeCheckpoint(instant("since"), instant("deletedSince"), node.path("next").asString(null), instant("pending"))
        }
    }
}
