package wasichai.integrations.ai

import org.springframework.dao.DataIntegrityViolationException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.PreconditionFailedException
import wasichai.core.common.ValidationException
import wasichai.core.identity.CurrentUser
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.ProviderErrors
import wasichai.integrations.Transactions
import wasichai.integrations.sdk.ActionExecutor
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceSchema
import wasichai.integrations.sdk.ResourceUpserter
import wasichai.integrations.sdk.ResourceWriter
import java.time.Clock
import java.util.UUID

// the tenant's exposed tools (ADR-0026). nothing is a tool until a tenant administrator exposes it; exposing checks
// the connection, what its connector declares and implements, the resource and the input schema
class ToolCatalog(
    private val access: IntegrationsAccess,
    private val currentUser: CurrentUser,
    private val repository: ToolRepository,
    private val registry: ConnectorRegistry,
    private val connections: ConnectionRepository,
    private val contexts: ConnectorContexts,
    private val limits: ToolRateLimits,
    private val audit: AuditService,
    private val transactions: Transactions,
    private val json: JsonMapper,
    private val clock: Clock
) {
    // a manager sees every tool; anyone else the enabled tools a role of theirs may call
    suspend fun list(): List<ToolView> {
        val user = access.reader()
        val manager = currentUser.hasPermission(user, Actions.MANAGE_ORGANIZATION)
        return repository
            .list(user.organizationId)
            .filter { manager || (it.enabled && it.allowedRoles.any { r -> r in user.roles }) }
            .map(::view)
    }

    suspend fun get(name: String): ToolView {
        val user = access.reader()
        val tool = repository.findByName(user.organizationId, name)
        val visible =
            tool != null &&
                (currentUser.hasPermission(user, Actions.MANAGE_ORGANIZATION) || (tool.enabled && tool.allowedRoles.any { it in user.roles }))
        if (!visible) throw NotFoundException("Tool '$name' not found")
        return view(tool)
    }

    suspend fun create(request: ToolRequest): ToolView {
        val user = access.manager()
        val now = clock.instant()
        val tool = checked(user.organizationId, request, UUID.randomUUID(), now)
        unique(tool.name) {
            transactions.run {
                repository.insert(tool, user.userId)
                audit.record(user.organizationId, user.userId, AUDIT_OBJECT, tool.id, AuditOperation.CREATE, after = view(tool))
            }
        }
        return view(repository.find(user.organizationId, tool.id)!!)
    }

    suspend fun update(
        name: String,
        request: ToolRequest,
        ifMatch: Int?
    ): ToolView {
        val user = access.manager()
        val existing = find(user.organizationId, name)
        if (ifMatch == null) throw PreconditionFailedException("If-Match is required to update a tool")
        val tool = checked(user.organizationId, request, existing.id, existing.createdAt).copy(updatedAt = clock.instant())
        val updated =
            unique(tool.name) {
                transactions.run {
                    repository.update(tool, ifMatch).also {
                        if (it) audit.record(user.organizationId, user.userId, AUDIT_OBJECT, tool.id, AuditOperation.UPDATE, view(existing), view(tool))
                    }
                }
            }
        if (!updated) throw PreconditionFailedException("Tool '$name' changed meanwhile")
        limits.forget(existing.id.toString())
        return view(repository.find(user.organizationId, existing.id)!!)
    }

    // its executions stay, with its name; one still waiting for approval can only be rejected
    suspend fun delete(name: String) {
        val user = access.manager()
        val tool = find(user.organizationId, name)
        transactions.run {
            if (!repository.delete(user.organizationId, tool.id)) throw NotFoundException("Tool '$name' not found")
            audit.record(user.organizationId, user.userId, AUDIT_OBJECT, tool.id, AuditOperation.DELETE, before = view(tool))
        }
        limits.forget(tool.id.toString())
    }

    private suspend fun <T> unique(
        name: String,
        block: suspend () -> T
    ): T =
        try {
            block()
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("A tool named '$name' already exists")
        }

    private suspend fun find(
        organizationId: UUID,
        name: String
    ): Tool = repository.findByName(organizationId, name) ?: throw NotFoundException("Tool '$name' not found")

    // every violation at once; a provider is called only once everything offline holds
    private suspend fun checked(
        organizationId: UUID,
        r: ToolRequest,
        id: UUID,
        createdAt: java.time.Instant
    ): Tool {
        val v = mutableListOf<FieldViolation>()
        val name = r.name?.trim().orEmpty()
        if (!NAME.matches(name)) v += FieldViolation("name", "lower case letters, digits and '_', starting with a letter, at most 64")
        val description = r.description?.trim().orEmpty()
        if (description.isEmpty() || description.length > 1000) v += FieldViolation("description", "1 to 1000 characters: what the tool does, for the model")
        val rate = r.rateLimitPerMinute ?: DEFAULT_RATE
        if (rate !in 1..10000) v += FieldViolation("rateLimitPerMinute", "1 to 10000 calls a minute")
        // wasichai keeps role names upper case
        val roles =
            r.allowedRoles
                .orEmpty()
                .map { it.trim().uppercase() }
                .distinct()
        if (roles.size > 50) {
            v += FieldViolation("allowedRoles", "at most 50 roles")
        } else {
            val known = repository.existingRoles(organizationId, roles)
            roles.forEachIndexed { i, role -> if (role !in known) v += FieldViolation("allowedRoles[$i]", "no role '$role' in this organization") }
        }
        val connection = r.connectionId?.let { connections.find(organizationId, it) }
        val connector = connection?.let { registry.find(it.connectorId) }
        when {
            r.connectionId == null || connection == null -> v += FieldViolation("connectionId", "unknown connection")
            connector == null -> v += FieldViolation("connectionId", "the connection's connector is not installed")
        }
        val isAction = r.action != null
        val operation =
            r.operation?.let { o ->
                ToolOperation.entries.firstOrNull { it.name == o.uppercase() }
                    ?: null.also { v += FieldViolation("operation", "one of ${ToolOperation.entries.joinToString { it.name.lowercase() }}") }
            }
        if (isAction == (r.resource != null)) v += FieldViolation("action", "either an action, or a resource with an operation")
        if (!isAction && r.resource != null && r.operation == null) v += FieldViolation("operation", "required with a resource")
        if (isAction && r.operation != null) v += FieldViolation("operation", "only with a resource")
        if (operation == ToolOperation.UPSERT && r.externalIdField.isNullOrBlank()) v += FieldViolation("externalIdField", "required to upsert")
        if (operation != ToolOperation.UPSERT && r.externalIdField != null) v += FieldViolation("externalIdField", "only to upsert")
        if (!isAction && r.inputSchema != null && !r.inputSchema.isNull) {
            v += FieldViolation("inputSchema", "a record tool's input is derived from the resource schema: leave it out")
        }
        var schema: JsonNode? = null
        if (isAction) {
            val (checked, violations) = ToolSchema.check(r.inputSchema, json)
            v += violations
            schema = checked
        }
        if (connector != null) {
            val m = connector.manifest
            if (isAction) {
                if (!m.supports(Capability.ACTIONS) || connector !is ActionExecutor) {
                    v += FieldViolation("action", "connector '${m.id}' runs no actions")
                } else if (m.actions.none { it.name == r.action }) {
                    v += FieldViolation("action", "connector '${m.id}' declares no action '${r.action}'")
                }
            } else if (r.resource != null && operation != null) {
                val supported =
                    when (operation) {
                        ToolOperation.READ, ToolOperation.LIST -> m.supports(Capability.READ) && connector is ResourceReader
                        ToolOperation.UPSERT -> m.supports(Capability.UPSERT) && connector is ResourceUpserter
                        else -> m.supports(Capability.WRITE) && connector is ResourceWriter
                    }
                if (!supported) v += FieldViolation("operation", "connector '${m.id}' cannot ${operation.name.lowercase()} records")
                val declared = m.resources.any { it.name == r.resource }
                val open = m.resources.any { it.dynamic } || (m.resources.isEmpty() && connector is ResourceDiscovery)
                if (!declared && !open) v += FieldViolation("resource", "connector '${m.id}' declares no resource '${r.resource}'")
                if (operation.writes && operation != ToolOperation.DELETE && connector !is ResourceDiscovery) {
                    v += FieldViolation("resource", "connector '${m.id}' cannot describe '${r.resource}', so its fields are unknown")
                }
            }
        }
        if (v.isNotEmpty()) throw ValidationException("Invalid tool", v)
        var fieldTypes = emptyMap<String, FieldType>()
        if (!isAction) {
            val described = describe(connection!!, connector!!, r.resource!!)
            if (described == null && connector is ResourceDiscovery) {
                throw ValidationException("Invalid tool", "resource", "resource '${r.resource}' does not exist")
            }
            if (operation == ToolOperation.UPSERT) {
                val field = described?.field(r.externalIdField!!)
                if (field == null || !field.externalId) {
                    throw ValidationException("Invalid tool", "externalIdField", "'${r.externalIdField}' is not an external id field of '${r.resource}'")
                }
            }
            val (derived, types) = ToolSchema.forResource(operation!!, described, r.externalIdField, json)
            schema = derived
            fieldTypes = types
        }
        return Tool(
            id = id,
            organizationId = organizationId,
            connectionId = connection!!.id,
            name = name,
            description = description,
            kind = if (isAction) ToolKind.ACTION else ToolKind.RESOURCE,
            action = r.action,
            resource = r.resource,
            operation = operation,
            externalIdField = r.externalIdField,
            inputSchema = schema!!,
            fieldTypes = fieldTypes,
            allowedRoles = roles,
            // the sensitive default: anything that changes the provider waits for a person
            approvalRequired = r.approvalRequired ?: (isAction || operation!!.writes),
            rateLimitPerMinute = rate,
            enabled = r.enabled ?: true,
            version = 1,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }

    // the schema the connector describes today; null when it has no discovery or the resource is gone
    private suspend fun describe(
        connection: wasichai.integrations.Connection,
        connector: wasichai.integrations.sdk.Connector,
        resource: String
    ): ResourceSchema? {
        val discovery = connector as? ResourceDiscovery ?: return null
        return try {
            discovery.describe(contexts.forConnection(connection), resource)
        } catch (_: ConnectorException.NotFound) {
            null
        } catch (e: ConnectorException) {
            throw ProviderErrors.toHttp(e)
        }
    }

    companion object {
        const val AUDIT_OBJECT = "integration_ai_tool"
        const val DEFAULT_RATE = 60

        // what MCP accepts as a tool name, lower case only
        val NAME = Regex("^[a-z][a-z0-9_]{0,63}$")

        fun view(t: Tool) =
            ToolView(
                t.id,
                t.name,
                t.description,
                t.connectionId,
                t.kind,
                t.action,
                t.resource,
                t.operation,
                t.externalIdField,
                t.inputSchema,
                t.allowedRoles,
                t.approvalRequired,
                t.rateLimitPerMinute,
                t.enabled,
                t.version,
                t.createdAt,
                t.updatedAt
            )
    }
}
