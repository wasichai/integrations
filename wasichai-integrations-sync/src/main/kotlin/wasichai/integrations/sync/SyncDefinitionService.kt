package wasichai.integrations.sync

import wasichai.core.common.NotFoundException
import wasichai.core.common.PreconditionFailedException
import wasichai.core.common.UnprocessableContentException
import wasichai.core.common.ValidationException
import wasichai.integrations.IntegrationsAccess
import java.time.Clock
import java.util.UUID

// definitions are drafts until they validate against both providers; only then may they run
class SyncDefinitionService(
    private val access: IntegrationsAccess,
    private val repository: SyncDefinitionRepository,
    private val validator: DefinitionValidator,
    private val clock: Clock
) {
    suspend fun list(): List<SyncDefinitionView> = repository.list(access.reader().organizationId).map(Companion::view)

    suspend fun get(id: UUID): SyncDefinitionView = view(find(access.reader().organizationId, id))

    suspend fun create(request: SyncDefinitionRequest): SyncDefinitionView {
        val user = access.manager()
        val violations = validator.structural(user.organizationId, request)
        if (violations.isNotEmpty()) throw ValidationException("Invalid sync definition", violations)
        val now = clock.instant()
        val definition = definition(UUID.randomUUID(), user.organizationId, request, DefinitionStatus.DRAFT, 1, now, now)
        repository.insert(definition, user.userId)
        return view(repository.find(user.organizationId, definition.id)!!)
    }

    // a change sends an active definition back to draft: it must validate again before it runs
    suspend fun replace(
        id: UUID,
        request: SyncDefinitionRequest,
        ifMatch: Int?
    ): SyncDefinitionView {
        val user = access.manager()
        val existing = find(user.organizationId, id)
        if (ifMatch == null) throw PreconditionFailedException("If-Match is required to replace a sync definition")
        val violations = validator.structural(user.organizationId, request)
        if (violations.isNotEmpty()) throw ValidationException("Invalid sync definition", violations)
        val updated = definition(id, user.organizationId, request, existing.status, existing.version, existing.createdAt, clock.instant())
        if (!repository.update(updated, ifMatch)) throw PreconditionFailedException("Sync definition $id changed meanwhile")
        if (existing.status == DefinitionStatus.ACTIVE) repository.setStatus(user.organizationId, id, DefinitionStatus.DRAFT, clock.instant())
        return view(repository.find(user.organizationId, id)!!)
    }

    suspend fun delete(id: UUID) {
        val user = access.manager()
        if (!repository.delete(user.organizationId, id)) throw NotFoundException("Sync definition $id not found")
    }

    suspend fun validate(id: UUID): ValidationView {
        val user = access.manager()
        val violations = validator.withSchemas(find(user.organizationId, id))
        return ValidationView(violations.isEmpty(), violations)
    }

    suspend fun activate(id: UUID): SyncDefinitionView {
        val user = access.manager()
        val violations = validator.withSchemas(find(user.organizationId, id))
        if (violations.isNotEmpty()) throw UnprocessableContentException("The sync definition does not validate", violations)
        repository.setStatus(user.organizationId, id, DefinitionStatus.ACTIVE, clock.instant())
        return view(find(user.organizationId, id))
    }

    suspend fun deactivate(id: UUID): SyncDefinitionView {
        val user = access.manager()
        find(user.organizationId, id)
        repository.setStatus(user.organizationId, id, DefinitionStatus.PAUSED, clock.instant())
        return view(find(user.organizationId, id))
    }

    private suspend fun find(
        organizationId: UUID,
        id: UUID
    ): SyncDefinition = repository.find(organizationId, id) ?: throw NotFoundException("Sync definition $id not found")

    private fun definition(
        id: UUID,
        organizationId: UUID,
        r: SyncDefinitionRequest,
        status: DefinitionStatus,
        version: Int,
        createdAt: java.time.Instant,
        updatedAt: java.time.Instant
    ) = SyncDefinition(
        id,
        organizationId,
        r.name!!.trim(),
        SideRef(r.a!!.connectionId, r.a.resource!!.trim(), r.a.externalIdField?.trim()),
        SideRef(r.b!!.connectionId, r.b.resource!!.trim(), r.b.externalIdField?.trim()),
        r.direction!!,
        status,
        r.deletePolicy,
        r.scheduleSeconds,
        r.reconcileSeconds,
        r.matchOn,
        r.mappings.map { it.copy(aField = it.aField!!.trim(), bField = it.bField!!.trim()) },
        version,
        createdAt,
        updatedAt
    )

    companion object {
        fun view(d: SyncDefinition) =
            SyncDefinitionView(
                d.id,
                d.name,
                d.a,
                d.b,
                d.direction,
                d.status,
                d.deletePolicy,
                d.scheduleSeconds,
                d.reconcileSeconds,
                d.matchOn,
                d.mappings,
                d.version,
                d.createdAt,
                d.updatedAt
            )
    }
}
