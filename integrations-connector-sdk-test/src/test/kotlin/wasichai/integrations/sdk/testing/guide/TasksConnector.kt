package wasichai.integrations.sdk.testing.guide

import tools.jackson.databind.JsonNode
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.ConnectorManifest
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpErrors
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
import wasichai.integrations.sdk.ResourceDeclaration
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.SdkVersion
import wasichai.integrations.sdk.SyncDirectionSupport
import wasichai.integrations.sdk.WriteResult
import java.time.Instant

// the example of docs/guides/connector-developer.md, compiled and tested so the guide cannot drift from the sdk.
// "tasks" is a made-up provider: GET/PATCH/DELETE /tasks/{id}, GET and POST /tasks, an api key, a rev per task.
class TasksConnector(
    private val baseUrl: String = "https://api.tasks.example"
) : Connector,
    ResourceReader,
    ResourceWriter {
    override val manifest =
        ConnectorManifest(
            id = "tasks",
            provider = ProviderInfo("Tasks", "https://tasks.example", "https://tasks.example/docs/api"),
            version = "0.1.0",
            sdkVersion = SdkVersion.CURRENT,
            description = "Tasks of a Tasks workspace: read, create, update and delete.",
            authMethods = listOf(AuthMethod.ApiKey()),
            capabilities =
                mapOf(
                    Capability.READ to Maturity.TESTED,
                    Capability.WRITE to Maturity.TESTED
                ),
            resources = listOf(ResourceDeclaration("task", "Task")),
            syncDirections = setOf(SyncDirectionSupport.SOURCE, SyncDirectionSupport.TARGET),
            pagination = setOf(PaginationStrategy.CURSOR),
            rateLimit = RateLimitHint(requestsPerSecond = 5.0, burst = 10, scope = "connection")
        )

    override suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord? {
        val response = context.http.exchange(ProviderRequest(HttpMethod.GET, "$baseUrl/tasks/${safe(id)}"))
        // gone is an answer, not a failure: the sync engine treats null as a deletion
        if (response.status == 404) return null
        return record(checked(response).json())
    }

    override suspend fun list(
        context: ConnectorContext,
        request: ReadRequest
    ): RecordPage {
        val query = mapOf("limit" to request.pageSize.toString()) + (request.cursor?.let { mapOf("cursor" to it) } ?: emptyMap())
        val page = checked(context.http.exchange(ProviderRequest(HttpMethod.GET, "$baseUrl/tasks", query = query))).json()
        val next = page.path("next").takeUnless { it.isNull || it.isMissingNode }?.asString()
        val items = page.path("items")
        return RecordPage((0 until items.size()).map { record(items.get(it)) }, next)
    }

    override suspend fun create(
        context: ConnectorContext,
        resource: String,
        fields: Map<String, FieldValue>
    ): WriteResult {
        val created = checked(context.http.exchange(ProviderRequest.json(HttpMethod.POST, "$baseUrl/tasks", body(fields)))).json()
        return result(created, created = true)
    }

    // the provider checks If-Match itself: a 412 becomes ConnectorException.Conflict through HttpErrors
    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): WriteResult {
        val headers = expectedVersion?.let { mapOf("If-Match" to it) } ?: emptyMap()
        val request = ProviderRequest.json(HttpMethod.PATCH, "$baseUrl/tasks/${safe(id)}", body(fields), headers)
        return result(checked(context.http.exchange(request)).json(), created = false)
    }

    override suspend fun delete(
        context: ConnectorContext,
        resource: String,
        id: String
    ) {
        checked(context.http.exchange(ProviderRequest(HttpMethod.DELETE, "$baseUrl/tasks/${safe(id)}")))
    }

    private fun record(task: JsonNode): ProviderRecord {
        val updated = task.path("updated_at").asString()
        return ProviderRecord(
            id = task.path("id").asString(),
            version = task.path("rev").asString(),
            modifiedAt = Instant.parse(updated),
            fields =
                mapOf(
                    "title" to FieldValue.Text(task.path("title").asString()),
                    "done" to FieldValue.Bool(task.path("done").asBoolean())
                ),
            native = task
        )
    }

    private fun result(
        task: JsonNode,
        created: Boolean
    ) = WriteResult(task.path("id").asString(), task.path("rev").asString(), Instant.parse(task.path("updated_at").asString()), created)

    // typed values back to the provider's json; anything this provider cannot store is refused, not guessed
    private fun body(fields: Map<String, FieldValue>): Map<String, Any?> =
        fields.mapValues { (name, value) ->
            when (value) {
                is FieldValue.Text -> value.value
                is FieldValue.Bool -> value.value
                FieldValue.Null -> null
                else -> throw ConnectorException.Permanent("tasks cannot store ${value.javaClass.simpleName} in '$name'", mapOf("field" to name))
            }
        }

    private fun checked(response: ProviderResponse): ProviderResponse {
        if (!response.successful) throw HttpErrors.classify(response)
        return response
    }

    // ids reach urls: accept only what the provider issues
    private fun safe(id: String): String {
        if (!ID.matches(id)) throw ConnectorException.Permanent("not a task id: '$id'")
        return id
    }

    companion object {
        private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
