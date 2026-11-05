package wasichai.integrations.connector.openapi

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
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
import wasichai.integrations.sdk.PaginationStrategy
import wasichai.integrations.sdk.ProviderInfo
import wasichai.integrations.sdk.ProviderRecord
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.ProviderResponse
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.RecordPage
import wasichai.integrations.sdk.ResourceDeclaration
import wasichai.integrations.sdk.ResourceDescriptor
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceSchema
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.SdkVersion
import wasichai.integrations.sdk.SyncDirectionSupport
import wasichai.integrations.sdk.WriteResult
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

// a rest api described by an openapi 3 document, exposed as resources through an explicit mapping (OpenApiConfig).
// the connector implements exactly the capability interfaces its mapping provides: the manifest validator checks
// interfaces against declarations, so of() picks one of four shapes (ADR-0021)
object OpenApiConnector {
    // throws IllegalArgumentException listing every problem of the config
    fun of(config: OpenApiConfig): Connector {
        val engine = OpenApiEngine(OpenApiMapping.of(config))
        val feed =
            engine.mapping.resources.values
                .any { it.incremental }
        val write =
            engine.mapping.resources.values
                .any { it.writable }
        return when {
            feed && write -> ReadFeedWrite(engine)
            feed -> ReadFeed(engine)
            write -> ReadWrite(engine)
            else -> ReadOnly(engine)
        }
    }

    private class ReadOnly(
        engine: OpenApiEngine
    ) : Connector by engine,
        ResourceDiscovery by engine,
        ResourceReader by engine

    private class ReadFeed(
        engine: OpenApiEngine
    ) : Connector by engine,
        ResourceDiscovery by engine,
        ResourceReader by engine,
        ChangeFeed by engine

    private class ReadWrite(
        engine: OpenApiEngine
    ) : Connector by engine,
        ResourceDiscovery by engine,
        ResourceReader by engine,
        ResourceWriter by engine

    private class ReadFeedWrite(
        engine: OpenApiEngine
    ) : Connector by engine,
        ResourceDiscovery by engine,
        ResourceReader by engine,
        ChangeFeed by engine,
        ResourceWriter by engine
}

// every capability's code; never registered itself, only through the shapes above
internal class OpenApiEngine(
    val mapping: OpenApiMapping
) : Connector,
    ResourceDiscovery,
    ResourceReader,
    ChangeFeed,
    ResourceWriter {
    private val config = mapping.config
    private val resources = mapping.resources.values

    override val manifest: ConnectorManifest = manifest()

    private fun manifest(): ConnectorManifest {
        val feed = resources.any { it.incremental }
        val write = resources.any { it.writable }
        val capabilities =
            listOfNotNull(
                Capability.DISCOVERY,
                Capability.READ,
                Capability.CHANGE_FEED.takeIf { feed },
                Capability.WRITE.takeIf { write }
            ).associateWith { config.maturity }
        val info = mapping.document.root.path("info")
        val title = info.path("title").asString("")
        return ConnectorManifest(
            id = config.id,
            provider =
                ProviderInfo(
                    config.providerName ?: title,
                    documentation =
                        mapping.document.root
                            .path("externalDocs")
                            .path("url")
                            .asString(null)
                ),
            version = config.version,
            sdkVersion = SdkVersion.CURRENT,
            description =
                "$title through its OpenAPI document, mapped to: " +
                    resources.joinToString("; ") { r ->
                        val ops =
                            listOfNotNull(
                                "read",
                                "incremental read".takeIf { r.incremental },
                                r.create?.let { "create" },
                                r.update?.let { "update" },
                                r.delete?.let { "delete" }
                            )
                        "${r.name} (${ops.joinToString(", ")})"
                    } + ". No webhooks; deletions are not in the change feed.",
            authMethods = listOf(mapping.authMethod),
            capabilities = capabilities,
            resources = resources.map { ResourceDeclaration(it.name, it.config.label) },
            syncDirections = setOfNotNull(SyncDirectionSupport.SOURCE, SyncDirectionSupport.TARGET.takeIf { write }),
            pagination =
                resources
                    .mapNotNull {
                        when (it.config.pagination) {
                            OpenApiPagination.None -> null
                            is OpenApiPagination.Page -> PaginationStrategy.OFFSET
                            is OpenApiPagination.Cursor -> PaginationStrategy.CURSOR
                            is OpenApiPagination.NextUrl -> PaginationStrategy.NEXT_URL
                        }
                    }.toSet(),
            rateLimit = config.rateLimit
        )
    }

    // ---- discovery: the mapping, no call

    override suspend fun listResources(context: ConnectorContext): List<ResourceDescriptor> =
        resources.map {
            ResourceDescriptor(
                it.name,
                it.config.label,
                it.get.node
                    .path("summary")
                    .asString(null)
            )
        }

    override suspend fun describe(
        context: ConnectorContext,
        resource: String
    ): ResourceSchema = resource(resource).schema

    // ---- reads

    override suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord? {
        val r = resource(resource)
        val response = exchange(context, ProviderRequest(r.get.method, url(r.get, id)))
        // absent or gone is an answer. the document cannot tell a missing record from a wrong path (ADR-0021)
        if (response.status == 404 || response.status == 410) return null
        return record(r, checked(response).json())
    }

    override suspend fun list(
        context: ConnectorContext,
        request: ReadRequest
    ): RecordPage {
        val r = resource(request.resource)
        val (items, next) = page(context, r, request.cursor, request.pageSize, since = null)
        return RecordPage(items.map { record(r, it) }, next)
    }

    // a pass pages through every record updated at or after `since`; when it ends, the next pass starts at the newest
    // updated-at it saw. the boundary instant is read again (at least once; the engine drops repeats by content hash).
    // deletions are not reported: reconciliation finds them
    override suspend fun changes(
        context: ConnectorContext,
        resource: String,
        checkpoint: String?,
        pageSize: Int
    ): ChangePage {
        val r = resource(resource)
        if (!r.incremental) throw ConnectorException.Permanent("resource '$resource' has no incremental read in its mapping")
        val state = OpenApiCheckpoint.parse(checkpoint)
        val (items, next) = page(context, r, state.cursor, pageSize, state.since)
        val records = items.map { record(r, it) }
        val newest = (records.mapNotNull { it.modifiedAt } + listOfNotNull(state.newest)).maxOrNull()
        val changes = records.map { Change.Upserted(it) }
        if (next != null) return ChangePage(changes, OpenApiCheckpoint(state.since, next, newest).render(), true)
        return ChangePage(changes, OpenApiCheckpoint(newest ?: state.since).render(), false)
    }

    // ---- writes

    override suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        val r = resource(resource)
        val operation = r.create ?: throw ConnectorException.Permanent("resource '$resource' has no create operation in its mapping")
        val body = OpenApiValues.write(r.schema, fields)
        val answer = checked(exchange(context, jsonRequest(operation, url(operation), body))).body
        // without the record in the answer the new id is unknown: a retry could create a second one
        val created =
            runCatching { json.readTree(answer) }.getOrNull()?.takeIf { it.isObject }
                ?: throw ConnectorException.Permanent("'${operation.id}' answered without the created record")
        return written(r, created, fallbackId = null, created = true)
    }

    // the mapping has no conditional write: with an expected version the record is read and compared on its
    // updated-at first. the race is narrowed, not closed (ADR-0009)
    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): WriteResult {
        val r = resource(resource)
        val operation = r.update ?: throw ConnectorException.Permanent("resource '$resource' has no update operation in its mapping")
        val body = OpenApiValues.write(r.schema, fields)
        if (expectedVersion != null) {
            if (r.config.updatedAtField == null) throw ConnectorException.Permanent("resource '$resource' has no updatedAtField to check a version against")
            val current = read(context, resource, id) ?: throw ConnectorException.NotFound("$resource '$id' is gone")
            if (current.version != expectedVersion) throw ConnectorException.Conflict("$resource '$id' changed since $expectedVersion", current.version)
        }
        val answer = checked(exchange(context, jsonRequest(operation, url(operation, id), body))).body
        // a 204 says nothing about the new version: the next read does
        val updated = runCatching { json.readTree(answer) }.getOrNull()?.takeIf { it.isObject }
        return if (updated == null) WriteResult(id, null, null, created = false) else written(r, updated, fallbackId = id, created = false)
    }

    // deleting what is already gone is done
    override suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    ) {
        val r = resource(resource)
        val operation = r.delete ?: throw ConnectorException.Permanent("resource '$resource' has no delete operation in its mapping")
        val response = exchange(context, ProviderRequest(operation.method, url(operation, id)))
        if (response.status == 404 || response.status == 410) return
        checked(response)
    }

    // ---- plumbing

    private fun resource(name: String): OpenApiMapping.Resource = mapping.resources[name] ?: throw ConnectorException.NotFound("resource '$name' is not mapped")

    // one page of the list operation and the cursor of the next one, in the resource's pagination style
    private suspend fun page(
        context: ConnectorContext,
        r: OpenApiMapping.Resource,
        cursor: String?,
        pageSize: Int,
        since: Instant?
    ): Pair<List<JsonNode>, String?> {
        val query = mutableMapOf<String, String>()
        r.config.updatedSinceParam?.let { param -> since?.let { query[param] = it.toString() } }
        val pagination = r.config.pagination
        val response =
            when (pagination) {
                OpenApiPagination.None -> get(context, url(r.list), query, r.list.method)
                is OpenApiPagination.Page -> {
                    val page = cursor?.let { it.toIntOrNull() ?: throw ConnectorException.Permanent("not a page number: '$it'") } ?: pagination.firstPage
                    query[pagination.pageParam] = "$page"
                    pagination.limitParam?.let { query[it] = "$pageSize" }
                    get(context, url(r.list), query, r.list.method)
                }
                is OpenApiPagination.Cursor -> {
                    cursor?.let { query[pagination.cursorParam] = it }
                    pagination.limitParam?.let { query[it] = "$pageSize" }
                    get(context, url(r.list), query, r.list.method)
                }
                is OpenApiPagination.NextUrl ->
                    if (cursor != null) {
                        get(context, underBase(cursor))
                    } else {
                        pagination.limitParam?.let { query[it] = "$pageSize" }
                        get(context, url(r.list), query, r.list.method)
                    }
            }
        val body = response.json()
        val items = path(body, r.config.itemsField)
        if (!items.isArray) throw ConnectorException.Permanent("'${r.list.id}' answered no array at '${r.config.itemsField ?: "the top level"}'")
        val records = items.values().toList()
        val next =
            when (pagination) {
                OpenApiPagination.None -> null
                is OpenApiPagination.Page -> {
                    val current = cursor?.toInt() ?: pagination.firstPage
                    val full = if (pagination.limitParam != null) records.size >= pageSize else records.isNotEmpty()
                    if (full) "${current + 1}" else null
                }
                is OpenApiPagination.Cursor -> path(body, pagination.cursorField).asString(null)?.takeIf { it.isNotEmpty() }
                is OpenApiPagination.NextUrl ->
                    (if (pagination.nextField != null) path(body, pagination.nextField).asString(null) else nextLink(response.header("Link")))
                        ?.takeIf { it.isNotEmpty() }
                        ?.let(::underBase)
            }
        return records to next
    }

    private fun record(
        r: OpenApiMapping.Resource,
        node: JsonNode
    ): ProviderRecord {
        val id = id(r, node) ?: throw ConnectorException.Permanent("a ${r.name} record has no '${r.config.idField}'")
        val updated = r.config.updatedAtField?.let { node.path(it) }
        return ProviderRecord(id, updated?.asString(null), updated?.let(OpenApiValues::instant), OpenApiValues.read(r.schema, node), node)
    }

    private fun written(
        r: OpenApiMapping.Resource,
        node: JsonNode,
        fallbackId: String?,
        created: Boolean
    ): WriteResult {
        val id = id(r, node) ?: fallbackId ?: throw ConnectorException.Permanent("the created ${r.name} has no '${r.config.idField}'")
        val updated = r.config.updatedAtField?.let { node.path(it) }
        return WriteResult(id, updated?.asString(null), updated?.let(OpenApiValues::instant), created)
    }

    private fun id(
        r: OpenApiMapping.Resource,
        node: JsonNode
    ): String? =
        node
            .path(r.config.idField)
            .takeIf { it.isValueNode && !it.isNull }
            ?.asString()
            ?.takeIf { it.isNotEmpty() }

    // the operation's path under the base url; the id is the one path parameter, encoded as a single segment
    private fun url(
        operation: OpenApiDocument.Operation,
        id: String? = null
    ): String {
        var path = operation.path
        if (id != null) {
            if (id.isEmpty() || id == "." || id == "..") throw ConnectorException.Permanent("not a record id: '$id'")
            val segment = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20")
            path = path.replace("{${operation.pathParameters().single().name}}", segment)
        }
        return mapping.baseUrl + path
    }

    // a url the provider handed back is followed only under the base url: the platform adds the credentials to it
    private fun underBase(url: String): String {
        val base = URI.create(mapping.baseUrl)
        val candidate = runCatching { URI.create(url) }.getOrNull()
        val ok =
            candidate != null &&
                candidate.scheme == base.scheme &&
                candidate.rawUserInfo == null &&
                candidate.host.equals(base.host, ignoreCase = true) &&
                candidate.port == base.port &&
                ((candidate.rawPath ?: "") + "/").startsWith((base.rawPath ?: "").trimEnd('/') + "/")
        if (!ok) throw ConnectorException.Permanent("the provider sent a next url outside the base url")
        return url
    }

    private fun path(
        node: JsonNode,
        dotted: String?
    ): JsonNode = dotted?.split('.')?.fold(node) { at, part -> at.path(part) } ?: node

    private suspend fun get(
        context: ConnectorContext,
        url: String,
        query: Map<String, String> = emptyMap(),
        method: HttpMethod = HttpMethod.GET
    ): ProviderResponse = checked(exchange(context, ProviderRequest(method, url, query = query)))

    private suspend fun exchange(
        context: ConnectorContext,
        request: ProviderRequest
    ): ProviderResponse = context.http.exchange(request.copy(headers = request.headers + ("Accept" to "application/json")))

    private fun jsonRequest(
        operation: OpenApiDocument.Operation,
        url: String,
        body: JsonNode
    ) = ProviderRequest.json(operation.method, url, body).copy(contentType = mapping.document.requestMediaType(operation))

    private fun checked(response: ProviderResponse): ProviderResponse {
        if (!response.successful) throw OpenApiErrors.classify(response)
        return response
    }

    companion object {
        private val json = JsonMapper.builder().build()
        private val LINK = Regex("""<([^>]*)>\s*;\s*rel="([^"]*)"""")

        // rfc 8288: <url>; rel="next"
        fun nextLink(header: String?): String? =
            header?.let { value ->
                LINK
                    .findAll(value)
                    .firstOrNull { it.groupValues[2].split(' ').contains("next") }
                    ?.groupValues
                    ?.get(1)
            }
    }
}
