package wasichai.integrations

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import wasichai.integrations.sdk.RateLimitHint
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// a token bucket per connection from the connector's declared limit, and a pause when the provider says
// Retry-After. per replica: n replicas may together reach n times the rate; the provider's 429 still
// stops all of them through the retry path (ADR-0008).
class RateLimits(
    private val clock: Clock
) {
    private class Bucket(
        val hint: RateLimitHint,
        var tokens: Double,
        var refilledAt: Instant,
        var pausedUntil: Instant? = null
    ) {
        val mutex = Mutex()
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()

    suspend fun acquire(
        key: String,
        hint: RateLimitHint?
    ) {
        val bucket = bucket(key, hint ?: return)
        while (true) {
            val wait =
                bucket.mutex.withLock {
                    val now = clock.instant()
                    bucket.pausedUntil?.let { if (it.isAfter(now)) return@withLock Duration.between(now, it) }
                    refill(bucket, now)
                    if (bucket.tokens >= 1.0) {
                        bucket.tokens -= 1.0
                        Duration.ZERO
                    } else {
                        Duration.ofMillis(((1.0 - bucket.tokens) / bucket.hint.requestsPerSecond * 1000).toLong().coerceAtLeast(1))
                    }
                }
            if (wait.isZero) return
            delay(wait.toMillis())
        }
    }

    // the provider asked for a pause: nobody on this replica calls it before then
    suspend fun pause(
        key: String,
        hint: RateLimitHint?,
        retryAfter: Duration
    ) {
        val bucket = bucket(key, hint ?: RateLimitHint(1.0, 1, "connection"))
        bucket.mutex.withLock {
            val until = clock.instant().plus(retryAfter)
            if (bucket.pausedUntil?.isAfter(until) != true) bucket.pausedUntil = until
        }
    }

    fun pausedUntil(key: String): Instant? = buckets[key]?.pausedUntil?.takeIf { it.isAfter(clock.instant()) }

    private fun bucket(
        key: String,
        hint: RateLimitHint
    ): Bucket = buckets.computeIfAbsent(key) { Bucket(hint, hint.burst.toDouble(), clock.instant()) }

    private fun refill(
        bucket: Bucket,
        now: Instant
    ) {
        val elapsed = Duration.between(bucket.refilledAt, now).toNanos() / 1e9
        if (elapsed <= 0) return
        bucket.tokens = minOf(bucket.hint.burst.toDouble(), bucket.tokens + elapsed * bucket.hint.requestsPerSecond)
        bucket.refilledAt = now
    }
}
