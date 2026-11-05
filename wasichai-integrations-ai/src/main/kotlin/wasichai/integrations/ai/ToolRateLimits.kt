package wasichai.integrations.ai

import wasichai.integrations.RateBuckets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// calls per minute per tool, a token bucket that refuses instead of waiting (the integrations RateLimits waits for
// a provider's budget; a caller over its budget is told 429). with shared buckets (ADR-0030) one bucket in
// postgresql for every replica, keyed "tool:<key>"; without them, or while the database does not answer, one per
// replica, and n replicas admit up to n times the limit.
class ToolRateLimits(
    private val clock: Clock,
    private val shared: RateBuckets? = null
) {
    private data class Bucket(
        val perMinute: Int,
        val tokens: Double,
        val at: Instant
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    // null when the call may go on, else how long until it may
    suspend fun tryAcquire(
        key: String,
        perMinute: Int
    ): Duration? {
        shared?.tryTake(PREFIX + key, perMinute / 60.0, perMinute)?.let { return it.wait }
        var wait: Duration? = null
        // compute is atomic per key and holds no lock across a suspension
        buckets.compute(key) { _, old ->
            val now = clock.instant()
            val start = if (old == null || old.perMinute != perMinute) Bucket(perMinute, perMinute.toDouble(), now) else old
            val elapsed = Duration.between(start.at, now).toNanos().coerceAtLeast(0) / 1e9
            val tokens = minOf(perMinute.toDouble(), start.tokens + elapsed * perMinute / 60.0)
            if (tokens >= 1.0) {
                wait = null
                Bucket(perMinute, tokens - 1.0, now)
            } else {
                wait = Duration.ofMillis(((1.0 - tokens) * 60_000.0 / perMinute).toLong().coerceAtLeast(1))
                Bucket(perMinute, tokens, now)
            }
        }
        return wait
    }

    // a changed or deleted tool starts afresh
    suspend fun forget(key: String) {
        buckets.remove(key)
        shared?.forget(PREFIX + key)
    }

    companion object {
        private const val PREFIX = "tool:"

        // the tenant in the key too: a row of the shared table says whose tool it counts
        fun key(
            organizationId: java.util.UUID,
            toolId: java.util.UUID
        ): String = "$organizationId:$toolId"
    }
}
