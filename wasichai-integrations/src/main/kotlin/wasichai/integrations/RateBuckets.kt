package wasichai.integrations

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import wasichai.core.platform.WasichaiSchemas
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.ceil

// token buckets in postgresql, one row per key, shared by every replica (ADR-0030). each call is one
// upsert that refills from the elapsed time and takes a token in the same statement: no read-then-write
// race. time is the database's clock_timestamp(), so replicas with skewed clocks agree.
// every method answers null when the database did not answer in time or failed: the caller falls back to
// its own per-replica bucket, a metric and a log line say so, and a provider call is never held up by it.
class RateBuckets(
    private val db: DatabaseClient,
    schemas: WasichaiSchemas,
    private val meters: MeterRegistry,
    private val timeout: Duration = Duration.ofSeconds(1)
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val m = schemas.metadata
    private val degraded = AtomicBoolean(false)

    // granted: go after wait. paused: nothing was taken, ask again after wait
    data class Reservation(
        val wait: Duration,
        val paused: Boolean
    )

    // takes a token now or reserves the next one (tokens go below zero): the caller waits, then calls. a paused key
    // takes nothing. one round trip whatever the outcome.
    suspend fun reserve(
        key: String,
        perSecond: Double,
        burst: Int
    ): Reservation? =
        guarded("reserve") {
            db
                .sql(
                    "WITH t AS (SELECT clock_timestamp() AS now) " +
                        "INSERT INTO $m.integration_rate_buckets AS b (bucket_key, tokens, updated_at) " +
                        "SELECT :key, CAST(:burst AS double precision) - 1, t.now FROM t " +
                        "ON CONFLICT (bucket_key) DO UPDATE SET " +
                        "tokens = CASE WHEN b.paused_until > EXCLUDED.updated_at THEN b.tokens ELSE $REFILL - 1 END, " +
                        "updated_at = CASE WHEN b.paused_until > EXCLUDED.updated_at THEN b.updated_at " +
                        "ELSE GREATEST(b.updated_at, EXCLUDED.updated_at) END " +
                        "RETURNING b.tokens, b.paused_until, (SELECT now FROM t) AS now"
                ).bind("key", key)
                .bind("rate", perSecond)
                .bind("burst", burst.toDouble())
                .map { row, _ ->
                    val tokens = row.get("tokens", Double::class.javaObjectType)!!
                    val now = row.get("now", OffsetDateTime::class.java)!!.toInstant()
                    val paused = row.get("paused_until", OffsetDateTime::class.java)?.toInstant()?.takeIf { it.isAfter(now) }
                    if (paused != null) {
                        Reservation(Duration.between(now, paused), true)
                    } else {
                        Reservation(seconds(if (tokens < 0) -tokens / perSecond else 0.0), false)
                    }
                }.one()
                .awaitSingle()
        }

    // takes a token only when one is there: null when taken, else how long until one is. a refusal leaves the row
    // as it was, so a caller refused again and again does not drain it.
    suspend fun tryTake(
        key: String,
        perSecond: Double,
        burst: Int
    ): Taken? =
        guarded("try") {
            db
                .sql(
                    "WITH t AS (SELECT clock_timestamp() AS now) " +
                        "INSERT INTO $m.integration_rate_buckets AS b (bucket_key, tokens, updated_at) " +
                        "SELECT :key, CAST(:burst AS double precision) - 1, t.now FROM t " +
                        "ON CONFLICT (bucket_key) DO UPDATE SET " +
                        "tokens = CASE WHEN $REFILL >= 1 THEN $REFILL - 1 ELSE b.tokens END, " +
                        "updated_at = CASE WHEN $REFILL >= 1 THEN EXCLUDED.updated_at ELSE b.updated_at END " +
                        "RETURNING b.tokens, b.updated_at, (SELECT now FROM t) AS now"
                ).bind("key", key)
                .bind("rate", perSecond)
                .bind("burst", burst.toDouble())
                .map { row, _ ->
                    val tokens = row.get("tokens", Double::class.javaObjectType)!!
                    val updated = row.get("updated_at", OffsetDateTime::class.java)!!.toInstant()
                    val now = row.get("now", OffsetDateTime::class.java)!!.toInstant()
                    // updated now: this call took the token. else it is what a refill would have reached
                    if (updated == now) {
                        Taken(null)
                    } else {
                        val refilled = minOf(burst.toDouble(), tokens + Duration.between(updated, now).toNanos().coerceAtLeast(0) / 1e9 * perSecond)
                        Taken(seconds((1.0 - refilled) / perSecond))
                    }
                }.one()
                .awaitSingle()
        }

    data class Taken(
        val wait: Duration?
    )

    // the provider said Retry-After: every replica honours it. a longer pause already there stays
    suspend fun pause(
        key: String,
        burst: Int,
        retryAfter: Duration
    ): Unit? =
        guarded("pause") {
            db
                .sql(
                    "INSERT INTO $m.integration_rate_buckets AS b (bucket_key, tokens, updated_at, paused_until) " +
                        "VALUES (:key, CAST(:burst AS double precision), clock_timestamp(), clock_timestamp() + make_interval(secs => :secs)) " +
                        "ON CONFLICT (bucket_key) DO UPDATE SET paused_until = GREATEST(b.paused_until, EXCLUDED.paused_until)"
                ).bind("key", key)
                .bind("burst", burst.toDouble())
                .bind("secs", retryAfter.toMillis() / 1000.0)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
            Unit
        }

    suspend fun pausedUntil(key: String): Instant? =
        guarded("paused") {
            db
                .sql("SELECT paused_until FROM $m.integration_rate_buckets WHERE bucket_key = :key AND paused_until > clock_timestamp()")
                .bind("key", key)
                .map { row, _ -> row.get("paused_until", OffsetDateTime::class.java)!!.toInstant() }
                .one()
                .awaitFirstOrNull()
        }

    // a changed limit starts afresh
    suspend fun forget(key: String) {
        guarded("forget") {
            db
                .sql("DELETE FROM $m.integration_rate_buckets WHERE bucket_key = :key")
                .bind("key", key)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        }
    }

    // null when the database failed or was slow: the caller decides locally
    private suspend fun <T> guarded(
        operation: String,
        block: suspend () -> T
    ): T? =
        try {
            withTimeout(timeout.toMillis()) { block() }.also {
                if (degraded.compareAndSet(true, false)) log.info("Shared rate limits answer again")
            }
        } catch (e: TimeoutCancellationException) {
            fallback(operation, "timeout", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fallback(operation, "error", e)
        }

    private fun <T> fallback(
        operation: String,
        reason: String,
        e: Exception
    ): T? {
        meters.counter("integrations.rate_limit.fallbacks", "operation", operation, "reason", reason).increment()
        // once per outage, not once per call
        if (degraded.compareAndSet(false, true)) log.warn("Shared rate limits unavailable ({}), each replica limits on its own until they answer", reason, e)
        return null
    }

    private fun seconds(s: Double): Duration = if (s <= 0) Duration.ZERO else Duration.ofMillis(ceil(s * 1000).toLong())

    private companion object {
        // the tokens a refill reaches now, never above the burst
        const val REFILL =
            "LEAST(CAST(:burst AS double precision), " +
                "b.tokens + GREATEST(0, EXTRACT(EPOCH FROM EXCLUDED.updated_at - b.updated_at)::double precision) * CAST(:rate AS double precision))"
    }
}
