package wasichai.integrations

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

// r2dbc-postgresql binds timestamptz as OffsetDateTime
internal fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)
