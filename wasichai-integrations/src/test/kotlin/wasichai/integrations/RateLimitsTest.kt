package wasichai.integrations

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.RateLimitHint
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// virtual time: the limiter's clock reads the test scheduler, its delays advance it. no wall-clock assertion.
@OptIn(ExperimentalCoroutinesApi::class)
class RateLimitsTest {
    private class SchedulerClock(
        private val scheduler: TestCoroutineScheduler
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(scheduler.currentTime)
    }

    private val hint = RateLimitHint(requestsPerSecond = 10.0, burst = 3, scope = "connection")

    @Test
    fun `a burst passes at once, the next call waits for a token`() =
        runTest {
            val limits = RateLimits(SchedulerClock(testScheduler))
            repeat(3) { limits.acquire("c", hint) }
            assertEquals(0, testScheduler.currentTime, "the burst costs no time")
            limits.acquire("c", hint)
            assertEquals(100, testScheduler.currentTime, "one token at 10 per second")
            limits.acquire("c", hint)
            assertEquals(200, testScheduler.currentTime)
        }

    @Test
    fun `a retry-after pauses the connection, others are untouched`() =
        runTest {
            val limits = RateLimits(SchedulerClock(testScheduler))
            limits.pause("a", null, Duration.ofSeconds(2))
            assertNotNull(limits.pausedUntil("a"))
            assertNull(limits.pausedUntil("b"))
            limits.acquire("b", RateLimitHint(100.0, 10, "connection"))
            assertEquals(0, testScheduler.currentTime, "another connection is not paused")
            limits.acquire("a", RateLimitHint(100.0, 10, "connection"))
            assertEquals(2000, testScheduler.currentTime)
            assertNull(limits.pausedUntil("a"))
        }

    @Test
    fun `no declared limit means no wait`() =
        runTest {
            RateLimits(SchedulerClock(testScheduler)).acquire("x", null)
            assertEquals(0, testScheduler.currentTime)
        }
}
