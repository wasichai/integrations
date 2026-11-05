package wasichai.integrations

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import wasichai.integrations.autoconfigure.IntegrationsProperties
import java.time.Duration
import java.time.Instant

// the batching of the retention purge, without a database: each fake task holds a number of purgeable rows
class RetentionTest {
    private val now = Instant.parse("2026-11-05T10:00:00Z")

    private class Fake(
        override val name: String,
        var rows: Long,
        override val defaultMaxAge: Duration = Duration.ofDays(30),
        val fail: Boolean = false
    ) : RetentionTask {
        override val table = "t_$name"
        val calls = mutableListOf<Pair<Instant, Int>>()

        override suspend fun purge(
            cutoff: Instant,
            limit: Int
        ): Long {
            calls += cutoff to limit
            if (fail) throw IllegalStateException("db down")
            val deleted = minOf(rows, limit.toLong())
            rows -= deleted
            return deleted
        }
    }

    private fun properties(
        batch: Int = 1000,
        maxBatches: Int = 100,
        ages: Map<String, Duration> = emptyMap()
    ) = IntegrationsProperties.Retention(batchSize = batch, maxBatches = maxBatches, maxAge = ages)

    @Test
    fun `a task is called in batches until one comes back short`() {
        val task = Fake("events", 2500)
        val meters = SimpleMeterRegistry()
        val purged = runBlocking { Retention(listOf(task), properties(), meters).purge(now) }
        assertEquals(mapOf("events" to 2500L), purged)
        assertEquals(3, task.calls.size)
        assertEquals(List(3) { now.minus(Duration.ofDays(30)) to 1000 }, task.calls)
        assertEquals(2500.0, meters.counter(Retention.PURGED, "task", "events", "table", "t_events").count())
    }

    @Test
    fun `an exact multiple of the batch costs one empty call more, never a loop`() {
        val task = Fake("jobs", 2000)
        runBlocking { Retention(listOf(task), properties(), SimpleMeterRegistry()).purge(now) }
        assertEquals(3, task.calls.size)
        assertEquals(0, task.rows)
    }

    @Test
    fun `max-batches bounds one tick, and the next tick goes on`() {
        val task = Fake("events", 10_000)
        val retention = Retention(listOf(task), properties(batch = 100, maxBatches = 5), SimpleMeterRegistry())
        assertEquals(500L, runBlocking { retention.purge(now) }["events"])
        assertEquals(9_500, task.rows)
        assertEquals(500L, runBlocking { retention.purge(now) }["events"])
    }

    @Test
    fun `max-age overrides a task's default by name, and zero keeps forever`() {
        val runs = Fake("sync.runs", 10, Duration.ofDays(90))
        val kept = Fake("usage", 10, Duration.ofDays(400))
        val purged =
            runBlocking {
                Retention(listOf(runs, kept), properties(ages = mapOf("sync.runs" to Duration.ofDays(7), "usage" to Duration.ZERO)), SimpleMeterRegistry())
                    .purge(now)
            }
        assertEquals(now.minus(Duration.ofDays(7)), runs.calls.single().first)
        assertEquals(emptyList<Pair<Instant, Int>>(), kept.calls)
        assertFalse(purged.containsKey("usage"))
    }

    @Test
    fun `a failing task is counted and the others still run`() {
        val broken = Fake("events", 10, fail = true)
        val fine = Fake("jobs", 10)
        val meters = SimpleMeterRegistry()
        val purged = runBlocking { Retention(listOf(broken, fine), properties(), meters).purge(now) }
        assertEquals(0L, purged["events"])
        assertEquals(10L, purged["jobs"])
        assertEquals(1.0, meters.counter(Retention.FAILURES, "task", "events").count())
    }

    @Test
    fun `two tasks may not share a name`() {
        assertThrows(IllegalArgumentException::class.java) { Retention(listOf(Fake("events", 0), Fake("events", 0)), properties(), SimpleMeterRegistry()) }
    }
}
