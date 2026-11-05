package wasichai.integrations.workflows

import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.support.CronExpression
import reactor.core.Disposable
import reactor.core.publisher.Flux
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import wasichai.core.platform.ChangeOrigin
import wasichai.core.platform.ClusterLock
import wasichai.integrations.IntegrationEvent
import wasichai.integrations.IntegrationEventListener
import wasichai.integrations.Transactions
import wasichai.integrations.workflows.autoconfigure.WorkflowsProperties
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

// event triggers: an integration event of a connection starts every published, unpaused workflow whose trigger names
// that connection and event type. at least once per event (EventJobs); the trigger key event:<id> starts it once
class WorkflowEventTrigger(
    private val executions: ExecutionRepository,
    private val engine: WorkflowEngine
) : IntegrationEventListener {
    override suspend fun onEvent(event: IntegrationEvent): Boolean {
        val triggers = executions.eventTriggers(event.organizationId, event.connectionId, event.eventType)
        triggers.forEach { t ->
            engine.start(t.organizationId, t.definitionId, t.versionNumber, TriggerType.EVENT, payload(event), "event:${event.id}", event.correlationId, null)
        }
        return triggers.isNotEmpty()
    }

    companion object {
        fun payload(event: IntegrationEvent): JsonNode =
            JsonNodeFactory.instance
                .objectNode()
                .put("type", TriggerType.EVENT.json)
                .put("eventId", event.id.toString())
                .put("connectionId", event.connectionId.toString())
                .put("eventType", event.eventType)
                .put("resourceType", event.resourceType)
                .put("resourceId", event.resourceId)
                .put("occurredAt", event.occurredAt?.toString())
                .set("payload", event.payload)
    }
}

// when a schedule fires. pure, for the scheduler and its unit tests
object WorkflowSchedules {
    private const val MAX_CATCH_UP = 100_000

    // the latest firing after `from` that is not after `now`, or null. firings missed while nothing ran (downtime)
    // collapse into this one: a schedule never fires a burst
    fun due(
        cron: String,
        zone: ZoneId,
        from: Instant,
        now: Instant
    ): Instant? {
        val expression = CronExpression.parse(cron)
        var next = expression.next(from.atZone(zone)) ?: return null
        if (next.toInstant().isAfter(now)) return null
        repeat(MAX_CATCH_UP) {
            val after = expression.next(next) ?: return next.toInstant()
            if (after.toInstant().isAfter(now)) return next.toInstant()
            next = after
        }
        return next.toInstant()
    }
}

// schedule triggers of every tenant: platform background work (no CurrentUser); each run starts under its own
// organization. one replica per tick (cluster lock); a firing is also claimed by a compare-and-set of last_fired_at
// and keyed schedule:<instant>, so even two schedulers without the lock start it once
class WorkflowScheduler(
    private val executions: ExecutionRepository,
    private val engine: WorkflowEngine,
    private val lock: ClusterLock,
    private val tx: Transactions,
    private val properties: WorkflowsProperties,
    private val clock: Clock
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger(javaClass)
    private var subscription: Disposable? = null

    // the runs it started; empty when another replica holds the tick
    suspend fun tick(now: Instant = clock.instant()): List<UUID> =
        lock.tryLock(LOCK)?.use {
            executions.scheduleTriggers().mapNotNull { t ->
                runCatching { fire(t, now) }
                    .onFailure { log.warn("integrations: workflow {} schedule failed to fire: {}", t.definitionId, it.message) }
                    .getOrNull()
            }
        } ?: emptyList()

    private suspend fun fire(
        trigger: LiveTrigger,
        now: Instant
    ): UUID? {
        val cron = trigger.cron ?: return null
        val due = WorkflowSchedules.due(cron, trigger.zone, trigger.lastFiredAt ?: trigger.updatedAt, now) ?: return null
        val payload =
            JsonNodeFactory.instance
                .objectNode()
                .put("type", TriggerType.SCHEDULE.json)
                .put("firedAt", due.toString())
                .set("payload", JsonNodeFactory.instance.objectNode())
        // the claim and the run in one transaction: a crash between them neither loses nor repeats the firing
        return tx.run {
            if (!executions.fired(trigger.definitionId, trigger.lastFiredAt, due)) return@run null
            engine.start(
                trigger.organizationId,
                trigger.definitionId,
                trigger.versionNumber,
                TriggerType.SCHEDULE,
                payload,
                "schedule:$due",
                ChangeOrigin.newCorrelationId(),
                null
            )
        }
    }

    override fun start() {
        if (!properties.schedulerEnabled || subscription != null) return
        subscription =
            Flux
                .interval(properties.schedulerTick)
                .onBackpressureDrop()
                .concatMap { mono { runCatching { tick() }.onFailure { log.warn("integrations: workflow scheduler tick failed: {}", it.message) } } }
                .subscribe({}, { log.error("integrations: workflow scheduler stopped", it) })
    }

    override fun stop() {
        subscription?.dispose()
        subscription = null
    }

    override fun isRunning(): Boolean = subscription != null

    companion object {
        const val LOCK = "integrations.workflows.scheduler"
    }
}
