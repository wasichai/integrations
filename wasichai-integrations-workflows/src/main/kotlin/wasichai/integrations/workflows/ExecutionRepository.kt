package wasichai.integrations.workflows

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

// the published trigger of a workflow that is not paused, as the trigger sources read it
data class LiveTrigger(
    val definitionId: UUID,
    val organizationId: UUID,
    val versionNumber: Int,
    val type: TriggerType,
    val config: JsonNode,
    val lastFiredAt: Instant?,
    // when this trigger was published: a schedule counts from here until it first fires
    val updatedAt: Instant,
    val paused: Boolean,
    val webhookTokenHash: String?
) {
    val cron: String? get() = config.get("cron")?.takeIf { it.isString }?.stringValue()
    val zone: ZoneId get() = config.get("zone")?.takeIf { it.isString }?.let { ZoneId.of(it.stringValue()) } ?: ZoneId.of("UTC")
}

class ExecutionRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata
    private val executionColumns =
        "id, organization_id, definition_id, version_number, status, trigger_type, trigger_key, trigger_payload::text AS trigger_payload, correlation_id, " +
            "started_by, error, created_at, started_at, finished_at"
    private val stepColumns =
        "execution_id, step_id, step_type, status, attempt, input::text AS input, output::text AS output, error, idempotency_key, scope::text AS scope, " +
            "arrivals, wait_until, decided_by, created_at, started_at, finished_at"
    private val triggerColumns =
        "t.definition_id, t.organization_id, t.version_number, t.type, t.config::text AS config, t.last_fired_at, t.updated_at, d.paused, d.webhook_token_hash"

    // false when the same trigger key already started a run of this workflow
    suspend fun insert(execution: Execution): Boolean =
        db
            .sql(
                "INSERT INTO $m.integration_workflow_executions (id, organization_id, definition_id, version_number, status, trigger_type, trigger_key, " +
                    "trigger_payload, correlation_id, started_by, created_at, updated_at) VALUES (:id, :org, :definition, :version, :status, :type, :key, " +
                    "CAST(:trigger AS jsonb), :correlation, :by, :now, :now) " +
                    "ON CONFLICT (definition_id, trigger_key) WHERE trigger_key IS NOT NULL DO NOTHING RETURNING id"
            ).bind("id", execution.id)
            .bind("org", execution.organizationId)
            .bind("definition", execution.definitionId)
            .bind("version", execution.versionNumber)
            .bind("status", execution.status.name)
            .bind("type", execution.triggerType.name)
            .nullable("key", execution.triggerKey, String::class.java)
            .bind("trigger", json.writeValueAsString(execution.trigger))
            .bind("correlation", execution.correlationId)
            .nullable("by", execution.startedBy, UUID::class.java)
            .bind("now", execution.createdAt.odt())
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitFirstOrNull() != null

    suspend fun findByTriggerKey(
        definitionId: UUID,
        key: String
    ): UUID? =
        db
            .sql("SELECT id FROM $m.integration_workflow_executions WHERE definition_id = :definition AND trigger_key = :key")
            .bind("definition", definitionId)
            .bind("key", key)
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitFirstOrNull()

    suspend fun find(
        organizationId: UUID,
        id: UUID
    ): Execution? =
        db
            .sql("SELECT $executionColumns FROM $m.integration_workflow_executions WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> execution(row) }
            .one()
            .awaitFirstOrNull()

    // inside a transaction: every change to a run's progress takes this row first, so they take turns
    suspend fun lock(
        organizationId: UUID,
        id: UUID
    ): Execution? =
        db
            .sql("SELECT $executionColumns FROM $m.integration_workflow_executions WHERE organization_id = :org AND id = :id FOR UPDATE")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> execution(row) }
            .one()
            .awaitFirstOrNull()

    // newest first, keyset by (created_at, id)
    suspend fun page(
        organizationId: UUID,
        definitionId: UUID,
        status: ExecutionStatus?,
        before: Pair<Instant, UUID>?,
        limit: Int
    ): List<Execution> =
        db
            .sql(
                "SELECT $executionColumns FROM $m.integration_workflow_executions WHERE organization_id = :org AND definition_id = :definition " +
                    "AND (CAST(:status AS text) IS NULL OR status = :status) " +
                    (if (before != null) "AND (created_at, id) < (:beforeAt, :beforeId) " else "") +
                    "ORDER BY created_at DESC, id DESC LIMIT :limit"
            ).bind("org", organizationId)
            .bind("definition", definitionId)
            .nullable("status", status?.name, String::class.java)
            .let { s -> before?.let { s.bind("beforeAt", it.first.odt()).bind("beforeId", it.second) } ?: s }
            .bind("limit", limit)
            .map { row, _ -> execution(row) }
            .all()
            .collectList()
            .awaitSingle()

    // QUEUED or WAITING -> RUNNING: a step started work
    suspend fun running(
        id: UUID,
        now: Instant
    ) = status(id, ExecutionStatus.RUNNING, setOf(ExecutionStatus.QUEUED, ExecutionStatus.WAITING), now)

    // a step parked (a delay, an approval)
    suspend fun waiting(
        id: UUID,
        now: Instant
    ) = status(id, ExecutionStatus.WAITING, setOf(ExecutionStatus.QUEUED, ExecutionStatus.RUNNING), now)

    private suspend fun status(
        id: UUID,
        to: ExecutionStatus,
        from: Set<ExecutionStatus>,
        now: Instant
    ) {
        db
            .sql(
                "UPDATE $m.integration_workflow_executions SET status = :to, started_at = COALESCE(started_at, :now), updated_at = :now " +
                    "WHERE id = :id AND status IN (${from.joinToString { "'${it.name}'" }})"
            ).bind("to", to.name)
            .bind("now", now.odt())
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // the run is over: steps that never ran are SKIPPED, with why. false when it was already over
    suspend fun finish(
        id: UUID,
        status: ExecutionStatus,
        error: String?,
        now: Instant
    ): Boolean {
        val finished =
            db
                .sql(
                    "UPDATE $m.integration_workflow_executions SET status = :status, error = :error, started_at = COALESCE(started_at, :now), " +
                        "finished_at = :now, updated_at = :now WHERE id = :id AND status IN ('QUEUED', 'RUNNING', 'WAITING')"
                ).bind("status", status.name)
                .nullable("error", error?.take(2000), String::class.java)
                .bind("now", now.odt())
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle() == 1L
        if (finished) {
            db
                .sql(
                    "UPDATE $m.integration_workflow_step_executions SET status = 'SKIPPED', error = :why, finished_at = :now, updated_at = :now " +
                        "WHERE execution_id = :id AND status IN ('PENDING', 'WAITING')"
                ).bind("why", "the run ended ${status.name.lowercase()} before this step ran")
                .bind("now", now.odt())
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
        return finished
    }

    // in the order the run reached them
    suspend fun steps(executionId: UUID): List<StepExecution> =
        db
            .sql("SELECT $stepColumns FROM $m.integration_workflow_step_executions WHERE execution_id = :id ORDER BY created_at, step_id")
            .bind("id", executionId)
            .map { row, _ -> step(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun step(
        executionId: UUID,
        stepId: String
    ): StepExecution? =
        db
            .sql("SELECT $stepColumns FROM $m.integration_workflow_step_executions WHERE execution_id = :id AND step_id = :step")
            .bind("id", executionId)
            .bind("step", stepId)
            .map { row, _ -> step(row) }
            .one()
            .awaitFirstOrNull()

    // false when the run already reached this step
    suspend fun insertStep(
        organizationId: UUID,
        executionId: UUID,
        stepId: String,
        type: String,
        status: StepStatus,
        scope: List<Frame>,
        waitUntil: Instant?,
        now: Instant
    ): Boolean =
        db
            .sql(
                "INSERT INTO $m.integration_workflow_step_executions (execution_id, organization_id, step_id, step_type, status, idempotency_key, scope, " +
                    "wait_until, created_at, updated_at) VALUES (:id, :org, :step, :type, :status, :key, CAST(:scope AS jsonb), :waitUntil, :now, :now) " +
                    "ON CONFLICT (execution_id, step_id) DO NOTHING"
            ).bind("id", executionId)
            .bind("org", organizationId)
            .bind("step", stepId)
            .bind("type", type)
            .bind("status", status.name)
            .bind("key", idempotencyKey(executionId, stepId))
            .bind("scope", json.writeValueAsString(scope))
            .nullable("waitUntil", waitUntil?.odt(), OffsetDateTime::class.java)
            .bind("now", now.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    // records that a branch reached the join, in the join's own row (created by the first arrival). the row lock makes
    // concurrent arrivals take turns; a branch already recorded changes nothing and answers null. otherwise the
    // branches recorded so far: the one arrival that completes them schedules the join (Flow.completes)
    suspend fun arrive(
        organizationId: UUID,
        executionId: UUID,
        joinId: String,
        type: String,
        branch: Int,
        scope: List<Frame>,
        now: Instant
    ): List<Int>? =
        db
            .sql(
                "INSERT INTO $m.integration_workflow_step_executions AS s (execution_id, organization_id, step_id, step_type, status, idempotency_key, " +
                    "scope, arrivals, created_at, updated_at) VALUES (:id, :org, :step, :type, 'PENDING', :key, CAST(:scope AS jsonb), ARRAY[:branch], " +
                    ":now, :now) ON CONFLICT (execution_id, step_id) DO UPDATE SET arrivals = array_append(s.arrivals, :branch), updated_at = :now " +
                    "WHERE s.status = 'PENDING' AND NOT (:branch = ANY (s.arrivals)) RETURNING s.arrivals"
            ).bind("id", executionId)
            .bind("org", organizationId)
            .bind("step", joinId)
            .bind("type", type)
            .bind("key", idempotencyKey(executionId, joinId))
            .bind("scope", json.writeValueAsString(scope))
            .bind("branch", branch)
            .bind("now", now.odt())
            .map { row, _ -> ints(row, "arrivals") }
            .one()
            .awaitFirstOrNull()

    // a join that turns out to be a delay or an approval: its row (made by the arrivals) now waits
    suspend fun park(
        executionId: UUID,
        stepId: String,
        waitUntil: Instant?,
        now: Instant
    ) {
        db
            .sql(
                "UPDATE $m.integration_workflow_step_executions SET status = 'WAITING', wait_until = :waitUntil, updated_at = :now " +
                    "WHERE execution_id = :id AND step_id = :step AND status = 'PENDING'"
            ).nullable("waitUntil", waitUntil?.odt(), OffsetDateTime::class.java)
            .bind("now", now.odt())
            .bind("id", executionId)
            .bind("step", stepId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // PENDING (or RUNNING whose worker died) -> RUNNING, one attempt more. null when the step is not runnable
    suspend fun start(
        executionId: UUID,
        stepId: String,
        input: JsonNode?,
        now: Instant
    ): Int? =
        db
            .sql(
                "UPDATE $m.integration_workflow_step_executions SET status = 'RUNNING', attempt = attempt + 1, input = CAST(:input AS jsonb), " +
                    "started_at = COALESCE(started_at, :now), updated_at = :now WHERE execution_id = :id AND step_id = :step " +
                    "AND status IN ('PENDING', 'RUNNING', 'WAITING') RETURNING attempt"
            ).nullable("input", input?.let { json.writeValueAsString(it) }, String::class.java)
            .bind("now", now.odt())
            .bind("id", executionId)
            .bind("step", stepId)
            .map { row, _ -> row.get("attempt", Int::class.javaObjectType)!! }
            .one()
            .awaitFirstOrNull()

    // the step's result, once: false when it was already finished
    suspend fun complete(
        executionId: UUID,
        stepId: String,
        status: StepStatus,
        output: JsonNode?,
        error: String?,
        now: Instant,
        decidedBy: UUID? = null
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_workflow_step_executions SET status = :status, output = CAST(:output AS jsonb), error = :error, " +
                    "decided_by = COALESCE(:by, decided_by), started_at = COALESCE(started_at, :now), finished_at = :now, updated_at = :now " +
                    "WHERE execution_id = :id AND step_id = :step AND status IN ('PENDING', 'RUNNING', 'WAITING')"
            ).bind("status", status.name)
            .nullable("output", output?.let { json.writeValueAsString(it) }, String::class.java)
            .nullable("error", error?.take(2000), String::class.java)
            .nullable("by", decidedBy, UUID::class.java)
            .bind("now", now.odt())
            .bind("id", executionId)
            .bind("step", stepId)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    // a failed attempt the job queue will retry
    suspend fun retrying(
        executionId: UUID,
        stepId: String,
        error: String,
        now: Instant
    ) {
        db
            .sql(
                "UPDATE $m.integration_workflow_step_executions SET status = 'PENDING', error = :error, updated_at = :now " +
                    "WHERE execution_id = :id AND step_id = :step AND status = 'RUNNING'"
            ).bind("error", error.take(2000))
            .bind("now", now.odt())
            .bind("id", executionId)
            .bind("step", stepId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // event triggers of one connection and event type, across the tenant's published, unpaused workflows
    suspend fun eventTriggers(
        organizationId: UUID,
        connectionId: UUID,
        eventType: String
    ): List<LiveTrigger> =
        db
            .sql(
                "SELECT $triggerColumns FROM $m.integration_workflow_triggers t JOIN $m.integration_workflow_definitions d ON d.id = t.definition_id " +
                    "WHERE t.organization_id = :org AND t.type = 'EVENT' AND t.connection_id = :connection AND t.event_type = :eventType AND NOT d.paused"
            ).bind("org", organizationId)
            .bind("connection", connectionId)
            .bind("eventType", eventType)
            .map { row, _ -> trigger(row) }
            .all()
            .collectList()
            .awaitSingle()

    // platform background work: every tenant's unpaused schedule triggers
    suspend fun scheduleTriggers(): List<LiveTrigger> =
        db
            .sql(
                "SELECT $triggerColumns FROM $m.integration_workflow_triggers t JOIN $m.integration_workflow_definitions d ON d.id = t.definition_id " +
                    "WHERE t.type = 'SCHEDULE' AND NOT d.paused ORDER BY t.definition_id"
            ).map { row, _ -> trigger(row) }
            .all()
            .collectList()
            .awaitSingle()

    // the published trigger of one workflow, paused or not. organizationId null: the public webhook route, which
    // knows only the workflow id until its secret is checked
    suspend fun trigger(
        organizationId: UUID?,
        definitionId: UUID
    ): LiveTrigger? =
        db
            .sql(
                "SELECT $triggerColumns FROM $m.integration_workflow_triggers t JOIN $m.integration_workflow_definitions d ON d.id = t.definition_id " +
                    "WHERE t.definition_id = :definition AND (CAST(:org AS uuid) IS NULL OR t.organization_id = :org)"
            ).bind("definition", definitionId)
            .nullable("org", organizationId, UUID::class.java)
            .map { row, _ -> trigger(row) }
            .one()
            .awaitFirstOrNull()

    // compare-and-set: of two schedulers that saw the same last firing, one moves it
    suspend fun fired(
        definitionId: UUID,
        previous: Instant?,
        at: Instant
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_workflow_triggers SET last_fired_at = :at WHERE definition_id = :definition " +
                    "AND last_fired_at IS NOT DISTINCT FROM :previous"
            ).bind("at", at.odt())
            .bind("definition", definitionId)
            .nullable("previous", previous?.odt(), OffsetDateTime::class.java)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    private fun execution(row: Readable) =
        Execution(
            id = row.get("id", UUID::class.java)!!,
            organizationId = row.get("organization_id", UUID::class.java)!!,
            definitionId = row.get("definition_id", UUID::class.java)!!,
            versionNumber = row.get("version_number", Int::class.javaObjectType)!!,
            status = ExecutionStatus.valueOf(row.get("status", String::class.java)!!),
            triggerType = TriggerType.valueOf(row.get("trigger_type", String::class.java)!!),
            triggerKey = row.get("trigger_key", String::class.java),
            trigger = json.readTree(row.get("trigger_payload", String::class.java)!!),
            correlationId = row.get("correlation_id", String::class.java)!!,
            startedBy = row.get("started_by", UUID::class.java),
            error = row.get("error", String::class.java),
            createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
            startedAt = row.get("started_at", OffsetDateTime::class.java)?.toInstant(),
            finishedAt = row.get("finished_at", OffsetDateTime::class.java)?.toInstant()
        )

    private fun step(row: Readable) =
        StepExecution(
            executionId = row.get("execution_id", UUID::class.java)!!,
            stepId = row.get("step_id", String::class.java)!!,
            stepType = row.get("step_type", String::class.java)!!,
            status = StepStatus.valueOf(row.get("status", String::class.java)!!),
            attempt = row.get("attempt", Int::class.javaObjectType)!!,
            input = row.get("input", String::class.java)?.let { json.readTree(it) },
            output = row.get("output", String::class.java)?.let { json.readTree(it) },
            error = row.get("error", String::class.java),
            idempotencyKey = row.get("idempotency_key", String::class.java)!!,
            scope = frames(json.readTree(row.get("scope", String::class.java)!!)),
            arrivals = ints(row, "arrivals"),
            waitUntil = row.get("wait_until", OffsetDateTime::class.java)?.toInstant(),
            decidedBy = row.get("decided_by", UUID::class.java),
            createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
            startedAt = row.get("started_at", OffsetDateTime::class.java)?.toInstant(),
            finishedAt = row.get("finished_at", OffsetDateTime::class.java)?.toInstant()
        )

    private fun trigger(row: Readable) =
        LiveTrigger(
            definitionId = row.get("definition_id", UUID::class.java)!!,
            organizationId = row.get("organization_id", UUID::class.java)!!,
            versionNumber = row.get("version_number", Int::class.javaObjectType)!!,
            type = TriggerType.valueOf(row.get("type", String::class.java)!!),
            config = json.readTree(row.get("config", String::class.java)!!),
            lastFiredAt = row.get("last_fired_at", OffsetDateTime::class.java)?.toInstant(),
            updatedAt = row.get("updated_at", OffsetDateTime::class.java)!!.toInstant(),
            paused = row.get("paused", Boolean::class.javaObjectType) ?: false,
            webhookTokenHash = row.get("webhook_token_hash", String::class.java)
        )

    private fun ints(
        row: Readable,
        name: String
    ): List<Int> = (row.get(name, Array<Int>::class.java) ?: emptyArray()).toList()

    companion object {
        // one per step of a run, the same for every attempt: a retry is recognisable as the same write
        fun idempotencyKey(
            executionId: UUID,
            stepId: String
        ) = "wf:$executionId:$stepId"

        fun frames(node: JsonNode): List<Frame> =
            (0 until node.size()).map { i -> node.get(i).let { Frame(it.get("parallel").stringValue(), it.get("branch").intValue()) } }
    }
}

internal fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

internal fun <T : Any> DatabaseClient.GenericExecuteSpec.nullable(
    name: String,
    value: T?,
    type: Class<T>
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)
