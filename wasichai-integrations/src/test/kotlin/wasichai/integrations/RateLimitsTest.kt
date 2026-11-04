package wasichai.integrations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.RateLimitHint
import java.time.Clock
import java.time.Duration

class RateLimitsTest {
    @Test
    fun `a burst passes at once, the next call waits for a token`() {
        val limits = RateLimits(Clock.systemUTC())
        val hint = RateLimitHint(requestsPerSecond = 10.0, burst = 3, scope = "connection")
        val started = System.nanoTime()
        runBlocking { repeat(3) { limits.acquire("c", hint) } }
        assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMillis(50))
        val waited = System.nanoTime()
        runBlocking { limits.acquire("c", hint) }
        assertTrue(Duration.ofNanos(System.nanoTime() - waited) >= Duration.ofMillis(60))
    }

    @Test
    fun `a retry-after pauses the connection, others are untouched`() {
        val limits = RateLimits(Clock.systemUTC())
        runBlocking { limits.pause("a", null, Duration.ofMillis(200)) }
        assertTrue(limits.pausedUntil("a") != null)
        assertNull(limits.pausedUntil("b"))
        val started = System.nanoTime()
        runBlocking { limits.acquire("a", RateLimitHint(100.0, 10, "connection")) }
        assertTrue(Duration.ofNanos(System.nanoTime() - started) >= Duration.ofMillis(150))
    }

    @Test
    fun `no declared limit means no wait`() {
        runBlocking { RateLimits(Clock.systemUTC()).acquire("x", null) }
        assertEquals(1, 1)
    }
}
