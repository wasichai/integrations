package wasichai.integrations.sync

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.integrations.IntegrationsAccess
import java.time.Clock
import java.util.UUID

data class RunRequest(
    // INCREMENTAL (default), INITIAL (cursors from the start), RECONCILE (read everything, keep cursors)
    val kind: String? = null
)

data class RunStarted(
    val runId: UUID
)

data class ResolveRequest(
    // A, B, or CUSTOM with values keyed by A's field names and/or a side per mapping key ("A" | "B")
    val choice: String? = null,
    val values: Map<String, JsonNode> = emptyMap(),
    val fields: Map<String, String> = emptyMap()
)

class SyncOperationsService(
    private val access: IntegrationsAccess,
    private val definitions: SyncDefinitionRepository,
    private val state: SyncStateRepository,
    private val runs: SyncRunRepository,
    private val engine: SyncEngine,
    private val clock: Clock
) {
    suspend fun run(
        definitionId: UUID,
        request: RunRequest
    ): RunStarted {
        val user = access.manager()
        val definition = definitions.find(user.organizationId, definitionId) ?: throw NotFoundException("Sync definition $definitionId not found")
        if (definition.status != DefinitionStatus.ACTIVE) throw ConflictException("Only an ACTIVE sync definition runs")
        val trigger =
            when (request.kind?.uppercase()) {
                null, "", "INCREMENTAL" -> RunTrigger.MANUAL
                "INITIAL" -> RunTrigger.INITIAL
                "RECONCILE" -> RunTrigger.RECONCILE
                else -> throw ValidationException("Invalid run", "kind", "INCREMENTAL, INITIAL or RECONCILE")
            }
        val runId =
            engine.enqueue(user.organizationId, definitionId, trigger) ?: throw ConflictException("A run of this definition is already waiting or running")
        return RunStarted(runId)
    }

    suspend fun runs(
        definitionId: UUID,
        limit: Int?
    ): List<SyncRunView> {
        val user = access.reader()
        definitions.find(user.organizationId, definitionId) ?: throw NotFoundException("Sync definition $definitionId not found")
        return runs.runs(user.organizationId, definitionId, limit(limit))
    }

    suspend fun conflicts(
        status: String?,
        definitionId: UUID?,
        limit: Int?
    ): List<ConflictView> {
        val user = access.reader()
        val parsed =
            status?.takeIf { it.isNotBlank() }?.let { s ->
                ConflictStatus.entries.firstOrNull { it.name == s.uppercase() } ?: throw ValidationException("Invalid filter", "status", "OPEN or RESOLVED")
            }
        return runs.conflicts(user.organizationId, parsed, definitionId, limit(limit))
    }

    suspend fun conflict(id: UUID): ConflictView {
        val user = access.reader()
        return runs.conflict(user.organizationId, id) ?: throw NotFoundException("Conflict $id not found")
    }

    suspend fun resolve(
        id: UUID,
        request: ResolveRequest
    ): ConflictView {
        val user = access.manager()
        val conflict = runs.conflict(user.organizationId, id) ?: throw NotFoundException("Conflict $id not found")
        if (conflict.status == ConflictStatus.RESOLVED) throw ConflictException("Conflict $id is already resolved")
        val link =
            conflict.linkId?.let { state.find(user.organizationId, it) } ?: throw NotFoundException("Conflict $id has no link anymore")
        val now = clock.instant()
        // a deleted record or a create nobody can recover: resolving forgets the pair
        val unlinks = conflict.kind != ConflictKind.FIELDS
        val choice =
            if (unlinks) {
                null
            } else {
                when (request.choice?.uppercase()) {
                    "A" -> Side.A
                    "B" -> Side.B
                    "CUSTOM" -> null
                    else -> throw ValidationException("Invalid resolution", "choice", "A, B or CUSTOM")
                }
            }
        if (!unlinks) {
            val definition = definitions.find(user.organizationId, conflict.definitionId) ?: throw NotFoundException("Sync definition not found")
            engine.applyResolution(definition, link, conflict, choice, request.values, request.fields)
        }
        val resolution = JsonNodeFactory.instance.objectNode().put("choice", if (unlinks) "UNLINK" else request.choice)
        if (request.values.isNotEmpty()) {
            resolution.set(
                "values",
                JsonNodeFactory.instance.objectNode().apply {
                    request.values.forEach { (k, v) ->
                        set(k, v)
                    }
                }
            )
        }
        if (request.fields.isNotEmpty()) {
            resolution.set("fields", JsonNodeFactory.instance.objectNode().apply { request.fields.forEach { (k, v) -> put(k, v.uppercase()) } })
        }
        // the conflict is closed first: forgetting the pair afterwards keeps it as history (link_id becomes null)
        if (!runs.resolve(id, resolution, user.userId, now)) throw ConflictException("Conflict $id was resolved meanwhile")
        if (unlinks) state.delete(link.id)
        return runs.conflict(user.organizationId, id)!!
    }

    suspend fun status(definitionId: UUID): SyncStatusView {
        val user = access.reader()
        val org = user.organizationId
        val definition = definitions.find(org, definitionId) ?: throw NotFoundException("Sync definition $definitionId not found")
        val now = clock.instant()
        val lastRun = runs.runs(org, definitionId, 1).firstOrNull()
        // the latest finished run tells whether it is failing now; a later success clears the error
        val lastFinished = runs.runs(org, definitionId, 20).firstOrNull { it.finishedAt != null }
        val failing = lastFinished?.takeIf { it.status == RunStatus.FAILED }
        val candidate = if (definition.status == DefinitionStatus.ACTIVE) runs.scheduleCandidates(org, definitionId).firstOrNull() else null
        return SyncStatusView(
            definitionId = definition.id,
            status = definition.status,
            lastRun = lastRun,
            lastSuccessAt = runs.lastSuccessAt(org, definitionId),
            lastError = failing?.error,
            errorKind = failing?.errorKind,
            pendingConflicts = runs.openConflicts(org, definitionId),
            links = state.linkCounts(definitionId),
            cursors = state.cursors(definitionId),
            nextScheduledAt = candidate?.let { SyncSchedule.nextIncremental(it, now) },
            nextReconcileAt = candidate?.let { SyncSchedule.nextReconcile(it, now) },
            queuedOrRunning = runs.active(org, definitionId)
        )
    }

    private fun limit(limit: Int?): Int = (limit ?: 50).also { if (it !in 1..200) throw ValidationException("Invalid limit", "limit", "1 to 200") }
}

@RestController
@RequestMapping("/api/integrations")
class SyncOperationsController(
    private val operations: SyncOperationsService
) {
    @PostMapping("/sync-definitions/{id}/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    suspend fun run(
        @PathVariable id: UUID,
        @RequestBody(required = false) request: RunRequest?
    ): RunStarted = operations.run(id, request ?: RunRequest())

    @GetMapping("/sync-definitions/{id}/runs")
    suspend fun runs(
        @PathVariable id: UUID,
        @RequestParam(required = false) limit: Int?
    ): List<SyncRunView> = operations.runs(id, limit)

    @GetMapping("/sync-definitions/{id}/status")
    suspend fun status(
        @PathVariable id: UUID
    ): SyncStatusView = operations.status(id)

    @GetMapping("/conflicts")
    suspend fun conflicts(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) definitionId: UUID?,
        @RequestParam(required = false) limit: Int?
    ): List<ConflictView> = operations.conflicts(status, definitionId, limit)

    @GetMapping("/conflicts/{id}")
    suspend fun conflict(
        @PathVariable id: UUID
    ): ConflictView = operations.conflict(id)

    @PostMapping("/conflicts/{id}/resolve")
    suspend fun resolve(
        @PathVariable id: UUID,
        @RequestBody request: ResolveRequest
    ): ConflictView = operations.resolve(id, request)
}
