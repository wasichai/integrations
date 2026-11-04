package wasichai.integrations

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

enum class JobStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    DEAD
}

data class Job(
    val id: UUID,
    val organizationId: UUID,
    val kind: String,
    val dedupeKey: String?,
    val payload: JsonNode,
    val status: JobStatus,
    val attempts: Int,
    val maxAttempts: Int,
    val nextAttemptAt: Instant,
    val leaseUntil: Instant?,
    val lastError: String?,
    val errorKind: String?,
    val correlationId: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val finishedAt: Instant?
)

// one kind of durable work. the sync engine and webhook processing are its users (ADR-0008).
// handle may run more than once for one job (a lease ran out, a crash after the work): make it idempotent.
interface JobHandler {
    val kind: String

    suspend fun handle(job: Job)
}

// thrown by a handler that knows trying again is pointless
class PermanentJobFailure(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class JobRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata
    private val columns =
        "id, organization_id, kind, dedupe_key, payload::text AS payload, status, attempts, max_attempts, next_attempt_at, lease_until, " +
            "last_error, error_kind, " +
            "correlation_id, created_at, updated_at, finished_at"

    // false when the same key is already waiting or running
    suspend fun enqueue(
        organizationId: UUID,
        kind: String,
        payload: Any,
        dedupeKey: String? = null,
        runAt: Instant,
        correlationId: String? = null,
        maxAttempts: Int = 8
    ): UUID? =
        db
            .sql(
                "INSERT INTO $m.integration_jobs (organization_id, kind, dedupe_key, payload, next_attempt_at, correlation_id, max_attempts) " +
                    "VALUES (:org, :kind, :key, CAST(:payload AS jsonb), :runAt, :correlation, :max) " +
                    "ON CONFLICT (organization_id, kind, dedupe_key) WHERE dedupe_key IS NOT NULL AND status IN ('PENDING', 'RUNNING') " +
                    "DO NOTHING RETURNING id"
            ).bind("org", organizationId)
            .bind("kind", kind)
            .bindNullable("key", dedupeKey)
            .bind("payload", json.writeValueAsString(payload))
            .bind("runAt", runAt.odt())
            .bindNullable("correlation", correlationId)
            .bind("max", maxAttempts)
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitFirstOrNull()

    // due jobs, and running ones whose lease ran out: their worker is gone
    suspend fun claim(
        batch: Int,
        lease: Duration,
        now: Instant
    ): List<Job> =
        db
            .sql(
                "UPDATE $m.integration_jobs SET status = 'RUNNING', attempts = attempts + 1, lease_until = :lease, updated_at = :now " +
                    "WHERE id IN (SELECT id FROM $m.integration_jobs WHERE (status = 'PENDING' AND next_attempt_at <= :now) " +
                    "OR (status = 'RUNNING' AND lease_until < :now) ORDER BY next_attempt_at LIMIT :batch FOR UPDATE SKIP LOCKED) " +
                    "RETURNING $columns"
            ).bind("lease", now.plus(lease).odt())
            .bind("now", now.odt())
            .bind("batch", batch)
            .map { row, _ -> read(row) }
            .all()
            .collectList()
            .awaitSingle()

    // only the holder of the current lease may finish a job: a worker whose lease ran out lost it
    suspend fun succeed(
        job: Job,
        now: Instant
    ): Boolean = finish(job, "status = 'SUCCEEDED', finished_at = :now, last_error = NULL, error_kind = NULL, lease_until = NULL", now, emptyMap())

    suspend fun retryLater(
        job: Job,
        at: Instant,
        error: String,
        kind: String,
        now: Instant
    ): Boolean =
        finish(
            job,
            "status = 'PENDING', next_attempt_at = :at, last_error = :error, error_kind = :kind, lease_until = NULL",
            now,
            mapOf("at" to at.odt(), "error" to error.take(2000), "kind" to kind)
        )

    suspend fun dead(
        job: Job,
        error: String,
        kind: String,
        now: Instant
    ): Boolean =
        finish(
            job,
            "status = 'DEAD', finished_at = :now, last_error = :error, error_kind = :kind, lease_until = NULL",
            now,
            mapOf(
                "error" to error.take(2000),
                "kind" to kind
            )
        )

    private suspend fun finish(
        job: Job,
        set: String,
        now: Instant,
        values: Map<String, Any>
    ): Boolean {
        var spec =
            db
                .sql("UPDATE $m.integration_jobs SET $set, updated_at = :now WHERE id = :id AND status = 'RUNNING' AND attempts = :attempts")
                .bind("now", now.odt())
                .bind("id", job.id)
                .bind("attempts", job.attempts)
        values.forEach { (k, v) -> spec = spec.bind(k, v) }
        return spec.fetch().rowsUpdated().awaitSingle() == 1L
    }

    // a person sends a dead job back: from now, with a fresh attempt budget
    suspend fun requeue(
        organizationId: UUID,
        id: UUID,
        now: Instant
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_jobs SET status = 'PENDING', attempts = 0, next_attempt_at = :now, finished_at = NULL, updated_at = :now " +
                    "WHERE organization_id = :org AND id = :id AND status = 'DEAD'"
            ).bind("now", now.odt())
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    suspend fun find(
        organizationId: UUID,
        id: UUID
    ): Job? =
        db
            .sql("SELECT $columns FROM $m.integration_jobs WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun list(
        organizationId: UUID,
        status: JobStatus?,
        kind: String?,
        limit: Int
    ): List<Job> =
        db
            .sql(
                "SELECT $columns FROM $m.integration_jobs WHERE organization_id = :org " +
                    "AND (CAST(:status AS text) IS NULL OR status = :status) AND (CAST(:kind AS text) IS NULL OR kind = :kind) " +
                    "ORDER BY updated_at DESC, id LIMIT :limit"
            ).bind("org", organizationId)
            .bindNullable("status", status?.name)
            .bindNullable("kind", kind)
            .bind("limit", limit)
            .map { row, _ -> read(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun counts(organizationId: UUID): Map<JobStatus, Long> =
        db
            .sql("SELECT status, count(*) AS n FROM $m.integration_jobs WHERE organization_id = :org GROUP BY status")
            .bind("org", organizationId)
            .map { row, _ -> JobStatus.valueOf(row.get("status", String::class.java)!!) to row.get("n", Long::class.javaObjectType)!! }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()

    private fun read(row: Readable): Job =
        Job(
            id = row.get("id", UUID::class.java)!!,
            organizationId = row.get("organization_id", UUID::class.java)!!,
            kind = row.get("kind", String::class.java)!!,
            dedupeKey = row.get("dedupe_key", String::class.java),
            payload = json.readTree(jsonText(row, "payload")),
            status = JobStatus.valueOf(row.get("status", String::class.java)!!),
            attempts = row.get("attempts", Int::class.javaObjectType)!!,
            maxAttempts = row.get("max_attempts", Int::class.javaObjectType)!!,
            nextAttemptAt = row.get("next_attempt_at", OffsetDateTime::class.java)!!.toInstant(),
            leaseUntil = row.get("lease_until", OffsetDateTime::class.java)?.toInstant(),
            lastError = row.get("last_error", String::class.java),
            errorKind = row.get("error_kind", String::class.java),
            correlationId = row.get("correlation_id", String::class.java),
            createdAt = row.get("created_at", OffsetDateTime::class.java)!!.toInstant(),
            updatedAt = row.get("updated_at", OffsetDateTime::class.java)!!.toInstant(),
            finishedAt = row.get("finished_at", OffsetDateTime::class.java)?.toInstant()
        )
}
