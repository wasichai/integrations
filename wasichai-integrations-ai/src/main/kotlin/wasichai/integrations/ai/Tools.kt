package wasichai.integrations.ai

import tools.jackson.databind.JsonNode
import wasichai.integrations.sdk.FieldType
import java.time.Instant
import java.util.UUID

// ACTION: a connector action through ActionExecutor. RESOURCE: one record operation on one resource
enum class ToolKind {
    ACTION,
    RESOURCE
}

enum class ToolOperation {
    READ,
    LIST,
    CREATE,
    UPDATE,
    UPSERT,
    DELETE;

    // reads change nothing at the provider: no approval by default
    val writes: Boolean get() = this != READ && this != LIST
}

enum class ExecutionStatus {
    PENDING_APPROVAL,
    RUNNING,
    SUCCEEDED,
    FAILED,
    REJECTED
}

data class Tool(
    val id: UUID,
    val organizationId: UUID,
    val connectionId: UUID,
    val name: String,
    val description: String,
    val kind: ToolKind,
    val action: String?,
    val resource: String?,
    val operation: ToolOperation?,
    val externalIdField: String?,
    val inputSchema: JsonNode,
    // what each record field converts to (RESOURCE writes); empty otherwise
    val fieldTypes: Map<String, FieldType>,
    val allowedRoles: List<String>,
    val approvalRequired: Boolean,
    val rateLimitPerMinute: Int,
    val enabled: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

// the body of POST and PUT /tools: everything, every time
data class ToolRequest(
    val name: String? = null,
    val description: String? = null,
    val connectionId: UUID? = null,
    // exactly one of action, or resource with operation
    val action: String? = null,
    val resource: String? = null,
    val operation: String? = null,
    // upsert only: the resource field that identifies a record
    val externalIdField: String? = null,
    // actions only (the manifest declares no action input); a resource tool's schema is derived
    val inputSchema: JsonNode? = null,
    val allowedRoles: List<String>? = null,
    // default: true for writes, deletes and actions, false for reads
    val approvalRequired: Boolean? = null,
    val rateLimitPerMinute: Int? = null,
    val enabled: Boolean? = null
)

data class ToolView(
    val id: UUID,
    val name: String,
    val description: String,
    val connectionId: UUID,
    val kind: ToolKind,
    val action: String?,
    val resource: String?,
    val operation: ToolOperation?,
    val externalIdField: String?,
    val inputSchema: JsonNode,
    val allowedRoles: List<String>,
    val approvalRequired: Boolean,
    val rateLimitPerMinute: Int,
    val enabled: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class Execution(
    val id: UUID,
    val organizationId: UUID,
    val toolId: UUID?,
    val toolName: String,
    val toolVersion: Int,
    val connectionId: UUID,
    val callerId: UUID?,
    val serviceAccount: String?,
    val input: JsonNode,
    val status: ExecutionStatus,
    val output: JsonNode?,
    val outputTruncated: Boolean,
    val error: String?,
    val errorKind: String?,
    val decidedBy: UUID?,
    val decidedAt: Instant?,
    val reason: String?,
    val correlationId: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?
)

data class ExecutionView(
    val id: UUID,
    val toolId: UUID?,
    val toolName: String,
    val connectionId: UUID,
    val callerId: UUID?,
    val serviceAccount: String?,
    val status: ExecutionStatus,
    val input: JsonNode,
    // the stored summary; the answer of execute and approve carries the whole output instead
    val output: JsonNode?,
    val outputTruncated: Boolean,
    val error: String?,
    val errorKind: String?,
    val decidedBy: UUID?,
    val decidedAt: Instant?,
    val reason: String?,
    val correlationId: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?
)

data class ExecutionPage(
    val content: List<ExecutionView>,
    val nextCursor: String?
)

data class RejectRequest(
    val reason: String? = null
)
