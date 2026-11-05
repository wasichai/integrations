package wasichai.integrations.workflows

import tools.jackson.databind.JsonNode
import wasichai.core.common.FieldViolation
import java.time.Instant
import java.util.UUID

// DRAFT: never published. PUBLISHED: has an immutable version; the draft may have moved on since
enum class WorkflowStatus {
    DRAFT,
    PUBLISHED
}

data class WorkflowDefinition(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val status: WorkflowStatus,
    // the draft, as the editor sent it
    val graph: JsonNode,
    val publishedVersion: Int?,
    // the draft equals the published version's graph
    val draftPublished: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class WorkflowRequest(
    val name: String? = null,
    // the draft graph; may be incomplete until it is validated and published
    val graph: JsonNode? = null
)

data class WorkflowView(
    val id: UUID,
    val name: String,
    val status: WorkflowStatus,
    val graph: JsonNode,
    val publishedVersion: Int?,
    // the draft differs from the published version (always true for a never-published workflow)
    val unpublishedChanges: Boolean,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class WorkflowVersionSummary(
    val number: Int,
    val publishedAt: Instant,
    val publishedBy: UUID?
)

data class WorkflowVersionView(
    val workflowId: UUID,
    val number: Int,
    val graph: JsonNode,
    val publishedAt: Instant,
    val publishedBy: UUID?
)

data class ValidationView(
    val valid: Boolean,
    val errors: List<FieldViolation>
)
