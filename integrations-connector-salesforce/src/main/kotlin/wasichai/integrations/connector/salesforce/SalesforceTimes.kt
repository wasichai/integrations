package wasichai.integrations.connector.salesforce

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

internal object SalesforceTimes {
    // the rest api writes 2026-11-04T12:00:00.000+0000 (offset without a colon)
    private val NATIVE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX")
    private val WINDOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx").withZone(ZoneOffset.UTC)
    private val HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

    fun parse(text: String): Instant =
        try {
            OffsetDateTime.parse(text, NATIVE).toInstant()
        } catch (_: DateTimeParseException) {
            OffsetDateTime.parse(text).toInstant()
        }

    fun parseOrNull(text: String?): Instant? = text?.let { runCatching { parse(it) }.getOrNull() }

    // the updated/deleted resources take 2026-11-04T12:00:00+00:00
    fun window(instant: Instant): String = WINDOW.format(instant.truncatedTo(ChronoUnit.SECONDS))

    // If-Unmodified-Since has second precision: round up so our own millisecond version does not read as newer
    fun httpDate(instant: Instant): String {
        val seconds = instant.truncatedTo(ChronoUnit.SECONDS)
        return HTTP_DATE.format(if (seconds == instant) seconds else seconds.plusSeconds(1))
    }
}
