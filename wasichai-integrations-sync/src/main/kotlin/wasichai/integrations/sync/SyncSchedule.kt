package wasichai.integrations.sync

import java.time.Instant
import java.util.UUID

// an ACTIVE definition with a schedule or a reconcile interval, and when each kind of run last started
data class ScheduleCandidate(
    val definitionId: UUID,
    val organizationId: UUID,
    val scheduleSeconds: Int?,
    val reconcileSeconds: Int?,
    // the last run that read the feeds (any trigger but RECONCILE), and the last reconcile
    val lastIncremental: Instant?,
    val lastReconcile: Instant?
)

// when runs are due. pure: the scheduler and the status endpoint both read it, the unit tests pin it.
object SyncSchedule {
    // never run: due at once
    fun nextIncremental(
        candidate: ScheduleCandidate,
        now: Instant
    ): Instant? = candidate.scheduleSeconds?.let { s -> candidate.lastIncremental?.plusSeconds(s.toLong()) ?: now }

    fun nextReconcile(
        candidate: ScheduleCandidate,
        now: Instant
    ): Instant? = candidate.reconcileSeconds?.let { s -> candidate.lastReconcile?.plusSeconds(s.toLong()) ?: now }

    // at most one run per tick: one waits behind the other anyway (one run per definition at a time).
    // a reconcile goes first: it reads everything and fixes what an incremental run cannot see.
    fun due(
        candidate: ScheduleCandidate,
        now: Instant
    ): RunTrigger? =
        when {
            nextReconcile(candidate, now)?.let { !it.isAfter(now) } == true -> RunTrigger.RECONCILE
            nextIncremental(candidate, now)?.let { !it.isAfter(now) } == true -> RunTrigger.SCHEDULE
            else -> null
        }
}
