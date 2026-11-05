package wasichai.integrations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// the in-memory buffer of the usage meter: sums per tenant, day and metric, written once, kept when a write fails
class UsageMeterTest {
    private class MovableClock(
        var now: Instant
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = now
    }

    private val clock = MovableClock(Instant.parse("2026-11-05T23:59:00Z"))
    private val org = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val written = mutableListOf<UsageCount>()
    private var failing = false
    private val meter =
        UsageMeter(clock) { counts ->
            if (failing) throw IllegalStateException("db down")
            written += counts
        }

    @Test
    fun `counts are summed per tenant, day and metric and written once`() {
        repeat(3) { meter.record(org, "provider.calls") }
        meter.record(org, "sync.records", 5)
        meter.record(other, "provider.calls")
        // past midnight utc: a new day
        clock.now = Instant.parse("2026-11-06T00:01:00Z")
        meter.record(org, "provider.calls")
        assertEquals(4, runBlocking { meter.flush() })
        val day = LocalDate.parse("2026-11-05")
        assertEquals(
            setOf(
                UsageCount(org, day, "provider.calls", 3),
                UsageCount(org, day, "sync.records", 5),
                UsageCount(other, day, "provider.calls", 1),
                UsageCount(org, day.plusDays(1), "provider.calls", 1)
            ),
            written.toSet()
        )
        // nothing new: nothing written
        assertEquals(0, runBlocking { meter.flush() })
        assertEquals(4, written.size)
    }

    @Test
    fun `a failed write keeps the counts for the next flush`() {
        meter.record(org, "workflow.steps", 2)
        failing = true
        assertThrows(IllegalStateException::class.java) { runBlocking { meter.flush() } }
        assertEquals(2, meter.pending())
        meter.record(org, "workflow.steps")
        failing = false
        runBlocking { meter.flush() }
        assertEquals(listOf(UsageCount(org, LocalDate.parse("2026-11-05"), "workflow.steps", 3)), written)
        assertEquals(0, meter.pending())
    }

    @Test
    fun `increments racing a flush are never lost`() {
        val pool = Executors.newFixedThreadPool(4)
        repeat(4) { pool.submit { repeat(10_000) { meter.record(org, "ai.tool_calls") } } }
        repeat(20) { runBlocking { meter.flush() } }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        runBlocking { meter.flush() }
        assertEquals(40_000, written.sumOf { it.count })
    }

    @Test
    fun `a metric name is checked, and nothing or less is not counted`() {
        assertThrows(IllegalArgumentException::class.java) { meter.record(org, "Provider Calls") }
        meter.record(org, "provider.calls", 0)
        assertEquals(0, meter.pending())
    }
}
