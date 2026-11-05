package wasichai.integrations.workflows

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class ExecutionStatus {
    QUEUED,
    RUNNING,

    // a step waits (a delay, an approval); with parallel branches, the latest change wins
    WAITING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    val terminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == CANCELLED
}

enum class StepStatus {
    PENDING,
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED,

    // never ran: the run ended (or was cancelled) first
    SKIPPED;

    val terminal: Boolean get() = this == SUCCEEDED || this == FAILED || this == SKIPPED
}

data class Execution(
    val id: UUID,
    val organizationId: UUID,
    val definitionId: UUID,
    val versionNumber: Int,
    val status: ExecutionStatus,
    val triggerType: TriggerType,
    val triggerKey: String?,
    val trigger: JsonNode,
    val correlationId: String,
    val startedBy: UUID?,
    val error: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?
)

data class StepExecution(
    val executionId: UUID,
    val stepId: String,
    val stepType: String,
    val status: StepStatus,
    val attempt: Int,
    val input: JsonNode?,
    val output: JsonNode?,
    val error: String?,
    val idempotencyKey: String,
    val scope: List<Frame>,
    val arrivals: List<Int>,
    val waitUntil: Instant?,
    val decidedBy: UUID?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?
)

data class ExecutionView(
    val id: UUID,
    val workflowId: UUID,
    val version: Int,
    val status: ExecutionStatus,
    val triggerType: String,
    // {type, payload, ...}: what trigger.<path> refs read
    val trigger: JsonNode,
    val correlationId: String,
    val startedBy: UUID?,
    val error: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?
)

data class StepExecutionView(
    val stepId: String,
    val type: String,
    val status: StepStatus,
    val attempt: Int,
    val input: JsonNode?,
    val output: JsonNode?,
    val error: String?,
    val waitUntil: Instant?,
    val decidedBy: UUID?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?
)

data class ExecutionDetailView(
    val execution: ExecutionView,
    // in the order they were reached
    val steps: List<StepExecutionView>
)

data class ExecutionPage(
    val content: List<ExecutionView>,
    val nextCursor: String?
)

data class RunRequest(
    // what trigger.payload.<path> reads; absent means {}
    val payload: JsonNode? = null
)

data class DecisionRequest(
    val comment: String? = null
)

data class WebhookUrlView(
    // null while wasichai.integrations.public-base-url is not set
    val url: String?,
    // the path to append to the public base url; holds the secret: shown once, only now
    val path: String
)

data class StartedView(
    val executionId: UUID
)

fun Execution.view() =
    ExecutionView(id, definitionId, versionNumber, status, triggerType.json, trigger, correlationId, startedBy, error, createdAt, startedAt, finishedAt)

fun StepExecution.view() = StepExecutionView(stepId, stepType, status, attempt, input, output, error, waitUntil, decidedBy, createdAt, startedAt, finishedAt)
