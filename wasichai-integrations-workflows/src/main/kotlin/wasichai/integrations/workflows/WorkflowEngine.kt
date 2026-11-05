package wasichai.integrations.workflows

import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.platform.ChangeOrigin
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.IntegrationsMeters
import wasichai.integrations.Job
import wasichai.integrations.JobHandler
import wasichai.integrations.JobRepository
import wasichai.integrations.JobRunner
import wasichai.integrations.PermanentJobFailure
import wasichai.integrations.Transactions
import wasichai.integrations.UsageMeter
import wasichai.integrations.sdk.ActionExecutor
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.ResourceUpserter
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.WriteResult
import wasichai.integrations.workflows.autoconfigure.WorkflowsProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

// runs workflows on the integrations job queue (ADR-0025). a run is a row pinned to its version; every step a row and a
// job of kind integrations.workflow.step keyed exec:<id>:step:<stepId>. a step's result and the moves after it are
// written in one transaction under the run's row lock, so a finished step is never run again and a crash resumes at
// the step that was not finished. a step's own work (a provider call) happens outside any transaction: at least once.
class WorkflowEngine(
    private val workflows: WorkflowRepository,
    private val executions: ExecutionRepository,
    private val registry: ConnectorRegistry,
    private val connections: ConnectionRepository,
    private val contexts: ConnectorContexts,
    private val jobs: JobRepository,
    private val tx: Transactions,
    private val meters: IntegrationsMeters,
    private val properties: WorkflowsProperties,
    private val clock: Clock,
    private val usage: UsageMeter? = null
) : JobHandler {
    private val log = LoggerFactory.getLogger(javaClass)
    private val nodes = JsonNodeFactory.instance

    override val kind = KIND

    // a new run of a published version. null when this trigger key already started one (a redelivered event, a
    // schedule firing seen twice): a run is started once per key
    suspend fun start(
        organizationId: UUID,
        definitionId: UUID,
        versionNumber: Int,
        type: TriggerType,
        trigger: JsonNode,
        triggerKey: String?,
        correlationId: String,
        startedBy: UUID?
    ): UUID? {
        val graph = graph(organizationId, definitionId, versionNumber)
        val now = clock.instant()
        val execution =
            Execution(
                UUID.randomUUID(),
                organizationId,
                definitionId,
                versionNumber,
                ExecutionStatus.QUEUED,
                type,
                triggerKey,
                trigger,
                correlationId,
                startedBy,
                null,
                now,
                null,
                null
            )
        val started =
            tx.run {
                if (!executions.insert(execution)) return@run false
                apply(execution, graph, Flow.move(graph, graph.trigger.next, emptyList()), now)
                true
            }
        if (!started) return null
        meters.registry.counter(EXECUTIONS, "trigger", type.json, "outcome", "started").increment()
        log.info("integrations: workflow {} run {} started by {} [{}]", definitionId, execution.id, type.json, correlationId)
        return execution.id
    }

    override suspend fun handle(job: Job) {
        val executionId = UUID.fromString(job.payload.path("executionId").asString())
        val stepId = job.payload.path("stepId").asString()
        ChangeOrigin.within(SOURCE, job.correlationId?.let(ChangeOrigin::acceptCorrelationId)) { step(job, executionId, stepId) }
    }

    private suspend fun step(
        job: Job,
        executionId: UUID,
        stepId: String
    ) {
        // gone with its workflow, or over (ended, failed, cancelled): what is left of it does nothing
        val execution = executions.find(job.organizationId, executionId) ?: return
        if (execution.status.terminal) return
        val row = executions.step(executionId, stepId)
        if (row == null) {
            log.warn("integrations: workflow run {} has a job for step '{}' it never reached; ignored [{}]", executionId, stepId, execution.correlationId)
            return
        }
        // finished already: a redelivered job, a crash after the commit. never run twice
        if (row.status.terminal) return
        val graph = graph(execution.organizationId, execution.definitionId, execution.versionNumber)
        val step = graph.step(stepId) ?: throw PermanentJobFailure("step '$stepId' is not in version ${execution.versionNumber}")
        val context =
            RunContext(
                execution.trigger,
                executions.steps(executionId).filter { it.status == StepStatus.SUCCEEDED }.associate {
                    it.stepId to
                        it.output
                }
            )
        if (step is Step.Approval) {
            // an approval's only job is its timeout; a decision that came first left nothing to do
            if (row.status == StepStatus.WAITING) timedOut(execution, graph, step, row)
            return
        }
        val input = (step as? Step.Action)?.let { context.input(it.input) }
        val attempt = executions.start(executionId, stepId, input, clock.instant()) ?: return
        executions.running(executionId, clock.instant())
        val started = System.nanoTime()
        val outcome =
            try {
                perform(execution, graph, step, row, context, attempt)
            } catch (e: Exception) {
                val (retryable, kind, _) = JobRunner.classify(e)
                val message = "${e.javaClass.simpleName}: ${e.message}"
                if (retryable && job.attempts < job.maxAttempts) {
                    executions.retrying(executionId, stepId, message, clock.instant())
                    timer(step, "retry", started)
                    log.info(
                        "integrations: workflow run {} step '{}' attempt {} failed ({}); retried [{}]",
                        executionId,
                        stepId,
                        attempt,
                        kind,
                        execution.correlationId
                    )
                    throw e
                }
                log.warn("integrations: workflow run {} step '{}' failed ({}) [{}]", executionId, stepId, kind, execution.correlationId)
                timer(step, "failed", started)
                usage?.record(execution.organizationId, STEPS_RUN)
                fail(execution, graph, step, row.scope, message)
                return
            }
        timer(step, "succeeded", started)
        // a step that ran to an outcome; its retries are not steps of their own
        usage?.record(execution.organizationId, STEPS_RUN)
        commit(execution, graph, stepId, StepStatus.SUCCEEDED, outcome.output, null, outcome.moves, outcome.end)
    }

    private class Outcome(
        val output: JsonNode?,
        val moves: List<Move> = emptyList(),
        // the run is over (an end step, a condition that does not hold)
        val end: Boolean = false
    )

    private suspend fun perform(
        execution: Execution,
        graph: WorkflowGraph,
        step: Step,
        row: StepExecution,
        context: RunContext,
        attempt: Int
    ): Outcome =
        when (step) {
            is Step.Action -> Outcome(act(execution, step, context, attempt), listOf(Flow.move(graph, step.next, row.scope)))
            is Step.Gate -> {
                val holds = Conditions.holds(step.condition, context)
                val output = nodes.objectNode().put("result", holds)
                if (holds) Outcome(output, listOf(Flow.move(graph, step.next, row.scope))) else Outcome(output, end = true)
            }
            is Step.Branch -> {
                val holds = Conditions.holds(step.condition, context)
                Outcome(nodes.objectNode().put("result", holds), listOf(Flow.move(graph, if (holds) step.then else step.otherwise, row.scope)))
            }
            is Step.Parallel -> Outcome(nodes.objectNode().put("branches", step.branches.size), Flow.fanOut(graph, step, row.scope))
            is Step.Delay -> Outcome(nodes.objectNode().put("waitedUntil", row.waitUntil.toString()), listOf(Flow.move(graph, step.next, row.scope)))
            is Step.End -> Outcome(null, end = true)
            is Step.Approval -> error("approvals are decided, not performed")
        }

    // a failure after the queue's retries (or one not worth retrying): onError when the step has one, else the run fails
    private suspend fun fail(
        execution: Execution,
        graph: WorkflowGraph,
        step: Step,
        scope: List<Frame>,
        message: String
    ) {
        val onError = step.onError
        if (onError != null) {
            commit(execution, graph, step.id, StepStatus.FAILED, null, message, listOf(Flow.move(graph, onError, scope)), end = false)
        } else {
            commit(execution, graph, step.id, StepStatus.FAILED, null, message, emptyList(), end = false, failRun = "step '${step.id}' failed: $message")
        }
    }

    // the step's result and what follows it, in one transaction under the run's row lock. a run that ended meanwhile
    // keeps the result (it happened) and goes nowhere
    private suspend fun commit(
        execution: Execution,
        graph: WorkflowGraph,
        stepId: String,
        status: StepStatus,
        output: JsonNode?,
        error: String?,
        moves: List<Move>,
        end: Boolean,
        failRun: String? = null,
        decidedBy: UUID? = null
    ) {
        val now = clock.instant()
        val finished =
            tx.run {
                val locked = executions.lock(execution.organizationId, execution.id) ?: return@run null
                if (!executions.complete(execution.id, stepId, status, output, error, now, decidedBy)) return@run null
                if (locked.status.terminal) return@run null
                when {
                    failRun != null -> ExecutionStatus.FAILED.takeIf { executions.finish(execution.id, it, failRun, now) }
                    end -> ExecutionStatus.SUCCEEDED.takeIf { executions.finish(execution.id, it, null, now) }
                    else -> {
                        moves.forEach { apply(locked, graph, it, now) }
                        null
                    }
                }
            }
        finished?.let { finished(execution, it) }
    }

    // inside the run's transaction: run a step, or record a branch's arrival at its join
    private suspend fun apply(
        execution: Execution,
        graph: WorkflowGraph,
        move: Move,
        now: Instant
    ) {
        when (move) {
            is Move.Run -> enter(execution, graph.step(move.stepId)!!, move.scope, now, joined = false)
            is Move.Arrive -> {
                val join = graph.step(move.join)!!
                val arrived = executions.arrive(execution.organizationId, execution.id, join.id, join.typeName, move.branch, move.scope, now) ?: return
                if (Flow.completes(arrived, move.branches)) enter(execution, join, move.scope, now, joined = true)
            }
        }
    }

    // a step the run reaches: a row, and a job (a delay's at its end; an approval's only for its timeout)
    private suspend fun enter(
        execution: Execution,
        step: Step,
        scope: List<Frame>,
        now: Instant,
        joined: Boolean
    ) {
        val waitUntil =
            when (step) {
                is Step.Delay -> now.plus(step.duration)
                is Step.Approval -> step.timeout?.let { now.plus(it) }
                else -> null
            }
        val parks = step is Step.Delay || step is Step.Approval
        val status = if (parks) StepStatus.WAITING else StepStatus.PENDING
        val inserted = executions.insertStep(execution.organizationId, execution.id, step.id, step.typeName, status, scope, waitUntil, now)
        if (!inserted) {
            if (!joined) {
                // a shared error path reached a second time (the validator allows only paths away from a join)
                log.info("integrations: workflow run {} reached step '{}' again; it runs once [{}]", execution.id, step.id, execution.correlationId)
                return
            }
            if (parks) executions.park(execution.id, step.id, waitUntil, now)
        }
        if (parks) executions.waiting(execution.id, now)
        if (step is Step.Approval && waitUntil == null) return
        val enqueued =
            jobs.enqueue(
                execution.organizationId,
                KIND,
                mapOf("executionId" to execution.id.toString(), "stepId" to step.id),
                dedupeKey(execution.id, step.id),
                waitUntil ?: now,
                execution.correlationId,
                properties.stepMaxAttempts
            )
        if (enqueued == null) log.info("integrations: workflow run {} step '{}' already has a job waiting [{}]", execution.id, step.id, execution.correlationId)
    }

    private suspend fun timedOut(
        execution: Execution,
        graph: WorkflowGraph,
        step: Step.Approval,
        row: StepExecution
    ) {
        executions.running(execution.id, clock.instant())
        val rejected = step.rejected
        if (rejected != null) {
            val output = nodes.objectNode().put("approved", false).put("timedOut", true)
            commit(execution, graph, step.id, StepStatus.SUCCEEDED, output, null, listOf(Flow.move(graph, rejected, row.scope)), end = false)
        } else {
            fail(execution, graph, step, row.scope, "the approval timed out")
        }
    }

    // a person's decision on a WAITING approval (the caller checked who may decide)
    suspend fun decide(
        organizationId: UUID,
        executionId: UUID,
        stepId: String,
        approved: Boolean,
        by: UUID,
        comment: String?
    ) {
        val execution = executions.find(organizationId, executionId) ?: throw NotFoundException("Execution $executionId not found")
        val graph = graph(organizationId, execution.definitionId, execution.versionNumber)
        val step = graph.step(stepId) as? Step.Approval ?: throw NotFoundException("Execution $executionId has no approval step '$stepId'")
        val now = clock.instant()
        val output =
            nodes
                .objectNode()
                .put("approved", approved)
                .put("decidedBy", by.toString())
                .put("decidedAt", now.toString())
                .also { o -> comment?.let { o.put("comment", it) } }
        val ended =
            tx.run {
                val locked = executions.lock(organizationId, executionId) ?: throw NotFoundException("Execution $executionId not found")
                if (locked.status.terminal) throw ConflictException("Execution $executionId is ${locked.status}")
                val row = executions.step(executionId, stepId)
                if (row == null || row.status != StepStatus.WAITING) throw ConflictException("Step '$stepId' is not waiting for a decision")
                executions.complete(executionId, stepId, StepStatus.SUCCEEDED, output, null, now, by)
                executions.running(executionId, now)
                val next = if (approved) step.next else step.rejected
                if (next == null) {
                    // rejected without a rejected edge: the run ends there
                    ExecutionStatus.SUCCEEDED.takeIf { executions.finish(executionId, it, null, now) }
                } else {
                    apply(locked, graph, Flow.move(graph, next, row.scope), now)
                    null
                }
            }
        log.info(
            "integrations: workflow run {} approval '{}' {} by {} [{}]",
            executionId,
            stepId,
            if (approved) "approved" else "rejected",
            by,
            execution.correlationId
        )
        ended?.let { finished(execution, it) }
    }

    // pending jobs of a cancelled run find it over and do nothing
    suspend fun cancel(
        organizationId: UUID,
        executionId: UUID,
        by: UUID
    ) {
        val execution =
            tx.run {
                val locked = executions.lock(organizationId, executionId) ?: throw NotFoundException("Execution $executionId not found")
                if (locked.status.terminal) throw ConflictException("Execution $executionId is already ${locked.status}")
                executions.finish(executionId, ExecutionStatus.CANCELLED, "cancelled by $by", clock.instant())
                locked
            }
        finished(execution, ExecutionStatus.CANCELLED)
    }

    private fun finished(
        execution: Execution,
        status: ExecutionStatus
    ) {
        meters.registry.counter(EXECUTIONS, "trigger", execution.triggerType.json, "outcome", status.name.lowercase()).increment()
        log.info("integrations: workflow {} run {} {} [{}]", execution.definitionId, execution.id, status.name.lowercase(), execution.correlationId)
    }

    private fun timer(
        step: Step,
        outcome: String,
        started: Long
    ) = meters.registry.timer(STEPS, "type", step.typeName, "outcome", outcome).record(Duration.ofNanos(System.nanoTime() - started))

    // a connector action, or a record write, through the SDK. the result is the step's output
    private suspend fun act(
        execution: Execution,
        step: Step.Action,
        context: RunContext,
        attempt: Int
    ): JsonNode {
        val connection =
            connections.find(execution.organizationId, step.connectionId) ?: throw PermanentJobFailure("connection ${step.connectionId} is gone")
        val connector = registry.find(connection.connectorId) ?: throw PermanentJobFailure("connector '${connection.connectorId}' is not installed")
        val on = contexts.forConnection(connection)
        step.action?.let { action ->
            val executor = connector as? ActionExecutor ?: throw PermanentJobFailure("connector '${connection.connectorId}' runs no actions")
            return executor.execute(on, action, context.input(step.input))
        }
        val write = step.write!!
        val fields = step.input.mapValues { fieldValue(context.value(it.value)) }

        fun writer() = connector as? ResourceWriter ?: throw PermanentJobFailure("connector '${connection.connectorId}' cannot write records")
        return when (write.operation) {
            // no idempotency key in the SDK: a create retried after a lost answer may create twice (ADR-0025)
            WriteOperation.CREATE -> written(writer().create(on, write.resource, fields))
            WriteOperation.UPDATE -> written(writer().update(on, write.resource, id(context, write.recordId, "recordId"), fields))
            // keyed by the external id: a retry finds the record it made
            WriteOperation.UPSERT -> {
                val upserter = connector as? ResourceUpserter ?: throw PermanentJobFailure("connector '${connection.connectorId}' cannot upsert records")
                written(upserter.upsert(on, write.resource, write.externalIdField!!, id(context, write.externalId, "externalId"), fields))
            }
            WriteOperation.DELETE -> {
                val id = id(context, write.recordId, "recordId")
                try {
                    writer().delete(on, write.resource, id)
                    nodes.objectNode().put("id", id).put("deleted", true)
                } catch (e: ConnectorException.NotFound) {
                    // an earlier attempt of this step may have deleted it before its answer was lost
                    if (attempt <= 1) throw e
                    nodes
                        .objectNode()
                        .put("id", id)
                        .put("deleted", true)
                        .put("alreadyGone", true)
                }
            }
        }
    }

    private fun id(
        context: RunContext,
        operand: Operand?,
        name: String
    ): String {
        val value = operand?.let(context::value)
        if (value == null || value.isNull || value.isContainer) throw PermanentJobFailure("$name resolved to no value")
        return value.asString()
    }

    private fun written(result: WriteResult): ObjectNode =
        nodes
            .objectNode()
            .put("id", result.id)
            .put("version", result.version)
            .put("created", result.created)
            .put("modifiedAt", result.modifiedAt?.toString())

    private suspend fun graph(
        organizationId: UUID,
        definitionId: UUID,
        number: Int
    ): WorkflowGraph {
        val version = workflows.version(organizationId, definitionId, number) ?: throw PermanentJobFailure("version $number of workflow $definitionId is gone")
        val violations = mutableListOf<wasichai.core.common.FieldViolation>()
        return WorkflowGraph.parse(version.graph, violations)
            ?: throw PermanentJobFailure("version $number of workflow $definitionId does not parse: $violations")
    }

    companion object {
        const val KIND = "integrations.workflow.step"
        const val SOURCE = "integrations.workflow"
        const val EXECUTIONS = "integrations.workflows.executions"
        const val STEPS = "integrations.workflows.steps"

        // usage metric: steps that ran to success or failure
        const val STEPS_RUN = "workflow.steps"

        fun dedupeKey(
            executionId: UUID,
            stepId: String
        ) = "exec:$executionId:step:$stepId"

        // a value from a run as a typed field value, by its json type: the connector converts further when it must
        fun fieldValue(node: JsonNode?): FieldValue =
            when {
                node == null || node.isNull || node.isMissingNode -> FieldValue.Null
                node.isBoolean -> FieldValue.Bool(node.booleanValue())
                node.isNumber -> FieldValue.Number(node.decimalValue())
                node.isString -> FieldValue.Text(node.stringValue())
                node.isArray &&
                    (0 until node.size()).all {
                        node
                            .get(
                                it
                            ).isString
                    }
                -> FieldValue.MultiChoice((0 until node.size()).map { node.get(it).stringValue() })
                else -> FieldValue.Json(node)
            }
    }
}

val Step.typeName: String
    get() =
        when (this) {
            is Step.Action -> "action"
            is Step.Gate -> "condition"
            is Step.Branch -> "branch"
            is Step.Parallel -> "parallel"
            is Step.Delay -> "delay"
            is Step.Approval -> "approval"
            is Step.End -> "end"
        }
