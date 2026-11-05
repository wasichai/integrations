package wasichai.integrations.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class ToolRateLimitsTest {
    private class MovableClock(
        var now: Instant
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovableClock(Instant.parse("2026-11-05T10:00:00Z"))
    private val limits = ToolRateLimits(clock)

    @Test
    fun `a burst of the per-minute limit, then a refusal that says how long to wait`() {
        repeat(3) { assertNull(limits.tryAcquire("t", 3)) }
        val wait = limits.tryAcquire("t", 3)
        assertNotNull(wait)
        assertEquals(Duration.ofSeconds(20), wait)
        clock.now = clock.now.plusSeconds(20)
        assertNull(limits.tryAcquire("t", 3))
        assertNotNull(limits.tryAcquire("t", 3))
    }

    @Test
    fun `tools are counted apart, and a changed limit starts afresh`() {
        assertNull(limits.tryAcquire("a", 1))
        assertNotNull(limits.tryAcquire("a", 1))
        assertNull(limits.tryAcquire("b", 1))
        assertNull(limits.tryAcquire("a", 2))
        limits.forget("b")
        assertNull(limits.tryAcquire("b", 1))
    }
}
