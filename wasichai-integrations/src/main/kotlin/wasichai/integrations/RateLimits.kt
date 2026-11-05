package wasichai.integrations

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import wasichai.integrations.sdk.RateLimitHint
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// a token bucket per connection from the connector's declared limit, and a pause when the provider says
// Retry-After. with shared buckets (ADR-0030) every replica draws on one bucket in postgresql and honours one
// pause; without them, or while the database does not answer, each replica limits on its own and n replicas
// may together reach n times the rate. waiting is a coroutine delay, never a blocked thread.
class RateLimits(
    private val clock: Clock,
    private val shared: RateBuckets? = null,
    private val meters: MeterRegistry = SimpleMeterRegistry()
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
        if (hint == null) return
        if (shared != null) {
            // a pause only this replica knows (the database was away when the 429 came)
            buckets[key]?.pausedUntil?.let { waited(Duration.between(clock.instant(), it).coerceAtLeast(Duration.ZERO), "paused") }
            while (true) {
                val reservation = shared.reserve(SHARED_PREFIX + key, hint.requestsPerSecond, hint.burst) ?: break
                waited(reservation.wait, if (reservation.paused) "paused" else "shared")
                if (!reservation.paused) return
            }
        }
        val bucket = bucket(key, hint)
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
            waited(wait, "local")
        }
    }

    private suspend fun waited(
        wait: Duration,
        source: String
    ) {
        if (wait.isZero) return
        meters.timer("integrations.rate_limit.waits", "source", source).record(wait)
        delay(wait.toMillis())
    }

    // the provider asked for a pause: nobody calls it before then. kept here too, for when the database is away
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
        shared?.pause(SHARED_PREFIX + key, bucket.hint.burst, retryAfter)
    }

    suspend fun pausedUntil(key: String): Instant? {
        val local = buckets[key]?.pausedUntil?.takeIf { it.isAfter(clock.instant()) }
        val elsewhere = shared?.pausedUntil(SHARED_PREFIX + key)
        return listOfNotNull(local, elsewhere).maxOrNull()
    }

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

    private companion object {
        // ai tool limits share the table under their own prefix
        const val SHARED_PREFIX = "http:"
    }
}
