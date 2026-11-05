package wasichai.integrations.connector.jira

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

internal object JiraTimes {
    // jira writes 2026-11-04T12:00:00.000+0000 (offset without a colon) and takes the same on write
    private val NATIVE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXX")

    // xx writes +0000 where XX would write Z
    private val WRITE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxx")
    private val JQL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun parse(text: String): Instant =
        try {
            OffsetDateTime.parse(text, NATIVE).toInstant()
        } catch (_: DateTimeParseException) {
            OffsetDateTime.parse(text).toInstant()
        }

    fun parseOrNull(text: String?): Instant? = text?.let { runCatching { parse(it) }.getOrNull() }

    fun format(instant: Instant): String = WRITE.format(instant.atOffset(ZoneOffset.UTC))

    // jql dates have minute precision and are read in the searching user's time zone. one minute back and
    // truncated: the boundary minute is read again (at least once), never skipped
    fun jqlLowerBound(
        since: Instant,
        zone: ZoneId
    ): String = JQL.format(since.minus(1, ChronoUnit.MINUTES).atZone(zone).truncatedTo(ChronoUnit.MINUTES))
}
