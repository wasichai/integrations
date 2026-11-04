package wasichai.integrations

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import reactor.core.Disposable
import reactor.core.publisher.Flux
import wasichai.integrations.autoconfigure.IntegrationsProperties
import wasichai.integrations.sdk.ConnectorException
import java.time.Clock
import java.time.Duration
import kotlin.math.min

// drains integration_jobs: claim a batch, run each with its handler, record the outcome.
// at least once: a job whose worker dies is claimed again when its lease runs out.
class JobRunner(
    private val repository: JobRepository,
    handlers: List<JobHandler>,
    private val properties: IntegrationsProperties.Jobs,
    private val meters: MeterRegistry,
    private val clock: Clock
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val byKind: Map<String, JobHandler>

    init {
        val duplicates = handlers.groupBy { it.kind }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "job kinds handled twice: $duplicates" }
        byKind = handlers.associateBy { it.kind }
    }

    // returns how many jobs it ran
    suspend fun drainOnce(): Int {
        val jobs = repository.claim(properties.batch, properties.lease, clock.instant())
        coroutineScope { jobs.map { async { run(it) } }.awaitAll() }
        return jobs.size
    }

    private suspend fun run(job: Job) {
        val handler = byKind[job.kind]
        val started = System.nanoTime()
        val outcome =
            try {
                if (handler == null) throw PermanentJobFailure("no handler for job kind '${job.kind}'")
                handler.handle(job)
                repository.succeed(job, clock.instant())
                "succeeded"
            } catch (e: Exception) {
                fail(job, e)
            }
        meters.timer("integrations.jobs", "kind", job.kind, "outcome", outcome).record(Duration.ofNanos(System.nanoTime() - started))
    }

    private suspend fun fail(
        job: Job,
        e: Exception
    ): String {
        val now = clock.instant()
        val (retryable, kind, wait) = classify(e)
        val message = "${e.javaClass.simpleName}: ${e.message}"
        return if (retryable && job.attempts < job.maxAttempts) {
            val at = now.plus(wait ?: backoff(job.attempts))
            repository.retryLater(job, at, message, kind, now)
            log.info("integrations: job {} ({}) attempt {} failed ({}), next at {}", job.id, job.kind, job.attempts, kind, at)
            "retry"
        } else {
            repository.dead(job, message, kind, now)
            log.warn("integrations: job {} ({}) is dead after {} attempts: {}", job.id, job.kind, job.attempts, kind)
            "dead"
        }
    }

    // exponential with a ceiling: 30s, 1m, 2m, 4m ... capped
    fun backoff(attempts: Int): Duration {
        val base = properties.backoff.toMillis()
        val factor = 1L shl min(attempts - 1, 20).coerceAtLeast(0)
        return Duration.ofMillis(min(base * factor, properties.maxBackoff.toMillis()))
    }

    companion object {
        // (retryable, error kind for operators, wait the provider asked for)
        fun classify(e: Throwable): Triple<Boolean, String, Duration?> =
            when (e) {
                is PermanentJobFailure -> Triple(false, "permanent", null)
                is ConnectorException.RateLimited -> Triple(true, "rate_limited", e.retryAfter)
                is ConnectorException.AuthExpired -> Triple(false, "auth_expired", null)
                is ConnectorException -> Triple(e.retryable, if (e.retryable) "transient" else "permanent", null)
                else -> Triple(true, "unexpected", null)
            }
    }
}

// the polling loop. interval has no backpressure: drop ticks instead of dying (wasichai automation's lesson)
class JobWorker(
    private val runner: JobRunner,
    private val properties: IntegrationsProperties.Jobs
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private var subscription: Disposable? = null

    override fun start() {
        if (!properties.workerEnabled || subscription != null) return
        subscription =
            Flux
                .interval(properties.pollInterval)
                .onBackpressureDrop()
                .concatMap { mono { runCatching { runner.drainOnce() }.onFailure { log.warn("integrations: job drain failed: {}", it.message) } } }
                .subscribe({}, { log.error("integrations: job worker stopped", it) })
    }

    override fun stop() {
        subscription?.dispose()
        subscription = null
    }

    override fun isRunning(): Boolean = subscription != null
}
