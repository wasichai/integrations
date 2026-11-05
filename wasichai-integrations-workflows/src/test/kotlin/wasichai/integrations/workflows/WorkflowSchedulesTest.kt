package wasichai.integrations.workflows

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

class WorkflowSchedulesTest {
    private val utc = ZoneId.of("UTC")
    private val at = Instant.parse("2026-11-05T10:00:30Z")

    @Test
    fun `nothing is due before the next firing`() {
        assertNull(WorkflowSchedules.due("0 * * * * *", utc, at, Instant.parse("2026-11-05T10:00:59Z")))
    }

    @Test
    fun `the next firing is due once it has passed`() {
        assertEquals(Instant.parse("2026-11-05T10:01:00Z"), WorkflowSchedules.due("0 * * * * *", utc, at, Instant.parse("2026-11-05T10:01:00Z")))
    }

    @Test
    fun `missed firings collapse into the latest one`() {
        assertEquals(Instant.parse("2026-11-05T10:07:00Z"), WorkflowSchedules.due("0 * * * * *", utc, at, Instant.parse("2026-11-05T10:07:42Z")))
    }

    @Test
    fun `the zone decides the wall clock`() {
        // 09:00 in Lima is 14:00 UTC
        val from = Instant.parse("2026-11-05T13:00:00Z")
        assertNull(WorkflowSchedules.due("0 0 9 * * *", ZoneId.of("America/Lima"), from, Instant.parse("2026-11-05T13:59:59Z")))
        assertEquals(
            Instant.parse("2026-11-05T14:00:00Z"),
            WorkflowSchedules.due("0 0 9 * * *", ZoneId.of("America/Lima"), from, Instant.parse("2026-11-05T14:00:01Z"))
        )
    }
}
