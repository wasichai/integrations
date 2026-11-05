package wasichai.integrations

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.r2dbc.core.DatabaseClient
import reactor.core.Disposable
import reactor.core.publisher.Flux
import wasichai.core.platform.ClusterLock
import wasichai.integrations.autoconfigure.IntegrationsProperties
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// what one module keeps and lets go of (ADR-0029). each module contributes a bean per table it purges; the core runs
// them all and knows none of their tables. a task deletes only finished history: never an open conflict, a pending
// approval or anything still running.
interface RetentionTask {
    // the key under wasichai.integrations.retention.max-age (events, sync.runs, ...)
    val name: String

    // the table it deletes from: the metric's tag
    val table: String

    val defaultMaxAge: Duration

    // deletes at most limit rows that finished before cutoff; how many it deleted
    suspend fun purge(
        cutoff: Instant,
        limit: Int
    ): Long
}

// a task that is one DELETE statement with :cutoff and :limit. the statement deletes by a sub-select with LIMIT (and
// SKIP LOCKED where rows can be busy): postgres has no DELETE ... LIMIT, and a short batch never holds long locks
class SqlRetentionTask(
    override val name: String,
    override val table: String,
    override val defaultMaxAge: Duration,
    private val db: DatabaseClient,
    private val sql: String,
    // the cutoff bound as a date (a table keyed by day), else as a timestamptz
    private val byDay: Boolean = false
) : RetentionTask {
    override suspend fun purge(
        cutoff: Instant,
        limit: Int
    ): Long =
        db
            .sql(sql)
            .let { if (byDay) it.bind("cutoff", LocalDate.ofInstant(cutoff, ZoneOffset.UTC)) else it.bind("cutoff", cutoff.odt()) }
            .bind("limit", limit)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
}

// runs every task in batches until it has nothing older than its cutoff, or until max-batches (the next tick goes on).
// a failing task is logged and counted; the others still run
class Retention(
    private val tasks: List<RetentionTask>,
    private val properties: IntegrationsProperties.Retention,
    private val meters: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        val names = tasks.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(names.isEmpty()) { "retention tasks share a name: $names" }
    }

    // task name -> rows deleted; a task kept forever (max-age 0) is absent
    suspend fun purge(now: Instant): Map<String, Long> {
        val purged = linkedMapOf<String, Long>()
        for (task in tasks) {
            val age = maxAge(task)
            if (age.isZero || age.isNegative) continue
            val cutoff = now.minus(age)
            var total = 0L
            try {
                for (batch in 1..properties.maxBatches) {
                    val deleted = task.purge(cutoff, properties.batchSize)
                    total += deleted
                    if (deleted < properties.batchSize) break
                }
            } catch (e: Exception) {
                meters.counter(FAILURES, "task", task.name).increment()
                log.warn("integrations: retention of {} failed after {} rows: {}", task.name, total, e.message)
            }
            meters.counter(PURGED, "task", task.name, "table", task.table).increment(total.toDouble())
            if (total > 0) log.info("integrations: retention purged {} rows of {} older than {}", total, task.table, cutoff)
            purged[task.name] = total
        }
        return purged
    }

    fun maxAge(task: RetentionTask): Duration = properties.maxAge[task.name] ?: task.defaultMaxAge

    companion object {
        const val PURGED = "integrations.retention.purged"
        const val FAILURES = "integrations.retention.failures"
    }
}

// platform background work over every tenant (no CurrentUser): one replica per tick (cluster lock). ticks that come
// while one runs are dropped, never queued
class RetentionScheduler(
    private val retention: Retention,
    private val lock: ClusterLock,
    private val properties: IntegrationsProperties.Retention,
    private val clock: Clock
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private var subscription: Disposable? = null

    // what it purged; null when another replica holds the tick
    suspend fun tick(now: Instant = clock.instant()): Map<String, Long>? = lock.tryLock(LOCK)?.use { retention.purge(now) }

    override fun start() {
        if (!properties.enabled || subscription != null) return
        subscription =
            Flux
                .interval(properties.initialDelay, properties.interval)
                .onBackpressureDrop()
                .concatMap { mono { runCatching { tick() }.onFailure { log.warn("integrations: retention tick failed: {}", it.message) } } }
                .subscribe({}, { log.error("integrations: retention scheduler stopped", it) })
    }

    override fun stop() {
        subscription?.dispose()
        subscription = null
    }

    override fun isRunning(): Boolean = subscription != null

    companion object {
        const val LOCK = "integrations.retention"
    }
}
