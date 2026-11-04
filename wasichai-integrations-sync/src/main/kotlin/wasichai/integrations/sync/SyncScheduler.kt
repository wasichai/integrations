package wasichai.integrations.sync

import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import reactor.core.Disposable
import reactor.core.publisher.Flux
import wasichai.core.platform.ClusterLock
import wasichai.integrations.sync.autoconfigure.SyncProperties
import java.time.Clock
import java.time.Instant

// starts scheduled and reconcile runs of every tenant. platform background work: it reads the definitions of all
// organizations (no CurrentUser) and enqueues each run under its own organization. one replica per tick (cluster lock);
// the job dedupe key keeps one run per definition at a time anyway. interval ticks are dropped, never queued.
class SyncScheduler(
    private val runs: SyncRunRepository,
    private val engine: SyncEngine,
    private val lock: ClusterLock,
    private val properties: SyncProperties,
    private val clock: Clock
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private var subscription: Disposable? = null

    // the runs it enqueued; empty when another replica holds the tick
    suspend fun tick(now: Instant = clock.instant()): List<Pair<ScheduleCandidate, RunTrigger>> =
        lock.tryLock(LOCK)?.use {
            runs.scheduleCandidates(null).mapNotNull { candidate ->
                val trigger = SyncSchedule.due(candidate, now) ?: return@mapNotNull null
                engine.enqueue(candidate.organizationId, candidate.definitionId, trigger)?.let { candidate to trigger }
            }
        } ?: emptyList()

    override fun start() {
        if (!properties.schedulerEnabled || subscription != null) return
        subscription =
            Flux
                .interval(properties.schedulerTick)
                .onBackpressureDrop()
                .concatMap { mono { runCatching { tick() }.onFailure { log.warn("integrations: sync scheduler tick failed: {}", it.message) } } }
                .subscribe({}, { log.error("integrations: sync scheduler stopped", it) })
    }

    override fun stop() {
        subscription?.dispose()
        subscription = null
    }

    override fun isRunning(): Boolean = subscription != null

    companion object {
        const val LOCK = "integrations.sync.scheduler"
    }
}
