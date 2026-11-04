package wasichai.integrations.sync

import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// the same conventions as the integrations module: timestamptz as OffsetDateTime, jsonb read as ::text
internal fun Instant.odt(): OffsetDateTime = atOffset(ZoneOffset.UTC)

internal fun Readable.text(column: String): String? = get(column, String::class.java)

internal fun Readable.instant(column: String): Instant? = get(column, OffsetDateTime::class.java)?.toInstant()

internal fun Readable.uuid(column: String): UUID? = get(column, UUID::class.java)

internal fun Readable.int(column: String): Int? = get(column, Int::class.javaObjectType)

internal fun Readable.long(column: String): Long? = get(column, Long::class.javaObjectType)

internal fun DatabaseClient.GenericExecuteSpec.bindText(
    name: String,
    value: String?
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, String::class.java) else bind(name, value)

internal fun DatabaseClient.GenericExecuteSpec.bindInt(
    name: String,
    value: Int?
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, Int::class.javaObjectType) else bind(name, value)

internal fun DatabaseClient.GenericExecuteSpec.bindUuid(
    name: String,
    value: UUID?
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, UUID::class.java) else bind(name, value)

internal fun DatabaseClient.GenericExecuteSpec.bindInstant(
    name: String,
    value: Instant?
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, OffsetDateTime::class.java) else bind(name, value.odt())
