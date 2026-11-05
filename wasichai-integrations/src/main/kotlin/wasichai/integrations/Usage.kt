package wasichai.integrations

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.Disposable
import reactor.core.publisher.Flux
import wasichai.core.common.ValidationException
import wasichai.core.platform.WasichaiSchemas
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// one tenant's count of one metric on one UTC day
data class UsageCount(
    val organizationId: UUID,
    val day: LocalDate,
    val metric: String,
    val count: Long
)

data class UsageView(
    val day: LocalDate,
    val metric: String,
    val count: Long
)

// per-tenant daily counters (ADR-0029). modules call record() on their hot paths: it only adds in memory, never
// blocks, never touches the database. flush() writes the sums as upsert increments, on an interval and on shutdown.
// a failed flush puts its counts back: nothing is dropped while the replica lives
class UsageMeter(
    private val clock: Clock,
    private val write: suspend (List<UsageCount>) -> Unit
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Key(
        val organizationId: UUID,
        val day: LocalDate,
        val metric: String
    )

    private val pending = ConcurrentHashMap<Key, Long>()

    fun record(
        organizationId: UUID,
        metric: String,
        amount: Long = 1
    ) {
        require(METRIC.matches(metric)) { "usage metric '$metric' must match $METRIC" }
        if (amount <= 0) return
        // merge is atomic per key: a flush that removed the key leaves a new entry, never a lost increment
        pending.merge(Key(organizationId, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC), metric), amount, Long::plus)
    }

    // the counts not written yet (tests, the shutdown log)
    fun pending(): Long = pending.values.sum()

    suspend fun flush(): Int {
        val drained = pending.keys.mapNotNull { key -> pending.remove(key)?.let { UsageCount(key.organizationId, key.day, key.metric, it) } }
        if (drained.isEmpty()) return 0
        try {
            write(drained)
        } catch (e: Exception) {
            drained.forEach { pending.merge(Key(it.organizationId, it.day, it.metric), it.count, Long::plus) }
            log.warn("integrations: usage flush of {} counters failed, kept for the next one: {}", drained.size, e.message)
            throw e
        }
        return drained.size
    }

    companion object {
        val METRIC = Regex("^[a-z][a-z0-9._]{0,63}$")

        // the core's own: every http exchange a connector makes through a connection
        const val PROVIDER_CALLS = "provider.calls"
    }
}

// flushes on an interval and once more when the app stops. stop runs on the shutdown thread, not a reactive one:
// waiting there is allowed, and bounded
class UsageFlusher(
    private val meter: UsageMeter,
    private val interval: Duration
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private var subscription: Disposable? = null

    override fun start() {
        if (subscription != null) return
        subscription =
            Flux
                .interval(interval)
                .onBackpressureDrop()
                .concatMap { mono { runCatching { meter.flush() } } }
                .subscribe({}, { log.error("integrations: usage flusher stopped", it) })
    }

    override fun stop() {
        subscription?.dispose()
        subscription = null
        runBlocking {
            withTimeoutOrNull(SHUTDOWN_WAIT.toMillis()) { runCatching { meter.flush() } }
        }
        meter.pending().takeIf { it > 0 }?.let { log.warn("integrations: {} usage counts were not written at shutdown", it) }
    }

    override fun isRunning(): Boolean = subscription != null

    companion object {
        private val SHUTDOWN_WAIT = Duration.ofSeconds(10)
    }
}

class UsageRepository(
    private val db: DatabaseClient,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata

    // one statement per counter: increments, never overwrites what another replica wrote
    suspend fun add(counts: List<UsageCount>) {
        for (c in counts) {
            db
                .sql(
                    "INSERT INTO $m.integration_usage_daily (organization_id, day, metric, count, updated_at) VALUES (:org, :day, :metric, :count, now()) " +
                        "ON CONFLICT (organization_id, day, metric) DO UPDATE SET count = integration_usage_daily.count + EXCLUDED.count, updated_at = now()"
                ).bind("org", c.organizationId)
                .bind("day", c.day)
                .bind("metric", c.metric)
                .bind("count", c.count)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    suspend fun list(
        organizationId: UUID,
        from: LocalDate,
        to: LocalDate,
        metric: String?
    ): List<UsageView> =
        db
            .sql(
                "SELECT day, metric, count FROM $m.integration_usage_daily WHERE organization_id = :org AND day BETWEEN :from AND :to " +
                    "AND (CAST(:metric AS text) IS NULL OR metric = :metric) ORDER BY day, metric"
            ).bind("org", organizationId)
            .bind("from", from)
            .bind("to", to)
            .bindNullable("metric", metric)
            .map { row, _ ->
                UsageView(row.get("day", LocalDate::class.java)!!, row.get("metric", String::class.java)!!, row.get("count", Long::class.javaObjectType)!!)
            }.all()
            .collectList()
            .awaitSingle()
}

class UsageService(
    private val access: IntegrationsAccess,
    private val repository: UsageRepository,
    private val clock: Clock
) {
    // any member reads the tenant's own counters; the days are UTC
    suspend fun list(
        from: LocalDate?,
        to: LocalDate?,
        metric: String?
    ): List<UsageView> {
        val user = access.reader()
        val end = to ?: LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val start = from ?: end.minusDays(DEFAULT_DAYS - 1)
        if (start.isAfter(end)) throw ValidationException("Invalid range", "from", "must not be after to")
        if (ChronoUnit.DAYS.between(start, end) >= MAX_DAYS) throw ValidationException("Invalid range", "to", "at most $MAX_DAYS days")
        if (metric != null && !UsageMeter.METRIC.matches(metric)) throw ValidationException("Invalid metric", "metric", "unknown metric name")
        return repository.list(user.organizationId, start, end, metric)
    }

    companion object {
        const val DEFAULT_DAYS = 30L
        const val MAX_DAYS = 366L
    }
}

@RestController
@RequestMapping("/api/integrations")
class UsageController(
    private val usage: UsageService
) {
    @GetMapping("/usage")
    suspend fun list(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
        @RequestParam(required = false) metric: String?
    ): List<UsageView> = usage.list(from, to, metric)
}
