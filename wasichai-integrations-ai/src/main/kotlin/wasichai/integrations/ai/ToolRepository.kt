package wasichai.integrations.ai

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.sdk.FieldType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class ToolRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata

    private val toolColumns =
        "id, organization_id, connection_id, name, description, kind, action, resource, operation, external_id_field, input_schema::text AS input_schema, " +
            "field_types::text AS field_types, allowed_roles::text AS allowed_roles, approval_required, rate_limit_per_minute, enabled, version, " +
            "created_at, updated_at"

    private val executionColumns =
        "id, organization_id, tool_id, tool_name, tool_version, connection_id, caller_id, service_account, input::text AS input, status, " +
            "output::text AS output, output_truncated, error, error_kind, decided_by, decided_at, reason, correlation_id, created_at, started_at, finished_at"

    suspend fun list(organizationId: UUID): List<Tool> =
        db
            .sql("SELECT $toolColumns FROM $m.integration_ai_tools WHERE organization_id = :org ORDER BY name")
            .bind("org", organizationId)
            .map { row, _ -> tool(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun findByName(
        organizationId: UUID,
        name: String
    ): Tool? =
        db
            .sql("SELECT $toolColumns FROM $m.integration_ai_tools WHERE organization_id = :org AND name = :name")
            .bind("org", organizationId)
            .bind("name", name)
            .map { row, _ -> tool(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun find(
        organizationId: UUID,
        id: UUID
    ): Tool? =
        db
            .sql("SELECT $toolColumns FROM $m.integration_ai_tools WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> tool(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun insert(
        tool: Tool,
        createdBy: UUID
    ) {
        db
            .sql(
                "INSERT INTO $m.integration_ai_tools (id, organization_id, connection_id, name, description, kind, action, resource, operation, " +
                    "external_id_field, input_schema, field_types, allowed_roles, approval_required, rate_limit_per_minute, enabled, created_by, " +
                    "created_at, updated_at) VALUES (:id, :org, :connection, :name, :description, :kind, :action, :resource, :operation, :externalIdField, " +
                    "CAST(:inputSchema AS jsonb), CAST(:fieldTypes AS jsonb), CAST(:roles AS jsonb), :approval, :rate, :enabled, :createdBy, :now, :now)"
            ).bindTool(tool)
            .bind("id", tool.id)
            .bind("org", tool.organizationId)
            .bind("createdBy", createdBy)
            .bind("now", tool.createdAt.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // optimistic lock on the version the caller read
    suspend fun update(
        tool: Tool,
        expectedVersion: Int
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_ai_tools SET connection_id = :connection, name = :name, description = :description, kind = :kind, action = :action, " +
                    "resource = :resource, operation = :operation, external_id_field = :externalIdField, input_schema = CAST(:inputSchema AS jsonb), " +
                    "field_types = CAST(:fieldTypes AS jsonb), allowed_roles = CAST(:roles AS jsonb), approval_required = :approval, " +
                    "rate_limit_per_minute = :rate, enabled = :enabled, version = version + 1, updated_at = :now " +
                    "WHERE organization_id = :org AND id = :id AND version = :version"
            ).bindTool(tool)
            .bind("now", tool.updatedAt.odt())
            .bind("org", tool.organizationId)
            .bind("id", tool.id)
            .bind("version", expectedVersion)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM $m.integration_ai_tools WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    // ConnectionUsage
    suspend fun namesOnConnection(
        organizationId: UUID,
        connectionId: UUID
    ): List<String> =
        db
            .sql("SELECT name FROM $m.integration_ai_tools WHERE organization_id = :org AND connection_id = :connection ORDER BY name")
            .bind("org", organizationId)
            .bind("connection", connectionId)
            .map { row, _ -> row.get("name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()

    // the tenant's role names among these (wasichai's roles table)
    suspend fun existingRoles(
        organizationId: UUID,
        names: Collection<String>
    ): Set<String> {
        if (names.isEmpty()) return emptySet()
        return db
            .sql("SELECT name FROM $m.roles WHERE organization_id = :org AND name = ANY(:names)")
            .bind("org", organizationId)
            .bind("names", names.toTypedArray())
            .map { row, _ -> row.get("name", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()
    }

    suspend fun insertExecution(e: Execution) {
        db
            .sql(
                "INSERT INTO $m.integration_ai_tool_executions (id, organization_id, tool_id, tool_name, tool_version, connection_id, caller_id, " +
                    "service_account, input, status, correlation_id, created_at, started_at) VALUES (:id, :org, :tool, :toolName, :toolVersion, " +
                    ":connection, :caller, :serviceAccount, CAST(:input AS jsonb), :status, :correlationId, :createdAt, :startedAt)"
            ).bind("id", e.id)
            .bind("org", e.organizationId)
            .bindOrNull("tool", e.toolId)
            .bind("toolName", e.toolName)
            .bind("toolVersion", e.toolVersion)
            .bind("connection", e.connectionId)
            .bindOrNull("caller", e.callerId)
            .bindOrNull("serviceAccount", e.serviceAccount)
            .bind("input", json.writeValueAsString(e.input))
            .bind("status", e.status.name)
            .bindOrNull("correlationId", e.correlationId)
            .bind("createdAt", e.createdAt.odt())
            .bindOrNull("startedAt", e.startedAt?.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // PENDING_APPROVAL to RUNNING or REJECTED, once: false when another decision came first
    suspend fun decide(
        organizationId: UUID,
        id: UUID,
        status: ExecutionStatus,
        decidedBy: UUID,
        reason: String?,
        now: Instant
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_ai_tool_executions SET status = :status, decided_by = :by, decided_at = :now, reason = :reason, " +
                    (if (status == ExecutionStatus.RUNNING) "started_at = :now " else "finished_at = :now ") +
                    "WHERE organization_id = :org AND id = :id AND status = 'PENDING_APPROVAL'"
            ).bind("status", status.name)
            .bind("by", decidedBy)
            .bind("now", now.odt())
            .bindOrNull("reason", reason)
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    suspend fun finish(
        organizationId: UUID,
        id: UUID,
        status: ExecutionStatus,
        output: String?,
        outputTruncated: Boolean,
        error: String?,
        errorKind: String?,
        now: Instant
    ) {
        db
            .sql(
                "UPDATE $m.integration_ai_tool_executions SET status = :status, output = CAST(:output AS jsonb), output_truncated = :truncated, " +
                    "error = :error, error_kind = :errorKind, finished_at = :now WHERE organization_id = :org AND id = :id AND status = 'RUNNING'"
            ).bind("status", status.name)
            .bindOrNull("output", output)
            .bind("truncated", outputTruncated)
            .bindOrNull("error", error)
            .bindOrNull("errorKind", errorKind)
            .bind("now", now.odt())
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun findExecution(
        organizationId: UUID,
        id: UUID
    ): Execution? =
        db
            .sql("SELECT $executionColumns FROM $m.integration_ai_tool_executions WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> execution(row) }
            .one()
            .awaitFirstOrNull()

    // newest first by (created_at, id); caller narrows to one caller's executions, before is the keyset cursor
    suspend fun executions(
        organizationId: UUID,
        caller: UUID?,
        status: ExecutionStatus?,
        toolName: String?,
        before: Pair<Instant, UUID>?,
        limit: Int
    ): List<Execution> {
        val where = mutableListOf("organization_id = :org")
        if (caller != null) where += "caller_id = :caller"
        if (status != null) where += "status = :status"
        if (toolName != null) where += "tool_name = :toolName"
        if (before != null) where += "(created_at, id) < (:beforeAt, :beforeId)"
        var spec =
            db
                .sql(
                    "SELECT $executionColumns FROM $m.integration_ai_tool_executions WHERE ${where.joinToString(
                        " AND "
                    )} ORDER BY created_at DESC, id DESC LIMIT :limit"
                ).bind("org", organizationId)
                .bind("limit", limit)
        if (caller != null) spec = spec.bind("caller", caller)
        if (status != null) spec = spec.bind("status", status.name)
        if (toolName != null) spec = spec.bind("toolName", toolName)
        if (before != null) spec = spec.bind("beforeAt", before.first.odt()).bind("beforeId", before.second)
        return spec
            .map { row, _ -> execution(row) }
            .all()
            .collectList()
            .awaitSingle()
    }

    private fun DatabaseClient.GenericExecuteSpec.bindTool(tool: Tool): DatabaseClient.GenericExecuteSpec =
        bind("connection", tool.connectionId)
            .bind("name", tool.name)
            .bind("description", tool.description)
            .bind("kind", tool.kind.name)
            .bindOrNull("action", tool.action)
            .bindOrNull("resource", tool.resource)
            .bindOrNull("operation", tool.operation?.name)
            .bindOrNull("externalIdField", tool.externalIdField)
            .bind("inputSchema", json.writeValueAsString(tool.inputSchema))
            .bind("fieldTypes", json.writeValueAsString(tool.fieldTypes.mapValues { it.value.name }))
            .bind("roles", json.writeValueAsString(tool.allowedRoles))
            .bind("approval", tool.approvalRequired)
            .bind("rate", tool.rateLimitPerMinute)
            .bind("enabled", tool.enabled)

    private inline fun <reified T : Any> DatabaseClient.GenericExecuteSpec.bindOrNull(
        name: String,
        value: T?
    ): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, T::class.java) else bind(name, value)

    private fun tool(row: Readable): Tool {
        val types = json.readTree(row.get("field_types", String::class.java)!!)
        val roles = json.readTree(row.get("allowed_roles", String::class.java)!!)
        return Tool(
            id = row.get("id", UUID::class.java)!!,
            organizationId = row.get("organization_id", UUID::class.java)!!,
            connectionId = row.get("connection_id", UUID::class.java)!!,
            name = row.get("name", String::class.java)!!,
            description = row.get("description", String::class.java)!!,
            kind = ToolKind.valueOf(row.get("kind", String::class.java)!!),
            action = row.get("action", String::class.java),
            resource = row.get("resource", String::class.java),
            operation = row.get("operation", String::class.java)?.let(ToolOperation::valueOf),
            externalIdField = row.get("external_id_field", String::class.java),
            inputSchema = json.readTree(row.get("input_schema", String::class.java)!!),
            fieldTypes = types.properties().associate { (k, v) -> k to FieldType.valueOf(v.stringValue()) },
            allowedRoles = (0 until roles.size()).map { roles.get(it).stringValue() },
            approvalRequired = row.get("approval_required", Boolean::class.javaObjectType)!!,
            rateLimitPerMinute = row.get("rate_limit_per_minute", Int::class.javaObjectType)!!,
            enabled = row.get("enabled", Boolean::class.javaObjectType)!!,
            version = row.get("version", Int::class.javaObjectType)!!,
            createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
            updatedAt = row.get("updated_at", OffsetDateTime::class.java)!!.toInstant()
        )
    }

    private fun execution(row: Readable): Execution =
        Execution(
            id = row.get("id", UUID::class.java)!!,
            organizationId = row.get("organization_id", UUID::class.java)!!,
            toolId = row.get("tool_id", UUID::class.java),
            toolName = row.get("tool_name", String::class.java)!!,
            toolVersion = row.get("tool_version", Int::class.javaObjectType)!!,
            connectionId = row.get("connection_id", UUID::class.java)!!,
            callerId = row.get("caller_id", UUID::class.java),
            serviceAccount = row.get("service_account", String::class.java),
            input = json.readTree(row.get("input", String::class.java)!!),
            status = ExecutionStatus.valueOf(row.get("status", String::class.java)!!),
            output = row.get("output", String::class.java)?.let { json.readTree(it) },
            outputTruncated = row.get("output_truncated", Boolean::class.javaObjectType)!!,
            error = row.get("error", String::class.java),
            errorKind = row.get("error_kind", String::class.java),
            decidedBy = row.get("decided_by", UUID::class.java),
            decidedAt = row.get("decided_at", OffsetDateTime::class.java)?.toInstant(),
            reason = row.get("reason", String::class.java),
            correlationId = row.get("correlation_id", String::class.java),
            createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
            startedAt = row.get("started_at", OffsetDateTime::class.java)?.toInstant(),
            finishedAt = row.get("finished_at", OffsetDateTime::class.java)?.toInstant()
        )

    private fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}
