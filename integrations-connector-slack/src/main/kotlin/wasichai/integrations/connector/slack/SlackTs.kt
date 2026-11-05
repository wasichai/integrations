package wasichai.integrations.connector.slack

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

// a slack ts ("1512085950.000216"): epoch seconds with six decimals, unique per channel. it is a message's id and
// orders the history; kept as the string slack sent, compared as a number
internal object SlackTs {
    private val FORMAT = Regex("""^\d{1,12}\.\d{6}$""")

    fun isTs(value: String?): Boolean = value != null && FORMAT.matches(value)

    fun instant(ts: String?): Instant? {
        if (!isTs(ts)) return null
        val seconds = ts!!.substringBefore('.').toLong()
        val micros = ts.substringAfter('.').toLong()
        return Instant.ofEpochSecond(seconds, micros * 1_000)
    }

    fun max(
        a: String?,
        b: String?
    ): String? =
        when {
            a == null -> b
            b == null -> a
            BigDecimal(a) >= BigDecimal(b) -> a
            else -> b
        }

    // ts minus a duration, never below zero, in slack's own format
    fun minus(
        ts: String,
        duration: Duration
    ): String =
        BigDecimal(ts)
            .subtract(BigDecimal.valueOf(duration.toMillis(), 3))
            .max(BigDecimal.ZERO)
            .setScale(6, RoundingMode.DOWN)
            .toPlainString()
}
