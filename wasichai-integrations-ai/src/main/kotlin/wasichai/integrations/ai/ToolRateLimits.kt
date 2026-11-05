package wasichai.integrations.ai

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// calls per minute per tool, a token bucket per replica that refuses instead of waiting (the integrations
// RateLimits waits for a provider's budget; a caller over its budget is told 429). n replicas admit up to n times the
// limit: a cluster-wide budget is #47's
class ToolRateLimits(
    private val clock: Clock
) {
    private data class Bucket(
        val perMinute: Int,
        val tokens: Double,
        val at: Instant
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()

    // null when the call may go on, else how long until it may
    fun tryAcquire(
        key: String,
        perMinute: Int
    ): Duration? {
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

    fun forget(key: String) {
        buckets.remove(key)
    }
}
