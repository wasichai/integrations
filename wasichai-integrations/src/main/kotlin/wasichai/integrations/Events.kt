package wasichai.integrations

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.core.platform.WasichaiSchemas
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

enum class EventSource {
    WEBHOOK,
    POLL,
    SYNC,
    SYSTEM
}

enum class EventStatus {
    RECEIVED,
    PROCESSED,
    IGNORED,
    FAILED
}

// the event envelope (ADR-0007): the platform's own identity and bookkeeping around what the provider said.
// the provider's payload is kept as received; ordering is never inferred from it.
data class IntegrationEvent(
    val id: UUID,
    val organizationId: UUID,
    val connectionId: UUID,
    val connectorId: String,
    val endpointId: UUID?,
    val providerEventId: String?,
    val dedupeKey: String,
    val eventType: String,
    val resourceType: String?,
    val resourceId: String?,
    val occurredAt: Instant?,
    val receivedAt: Instant,
    val source: EventSource,
    val schemaVersion: Int,
    val correlationId: String,
    val causationId: String?,
    val payload: JsonNode,
    val metadata: JsonNode,
    val status: EventStatus,
    val processedAt: Instant?,
    val error: String?
)

// what reacts to events: the sync engine (and later workflows). called from a job, at least once per event.
interface IntegrationEventListener {
    // true when it acted on the event; an event nobody acts on is IGNORED, never dropped silently
    suspend fun onEvent(event: IntegrationEvent): Boolean
}

class EventRepository(
    private val db: DatabaseClient,
    private val json: JsonMapper,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata
    private val columns =
        "id, organization_id, connection_id, connector_id, endpoint_id, provider_event_id, dedupe_key, event_type, resource_type, resource_id, " +
            "occurred_at, received_at, source, schema_version, correlation_id, causation_id, payload::text AS payload, metadata::text AS metadata, " +
            "status, processed_at, error"

    // null when this delivery was already stored: a provider retrying is not a new event
    suspend fun insert(event: IntegrationEvent): UUID? =
        db
            .sql(
                "INSERT INTO $m.integration_events (id, organization_id, connection_id, connector_id, endpoint_id, provider_event_id, dedupe_key, " +
                    "event_type, resource_type, resource_id, occurred_at, received_at, source, schema_version, correlation_id, causation_id, payload, " +
                    "metadata) VALUES (:id, :org, :connection, :connector, :endpoint, :providerId, :dedupe, :type, :resourceType, :resourceId, " +
                    ":occurred, :received, :source, :version, :correlation, :causation, CAST(:payload AS jsonb), CAST(:metadata AS jsonb)) " +
                    "ON CONFLICT (connection_id, dedupe_key) DO NOTHING RETURNING id"
            ).bind("id", event.id)
            .bind("org", event.organizationId)
            .bind("connection", event.connectionId)
            .bind("connector", event.connectorId)
            .let { s -> event.endpointId?.let { s.bind("endpoint", it) } ?: s.bindNull("endpoint", UUID::class.java) }
            .bindNullable("providerId", event.providerEventId)
            .bind("dedupe", event.dedupeKey)
            .bind("type", event.eventType)
            .bindNullable("resourceType", event.resourceType)
            .bindNullable("resourceId", event.resourceId)
            .let { s -> event.occurredAt?.let { s.bind("occurred", it.odt()) } ?: s.bindNull("occurred", OffsetDateTime::class.java) }
            .bind("received", event.receivedAt.odt())
            .bind("source", event.source.name)
            .bind("version", event.schemaVersion)
            .bind("correlation", event.correlationId)
            .bindNullable("causation", event.causationId)
            .bind("payload", json.writeValueAsString(event.payload))
            .bind("metadata", json.writeValueAsString(event.metadata))
            .map { row, _ -> row.get("id", UUID::class.java)!! }
            .one()
            .awaitFirstOrNull()

    suspend fun find(
        organizationId: UUID,
        id: UUID
    ): IntegrationEvent? =
        db
            .sql("SELECT $columns FROM $m.integration_events WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    // newest first, keyset by (received_at, id)
    suspend fun page(
        organizationId: UUID,
        connectionId: UUID?,
        status: EventStatus?,
        before: Pair<Instant, UUID>?,
        limit: Int
    ): List<IntegrationEvent> =
        db
            .sql(
                "SELECT $columns FROM $m.integration_events WHERE organization_id = :org " +
                    "AND (CAST(:connection AS uuid) IS NULL OR connection_id = :connection) " +
                    "AND (CAST(:status AS text) IS NULL OR status = :status) " +
                    (if (before != null) "AND (received_at, id) < (:beforeAt, :beforeId) " else "") +
                    "ORDER BY received_at DESC, id DESC LIMIT :limit"
            ).bind("org", organizationId)
            .let { s -> connectionId?.let { s.bind("connection", it) } ?: s.bindNull("connection", UUID::class.java) }
            .bindNullable("status", status?.name)
            .let { s -> before?.let { s.bind("beforeAt", it.first.odt()).bind("beforeId", it.second) } ?: s }
            .bind("limit", limit)
            .map { row, _ -> read(row) }
            .all()
            .collectList()
            .awaitSingle()

    suspend fun mark(
        id: UUID,
        status: EventStatus,
        error: String?,
        now: Instant
    ) {
        db
            .sql("UPDATE $m.integration_events SET status = :status, error = :error, processed_at = :now WHERE id = :id")
            .bind("status", status.name)
            .bindNullable("error", error?.take(2000))
            .bind("now", now.odt())
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun reopen(
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("UPDATE $m.integration_events SET status = 'RECEIVED', error = NULL, processed_at = NULL WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    private fun read(row: Readable): IntegrationEvent =
        IntegrationEvent(
            id = row.get("id", UUID::class.java)!!,
            organizationId = row.get("organization_id", UUID::class.java)!!,
            connectionId = row.get("connection_id", UUID::class.java)!!,
            connectorId = row.get("connector_id", String::class.java)!!,
            endpointId = row.get("endpoint_id", UUID::class.java),
            providerEventId = row.get("provider_event_id", String::class.java),
            dedupeKey = row.get("dedupe_key", String::class.java)!!,
            eventType = row.get("event_type", String::class.java)!!,
            resourceType = row.get("resource_type", String::class.java),
            resourceId = row.get("resource_id", String::class.java),
            occurredAt = row.get("occurred_at", OffsetDateTime::class.java)?.toInstant(),
            receivedAt = row.get("received_at", OffsetDateTime::class.java)!!.toInstant(),
            source = EventSource.valueOf(row.get("source", String::class.java)!!),
            schemaVersion = row.get("schema_version", Int::class.javaObjectType)!!,
            correlationId = row.get("correlation_id", String::class.java)!!,
            causationId = row.get("causation_id", String::class.java),
            payload = json.readTree(jsonText(row, "payload")),
            metadata = json.readTree(jsonText(row, "metadata")),
            status = EventStatus.valueOf(row.get("status", String::class.java)!!),
            processedAt = row.get("processed_at", OffsetDateTime::class.java)?.toInstant(),
            error = row.get("error", String::class.java)
        )
}

// the job that hands a stored event to its listeners
class EventJobs(
    private val events: EventRepository,
    private val listeners: List<IntegrationEventListener>,
    private val clock: java.time.Clock
) : JobHandler {
    override val kind = KIND

    override suspend fun handle(job: Job) {
        val id = UUID.fromString(job.payload.path("eventId").asString())
        val event = events.find(job.organizationId, id) ?: return
        val acted =
            try {
                listeners.map { it.onEvent(event) }.any { it }
            } catch (e: Exception) {
                events.mark(id, EventStatus.FAILED, "${e.javaClass.simpleName}: ${e.message}", clock.instant())
                throw e
            }
        events.mark(id, if (acted) EventStatus.PROCESSED else EventStatus.IGNORED, null, clock.instant())
    }

    companion object {
        const val KIND = "integrations.event"
    }
}
