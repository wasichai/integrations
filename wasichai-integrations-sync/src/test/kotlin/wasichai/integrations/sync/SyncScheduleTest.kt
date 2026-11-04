package wasichai.integrations.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SyncScheduleTest {
    private val now = Instant.parse("2026-11-04T10:00:00Z")

    private fun candidate(
        schedule: Int? = null,
        reconcile: Int? = null,
        lastIncremental: Instant? = null,
        lastReconcile: Instant? = null
    ) = ScheduleCandidate(UUID.randomUUID(), UUID.randomUUID(), schedule, reconcile, lastIncremental, lastReconcile)

    @Test
    fun `nothing is due without an interval`() {
        assertNull(SyncSchedule.due(candidate(lastIncremental = now.minusSeconds(9999)), now))
        assertNull(SyncSchedule.nextIncremental(candidate(), now))
        assertNull(SyncSchedule.nextReconcile(candidate(), now))
    }

    @Test
    fun `a definition never run is due at once`() {
        assertEquals(RunTrigger.SCHEDULE, SyncSchedule.due(candidate(schedule = 60), now))
        assertEquals(now, SyncSchedule.nextIncremental(candidate(schedule = 60), now))
        assertEquals(RunTrigger.RECONCILE, SyncSchedule.due(candidate(reconcile = 300), now))
    }

    @Test
    fun `an incremental run is due once its interval has passed since the last one started`() {
        assertNull(SyncSchedule.due(candidate(schedule = 60, lastIncremental = now.minusSeconds(59)), now))
        assertEquals(RunTrigger.SCHEDULE, SyncSchedule.due(candidate(schedule = 60, lastIncremental = now.minusSeconds(60)), now))
        assertEquals(now.plusSeconds(1), SyncSchedule.nextIncremental(candidate(schedule = 60, lastIncremental = now.minusSeconds(59)), now))
    }

    @Test
    fun `a reconcile counts only reconcile runs and goes before an incremental one`() {
        val both = candidate(schedule = 60, reconcile = 300, lastIncremental = now.minusSeconds(120), lastReconcile = now.minusSeconds(301))
        assertEquals(RunTrigger.RECONCILE, SyncSchedule.due(both, now))
        val recent = candidate(schedule = 60, reconcile = 300, lastIncremental = now.minusSeconds(120), lastReconcile = now.minusSeconds(10))
        assertEquals(RunTrigger.SCHEDULE, SyncSchedule.due(recent, now))
        assertEquals(now.plusSeconds(290), SyncSchedule.nextReconcile(recent, now))
        // a fresh incremental run does not push the reconcile back
        assertEquals(RunTrigger.RECONCILE, SyncSchedule.due(candidate(reconcile = 300, lastIncremental = now, lastReconcile = now.minusSeconds(400)), now))
    }
}
