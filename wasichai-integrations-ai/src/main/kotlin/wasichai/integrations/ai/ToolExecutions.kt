package wasichai.integrations.ai

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import wasichai.core.audit.AuditOperation
import wasichai.core.audit.AuditService
import wasichai.core.common.Actions
import wasichai.core.common.ConflictException
import wasichai.core.common.ForbiddenException
import wasichai.core.common.NotFoundException
import wasichai.core.common.RetryLaterException
import wasichai.core.common.ValidationException
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser
import wasichai.core.platform.ChangeOrigin
import wasichai.integrations.Connection
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectionStatus
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.IntegrationsMeters
import wasichai.integrations.ProviderErrors
import wasichai.integrations.Transactions
import wasichai.integrations.WebhookService
import wasichai.integrations.sdk.ActionExecutor
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.ProviderRecord
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceUpserter
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.WriteResult
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// what execute and approve answer: the execution, and the whole output when it ran
data class ExecutionResult(
    val execution: ExecutionView,
    val pending: Boolean
)

// the only way a tool runs (ADR-0026): the REST route here and the MCP endpoint (#44) both call execute(). the policy,
// in order: the tool exists in the caller's tenant (404), a role of the caller's may call it (403), it is enabled and
// its connection active (409), the rate limit (429), the input fits its schema (400). then an execution row, and
// either the run through the SDK or a wait for a manager's approval
class ToolExecutions(
    private val access: IntegrationsAccess,
    private val currentUser: CurrentUser,
    private val repository: ToolRepository,
    private val registry: ConnectorRegistry,
    private val connections: ConnectionRepository,
    private val contexts: ConnectorContexts,
    private val limits: ToolRateLimits,
    private val audit: AuditService,
    private val transactions: Transactions,
    private val meters: IntegrationsMeters,
    private val json: JsonMapper,
    private val clock: Clock
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun execute(
        name: String,
        input: JsonNode?
    ): ExecutionResult {
        val user = access.reader()
        val tool = repository.findByName(user.organizationId, name) ?: refuse("not_found", NotFoundException("Tool '$name' not found"))
        if (!mayCall(user, tool)) refuse("forbidden", ForbiddenException("Not allowed to call tool '$name'"))
        if (!tool.enabled) refuse("disabled", ConflictException("Tool '$name' is disabled"))
        val connection = activeConnection(tool)
        limits.tryAcquire(tool.id.toString(), tool.rateLimitPerMinute)?.let {
            refuse(
                "rate_limited",
                RetryLaterException("Tool '$name' is over its limit of ${tool.rateLimitPerMinute} calls a minute", it, status = HttpStatus.TOO_MANY_REQUESTS)
            )
        }
        val given = input?.takeUnless { it.isNull || it.isMissingNode } ?: json.createObjectNode()
        if (json.writeValueAsBytes(given).size >
            MAX_INPUT_BYTES
        ) {
            refuse("invalid", ValidationException("Invalid input", "input", "at most ${MAX_INPUT_BYTES / 1024} KiB"))
        }
        val violations = ToolSchema.validate(tool.inputSchema, given)
        if (violations.isNotEmpty()) refuse("invalid", ValidationException("Invalid input for tool '$name'", violations))

        val now = clock.instant()
        val pending = tool.approvalRequired
        val execution =
            Execution(
                id = UUID.randomUUID(),
                organizationId = user.organizationId,
                toolId = tool.id,
                toolName = tool.name,
                toolVersion = tool.version,
                connectionId = tool.connectionId,
                callerId = user.userId,
                serviceAccount = user.serviceAccount,
                input = given,
                status = if (pending) ExecutionStatus.PENDING_APPROVAL else ExecutionStatus.RUNNING,
                output = null,
                outputTruncated = false,
                error = null,
                errorKind = null,
                decidedBy = null,
                decidedAt = null,
                reason = null,
                correlationId = ChangeOrigin.correlationId(),
                createdAt = now,
                startedAt = if (pending) null else now,
                finishedAt = null
            )
        transactions.run {
            repository.insertExecution(execution)
            audit.record(user.organizationId, user.userId, AUDIT_OBJECT, execution.id, AuditOperation.CREATE, after = auditState(execution))
        }
        if (pending) {
            count(ExecutionStatus.PENDING_APPROVAL)
            log.info("tool execution {} of '{}' waits for approval", execution.id, tool.name)
            return ExecutionResult(view(execution), true)
        }
        return ExecutionResult(run(tool, connection, execution, user.userId), false)
    }

    // runs it once: the claim from PENDING_APPROVAL to RUNNING is a conditional update, a second approve is 409
    suspend fun approve(id: UUID): ExecutionResult {
        val user = access.manager()
        val execution = pendingExecution(user, id)
        val tool =
            execution.toolId?.let { repository.find(user.organizationId, it) }
                ?: throw ConflictException("Tool '${execution.toolName}' is no longer exposed; reject the execution")
        if (tool.version != execution.toolVersion) throw ConflictException("Tool '${tool.name}' changed since it was called; reject the execution")
        if (!tool.enabled) throw ConflictException("Tool '${tool.name}' is disabled")
        val connection = activeConnection(tool)
        val claimed =
            transactions.run {
                repository.decide(user.organizationId, id, ExecutionStatus.RUNNING, user.userId, null, clock.instant()).also {
                    if (it) {
                        audit.record(
                            user.organizationId,
                            user.userId,
                            AUDIT_OBJECT,
                            id,
                            AuditOperation.UPDATE,
                            after =
                                mapOf(
                                    "status" to "RUNNING",
                                    "approved" to true
                                )
                        )
                    }
                }
            }
        if (!claimed) throw ConflictException("Execution $id was decided meanwhile")
        return ExecutionResult(run(tool, connection, repository.findExecution(user.organizationId, id)!!, user.userId), false)
    }

    suspend fun reject(
        id: UUID,
        reason: String?
    ): ExecutionView {
        val user = access.manager()
        pendingExecution(user, id)
        val text = reason?.trim()?.takeIf { it.isNotEmpty() }
        if (text != null && text.length > 1000) throw ValidationException("Invalid reason", "reason", "at most 1000 characters")
        val rejected =
            transactions.run {
                repository.decide(user.organizationId, id, ExecutionStatus.REJECTED, user.userId, text, clock.instant()).also {
                    if (it) audit.record(user.organizationId, user.userId, AUDIT_OBJECT, id, AuditOperation.UPDATE, after = mapOf("status" to "REJECTED"))
                }
            }
        if (!rejected) throw ConflictException("Execution $id was decided meanwhile")
        count(ExecutionStatus.REJECTED)
        return view(repository.findExecution(user.organizationId, id)!!)
    }

    // a manager reads every execution of the tenant; anyone else their own
    suspend fun list(
        status: String?,
        tool: String?,
        cursor: String?,
        limit: Int?
    ): ExecutionPage {
        val user = access.reader()
        val parsed =
            status?.takeIf { it.isNotBlank() }?.let { s ->
                ExecutionStatus.entries.firstOrNull { it.name == s.uppercase() }
                    ?: throw ValidationException("Invalid filter", "status", "one of ${ExecutionStatus.entries.joinToString()}")
            }
        val size = limit ?: 50
        if (size !in 1..200) throw ValidationException("Invalid limit", "limit", "1 to 200")
        val before = cursor?.let(WebhookService::decodeCursor)
        val caller = if (currentUser.hasPermission(user, Actions.MANAGE_ORGANIZATION)) null else user.userId
        val page = repository.executions(user.organizationId, caller, parsed, tool?.takeIf { it.isNotBlank() }, before, size + 1)
        val content = page.take(size)
        val next = if (page.size > size) content.last().let { WebhookService.encodeCursor(it.createdAt, it.id) } else null
        return ExecutionPage(content.map(::view), next)
    }

    suspend fun get(id: UUID): ExecutionView = view(visible(access.reader(), id))

    private suspend fun visible(
        user: AuthenticatedUser,
        id: UUID
    ): Execution {
        val e = repository.findExecution(user.organizationId, id)
        if (e == null || (e.callerId != user.userId && !currentUser.hasPermission(user, Actions.MANAGE_ORGANIZATION))) {
            throw NotFoundException("Execution $id not found")
        }
        return e
    }

    private suspend fun pendingExecution(
        user: AuthenticatedUser,
        id: UUID
    ): Execution {
        val e = repository.findExecution(user.organizationId, id) ?: throw NotFoundException("Execution $id not found")
        if (e.status != ExecutionStatus.PENDING_APPROVAL) throw ConflictException("Execution $id is ${e.status}, not waiting for approval")
        return e
    }

    // a role the tool allows, or MANAGE_ORGANIZATION (never a service account's, wasichai ADR-043)
    private suspend fun mayCall(
        user: AuthenticatedUser,
        tool: Tool
    ): Boolean = tool.allowedRoles.any { it in user.roles } || currentUser.hasPermission(user, Actions.MANAGE_ORGANIZATION)

    private suspend fun activeConnection(tool: Tool): Connection {
        val connection =
            connections.find(tool.organizationId, tool.connectionId)
                ?: refuse("connection", ConflictException("The connection of tool '${tool.name}' is gone"))
        if (connection.status != ConnectionStatus.ACTIVE) {
            refuse("connection", ConflictException("The connection of tool '${tool.name}' is ${connection.status}"))
        }
        return connection
    }

    // a refusal before any execution exists: an http error and a counted reason, no row
    private fun refuse(
        reason: String,
        e: Exception
    ): Nothing {
        meters.registry.counter("integrations.ai.tool.refusals", "reason", reason).increment()
        throw e
    }

    // the run, then its outcome stored even when the caller went away meanwhile
    private suspend fun run(
        tool: Tool,
        connection: Connection,
        execution: Execution,
        actor: UUID
    ): ExecutionView {
        var output: JsonNode? = null
        var error: String? = null
        var errorKind: String? = null
        try {
            output = dispatch(tool, connection, execution.input)
        } catch (e: ConnectorException) {
            error = ProviderErrors.toHttp(e).message
            errorKind = kind(e)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { finish(tool, execution, actor, null, "cancelled before the provider answered", "cancelled") }
            throw e
        } catch (e: Unsupported) {
            error = e.message
            errorKind = "unsupported"
        } catch (e: Exception) {
            // the class only: a message may quote input
            log.warn("tool execution {} of '{}' failed unexpectedly: {}", execution.id, tool.name, e.javaClass.name)
            error = "unexpected error"
            errorKind = "unexpected"
        }
        val stored = withContext(NonCancellable) { finish(tool, execution, actor, output, error, errorKind) }
        return stored.copy(output = output ?: stored.output)
    }

    private suspend fun finish(
        tool: Tool,
        execution: Execution,
        actor: UUID,
        output: JsonNode?,
        error: String?,
        errorKind: String?
    ): ExecutionView {
        val status = if (error == null) ExecutionStatus.SUCCEEDED else ExecutionStatus.FAILED
        val (summary, truncated) = summary(output)
        transactions.run {
            repository.finish(execution.organizationId, execution.id, status, summary, truncated, error?.take(1000), errorKind, clock.instant())
            audit.record(
                execution.organizationId,
                actor,
                AUDIT_OBJECT,
                execution.id,
                AuditOperation.UPDATE,
                after = mapOf("status" to status.name, "errorKind" to errorKind)
            )
        }
        count(status)
        log.info("tool execution {} of '{}' {}", execution.id, tool.name, status)
        return view(repository.findExecution(execution.organizationId, execution.id)!!)
    }

    // the stored summary: credential-like keys masked at any depth, at most MAX_OUTPUT_BYTES
    private fun summary(output: JsonNode?): Pair<String?, Boolean> {
        if (output == null) return null to false
        val text = json.writeValueAsString(mask(output.deepCopy()))
        if (text.toByteArray().size <= MAX_OUTPUT_BYTES) return text to false
        return json.writeValueAsString(json.nodeFactory.stringNode(text.take(MAX_OUTPUT_BYTES / 4))) to true
    }

    private fun mask(node: JsonNode): JsonNode {
        when {
            node.isObject -> {
                val o = node as ObjectNode
                o.propertyNames().toList().forEach { k ->
                    if (ToolSchema.SECRET_NAME.containsMatchIn(k)) o.put(k, MASK) else mask(o.get(k))
                }
            }
            node.isArray -> (0 until node.size()).forEach { mask(node.get(it)) }
        }
        return node
    }

    private class Unsupported(
        message: String
    ) : RuntimeException(message)

    // through the SDK only: the connector, its context for this connection, one call
    private suspend fun dispatch(
        tool: Tool,
        connection: Connection,
        input: JsonNode
    ): JsonNode {
        val connector = registry.find(connection.connectorId) ?: throw Unsupported("connector '${connection.connectorId}' is not installed")
        val context = contexts.forConnection(connection)
        if (tool.kind == ToolKind.ACTION) {
            val actions = connector as? ActionExecutor ?: throw Unsupported("connector '${connection.connectorId}' runs no actions")
            return actions.execute(context, tool.action!!, input)
        }
        val resource = tool.resource!!

        fun unsupported(): Nothing = throw Unsupported("connector '${connection.connectorId}' cannot ${tool.operation!!.name.lowercase()} records")
        return when (tool.operation!!) {
            ToolOperation.READ -> {
                val reader = connector as? ResourceReader ?: unsupported()
                json.createObjectNode().set("record", reader.read(context, resource, input.get("id").stringValue())?.let(::record))
            }
            ToolOperation.LIST -> {
                val reader = connector as? ResourceReader ?: unsupported()
                val page =
                    reader.list(
                        context,
                        ReadRequest(resource, input.get("cursor")?.stringValue(), input.get("pageSize")?.intValue() ?: DEFAULT_PAGE)
                    )
                json.createObjectNode().also { o ->
                    o.putArray("records").apply { page.records.forEach { add(record(it)) } }
                    o.put("nextCursor", page.nextCursor)
                }
            }
            ToolOperation.CREATE -> written((connector as? ResourceWriter ?: unsupported()).create(context, resource, fields(tool, input)))
            ToolOperation.UPDATE ->
                written((connector as? ResourceWriter ?: unsupported()).update(context, resource, input.get("id").stringValue(), fields(tool, input)))
            ToolOperation.UPSERT ->
                written(
                    (connector as? ResourceUpserter ?: unsupported())
                        .upsert(context, resource, tool.externalIdField!!, input.get("externalId").stringValue(), fields(tool, input))
                )
            ToolOperation.DELETE -> {
                val id = input.get("id").stringValue()
                (connector as? ResourceWriter ?: unsupported()).delete(context, resource, id)
                json.createObjectNode().put("id", id).put("deleted", true)
            }
        }
    }

    // the validated input's fields, typed by what the resource described at exposure
    private fun fields(
        tool: Tool,
        input: JsonNode
    ): Map<String, FieldValue> =
        input.get("fields")?.properties()?.associate { (name, value) ->
            name to
                when (tool.fieldTypes.getValue(name)) {
                    FieldType.TEXT, FieldType.REFERENCE -> FieldValue.Text(value.stringValue())
                    FieldType.NUMBER -> FieldValue.Number(value.decimalValue())
                    FieldType.BOOLEAN -> FieldValue.Bool(value.booleanValue())
                    FieldType.DATE -> FieldValue.Date(LocalDate.parse(value.stringValue()))
                    FieldType.DATETIME -> FieldValue.DateTime(OffsetDateTime.parse(value.stringValue()).toInstant())
                    FieldType.CHOICE -> FieldValue.Choice(value.stringValue())
                    FieldType.MULTI_CHOICE -> FieldValue.MultiChoice((0 until value.size()).map { value.get(it).stringValue() })
                    FieldType.JSON -> FieldValue.Json(value)
                }
        } ?: emptyMap()

    private fun record(r: ProviderRecord): JsonNode =
        json.createObjectNode().also { o ->
            o.put("id", r.id)
            o.put("version", r.version)
            o.put("modifiedAt", r.modifiedAt?.toString())
            val f = o.putObject("fields")
            r.fields.forEach { (k, v) -> f.set(k, value(v)) }
        }

    private fun value(v: FieldValue): JsonNode {
        val n = json.nodeFactory
        return when (v) {
            FieldValue.Null -> n.nullNode()
            is FieldValue.Text -> n.stringNode(v.value)
            is FieldValue.Number -> n.numberNode(v.value)
            is FieldValue.Bool -> n.booleanNode(v.value)
            is FieldValue.Date -> n.stringNode(v.value.toString())
            is FieldValue.DateTime -> n.stringNode(v.value.toString())
            is FieldValue.Choice -> n.stringNode(v.value)
            is FieldValue.MultiChoice -> n.arrayNode().apply { v.values.forEach(::add) }
            is FieldValue.Reference -> n.objectNode().put("resource", v.resource).put("id", v.id)
            is FieldValue.Json -> v.value
        }
    }

    private fun written(w: WriteResult): JsonNode =
        json
            .createObjectNode()
            .put("id", w.id)
            .put("version", w.version)
            .put("modifiedAt", w.modifiedAt?.toString())
            .put("created", w.created)

    private fun count(status: ExecutionStatus) {
        meters.registry.counter("integrations.ai.tool.executions", "status", status.name.lowercase()).increment()
    }

    private fun auditState(e: Execution) =
        mapOf("tool" to e.toolName, "toolVersion" to e.toolVersion, "connectionId" to e.connectionId, "status" to e.status.name)

    companion object {
        const val AUDIT_OBJECT = "integration_ai_tool_execution"
        const val MAX_INPUT_BYTES = 64 * 1024
        const val MAX_OUTPUT_BYTES = 16 * 1024
        const val DEFAULT_PAGE = 20
        const val MASK = "***"

        fun kind(e: ConnectorException): String =
            when (e) {
                is ConnectorException.RateLimited -> "rate_limited"
                is ConnectorException.AuthExpired -> "auth_expired"
                is ConnectorException.NotFound -> "not_found"
                is ConnectorException.PermissionDenied -> "permission_denied"
                is ConnectorException.Transient -> "transient"
                is ConnectorException.Conflict -> "conflict"
                is ConnectorException.Permanent -> "permanent"
            }

        fun view(e: Execution) =
            ExecutionView(
                e.id,
                e.toolId,
                e.toolName,
                e.connectionId,
                e.callerId,
                e.serviceAccount,
                e.status,
                e.input,
                e.output,
                e.outputTruncated,
                e.error,
                e.errorKind,
                e.decidedBy,
                e.decidedAt,
                e.reason,
                e.correlationId,
                e.createdAt,
                e.startedAt,
                e.finishedAt
            )
    }
}
