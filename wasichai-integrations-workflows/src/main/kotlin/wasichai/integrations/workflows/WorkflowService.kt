package wasichai.integrations.workflows

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.PreconditionFailedException
import wasichai.core.common.UnprocessableContentException
import wasichai.core.common.ValidationException
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.Transactions
import java.time.Clock
import java.util.UUID

// a draft is saved as it is (an editor saves half-built graphs); it must validate to be published, and what is
// published is an immutable, numbered version. nothing runs yet (#42)
class WorkflowService(
    private val access: IntegrationsAccess,
    private val repository: WorkflowRepository,
    private val validator: WorkflowValidator,
    private val transactions: Transactions,
    private val json: JsonMapper,
    private val clock: Clock
) {
    suspend fun list(): List<WorkflowView> = repository.list(access.reader().organizationId).map(::view)

    suspend fun get(id: UUID): WorkflowView = view(find(access.reader().organizationId, id))

    suspend fun create(request: WorkflowRequest): WorkflowView {
        val user = access.manager()
        val (name, graph) = checked(request)
        val id = UUID.randomUUID()
        repository.insert(id, user.organizationId, name, graph, user.userId, clock.instant())
        return view(find(user.organizationId, id))
    }

    // a published version is never touched: only the draft changes
    suspend fun update(
        id: UUID,
        request: WorkflowRequest,
        ifMatch: Int?
    ): WorkflowView {
        val user = access.manager()
        find(user.organizationId, id)
        if (ifMatch == null) throw PreconditionFailedException("If-Match is required to update a workflow")
        val (name, graph) = checked(request)
        if (!repository.updateDraft(user.organizationId, id, name, graph, ifMatch, clock.instant())) {
            throw PreconditionFailedException("Workflow $id changed meanwhile")
        }
        return view(find(user.organizationId, id))
    }

    suspend fun delete(id: UUID) {
        val user = access.manager()
        if (!repository.delete(user.organizationId, id)) throw NotFoundException("Workflow $id not found")
    }

    suspend fun validate(id: UUID): ValidationView {
        val user = access.manager()
        val violations = validator.validate(user.organizationId, find(user.organizationId, id).graph)
        return ValidationView(violations.isEmpty(), violations)
    }

    // validates outside the transaction (it may call providers), then publishes the very draft it validated
    suspend fun publish(
        id: UUID,
        ifMatch: Int?
    ): WorkflowVersionView {
        val user = access.manager()
        val definition = find(user.organizationId, id)
        if (ifMatch != null && ifMatch != definition.version) throw PreconditionFailedException("Workflow $id changed meanwhile")
        if (definition.draftPublished) throw ConflictException("The draft is already published as version ${definition.publishedVersion}")
        val violations = validator.validate(user.organizationId, definition.graph)
        if (violations.isNotEmpty()) throw UnprocessableContentException("The workflow does not validate", violations)
        val graph = WorkflowGraph.parse(definition.graph, mutableListOf())!!
        val number =
            transactions.run {
                val current = repository.lock(user.organizationId, id) ?: throw NotFoundException("Workflow $id not found")
                if (current != definition.version) throw PreconditionFailedException("Workflow $id changed while it was validated; publish again")
                repository.publish(user.organizationId, id, definition.graph, graph.trigger, user.userId, clock.instant())
            }
        return repository.version(user.organizationId, id, number)!!
    }

    suspend fun versions(id: UUID): List<WorkflowVersionSummary> {
        val user = access.reader()
        find(user.organizationId, id)
        return repository.versions(user.organizationId, id)
    }

    suspend fun version(
        id: UUID,
        number: Int
    ): WorkflowVersionView {
        val user = access.reader()
        find(user.organizationId, id)
        return repository.version(user.organizationId, id, number) ?: throw NotFoundException("Version $number of workflow $id not found")
    }

    private fun checked(request: WorkflowRequest): Pair<String, JsonNode> {
        val v = mutableListOf<FieldViolation>()
        val name = request.name?.trim().orEmpty()
        if (name.isEmpty() || name.length > 200) v += FieldViolation("name", "1 to 200 characters")
        val graph = request.graph?.takeUnless { it.isNull } ?: json.createObjectNode()
        if (!graph.isObject) {
            v += FieldViolation("graph", "an object with a trigger and steps")
        } else if (json.writeValueAsBytes(graph).size > MAX_GRAPH_BYTES) {
            v += FieldViolation("graph", "at most ${MAX_GRAPH_BYTES / 1024} KiB")
        }
        if (v.isNotEmpty()) throw ValidationException("Invalid workflow", v)
        return name to graph
    }

    private suspend fun find(
        organizationId: UUID,
        id: UUID
    ): WorkflowDefinition = repository.find(organizationId, id) ?: throw NotFoundException("Workflow $id not found")

    companion object {
        const val MAX_GRAPH_BYTES = 256 * 1024

        fun view(d: WorkflowDefinition) =
            WorkflowView(d.id, d.name, d.status, d.graph, d.publishedVersion, !d.draftPublished, d.version, d.createdAt, d.updatedAt)
    }
}
