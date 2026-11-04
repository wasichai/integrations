package wasichai.integrations

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactive.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import wasichai.core.common.ConflictException
import wasichai.core.common.FieldViolation
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.platform.WasichaiSchemas
import wasichai.integrations.autoconfigure.IntegrationsProperties
import wasichai.integrations.sdk.WebhookHandler
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookVerification
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

enum class EndpointStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    DISABLED
}

data class WebhookEndpoint(
    val id: UUID,
    val organizationId: UUID,
    val connectionId: UUID,
    val connectorId: String,
    val secretSealed: String?,
    val status: EndpointStatus,
    val lastDeliveryAt: Instant?,
    val createdAt: Instant
)

data class WebhookEndpointView(
    val id: UUID,
    val connectionId: UUID,
    val connectorId: String,
    // what to register at the provider; null while wasichai.integrations.public-base-url is not set
    val url: String?,
    val status: EndpointStatus,
    val secretSet: Boolean,
    val lastDeliveryAt: Instant?,
    val createdAt: Instant
)

data class CreateEndpointRequest(
    // a secret the provider app shows at registration (when it works that way); absent for handshake providers
    val secret: String? = null
)

data class VerificationTokenView(
    val token: String
)

// the answer to a delivery, ready for the controller
data class WebhookAnswer(
    val status: Int,
    val body: ByteArray? = null
)

data class EventView(
    val eventId: UUID,
    val tenantId: UUID,
    val connectionId: UUID,
    val provider: String,
    val providerEventId: String?,
    val resourceType: String?,
    val resourceId: String?,
    val eventType: String,
    val occurredAt: Instant?,
    val receivedAt: Instant,
    val schemaVersion: Int,
    val correlationId: String,
    val causationId: String?,
    val source: EventSource,
    val status: EventStatus,
    val processedAt: Instant?,
    val error: String?,
    val payload: tools.jackson.databind.JsonNode,
    val metadata: tools.jackson.databind.JsonNode
)

data class EventPage(
    val content: List<EventView>,
    val nextCursor: String?
)

class WebhookEndpointRepository(
    private val db: DatabaseClient,
    schemas: WasichaiSchemas
) {
    private val m = schemas.metadata
    private val columns = "id, organization_id, connection_id, connector_id, secret_sealed, status, last_delivery_at, created_at"

    suspend fun insert(endpoint: WebhookEndpoint) {
        db
            .sql(
                "INSERT INTO $m.integration_webhook_endpoints " +
                    "(id, organization_id, connection_id, connector_id, secret_sealed, status, created_at, updated_at) VALUES (:id, :org, :connection, :connector, :secret, :status, :now, :now)"
            ).bind("id", endpoint.id)
            .bind("org", endpoint.organizationId)
            .bind("connection", endpoint.connectionId)
            .bind("connector", endpoint.connectorId)
            .bindNullable("secret", endpoint.secretSealed)
            .bind("status", endpoint.status.name)
            .bind("now", endpoint.createdAt.odt())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    // the public route knows only the endpoint id and the connector: no tenant to filter on yet
    suspend fun findForDelivery(
        id: UUID,
        connectorId: String
    ): WebhookEndpoint? =
        db
            .sql("SELECT $columns FROM $m.integration_webhook_endpoints WHERE id = :id AND connector_id = :connector AND status <> 'DISABLED'")
            .bind("id", id)
            .bind("connector", connectorId)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun find(
        organizationId: UUID,
        id: UUID
    ): WebhookEndpoint? =
        db
            .sql("SELECT $columns FROM $m.integration_webhook_endpoints WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .map { row, _ -> read(row) }
            .one()
            .awaitFirstOrNull()

    suspend fun list(
        organizationId: UUID,
        connectionId: UUID
    ): List<WebhookEndpoint> =
        db
            .sql(
                "SELECT $columns FROM $m.integration_webhook_endpoints WHERE organization_id = :org AND connection_id = :connection ORDER BY created_at"
            ).bind("org", organizationId)
            .bind("connection", connectionId)
            .map { row, _ -> read(row) }
            .all()
            .collectList()
            .awaitSingle()

    // the handshake secret is stored once: only while none is set (a replayed handshake changes nothing)
    suspend fun storeHandshakeSecret(
        id: UUID,
        sealed: String,
        now: Instant
    ): Boolean =
        db
            .sql(
                "UPDATE $m.integration_webhook_endpoints SET secret_sealed = :sealed, updated_at = :now " +
                    "WHERE id = :id AND secret_sealed IS NULL"
            ).bind("sealed", sealed)
            .bind("now", now.odt())
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    suspend fun delivered(
        id: UUID,
        now: Instant
    ) {
        db
            .sql("UPDATE $m.integration_webhook_endpoints SET status = 'ACTIVE', last_delivery_at = :now, updated_at = :now WHERE id = :id")
            .bind("now", now.odt())
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    suspend fun delete(
        organizationId: UUID,
        id: UUID
    ): Boolean =
        db
            .sql("DELETE FROM $m.integration_webhook_endpoints WHERE organization_id = :org AND id = :id")
            .bind("org", organizationId)
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    private fun read(row: Readable) =
        WebhookEndpoint(
            row.get("id", UUID::class.java)!!,
            row.get("organization_id", UUID::class.java)!!,
            row.get("connection_id", UUID::class.java)!!,
            row.get("connector_id", String::class.java)!!,
            row.get("secret_sealed", String::class.java),
            EndpointStatus.valueOf(row.get("status", String::class.java)!!),
            row.get("last_delivery_at", OffsetDateTime::class.java)?.toInstant(),
            row.get("created_at", OffsetDateTime::class.java)!!.toInstant()
        )
}

class WebhookService(
    private val access: IntegrationsAccess,
    private val registry: ConnectorRegistry,
    private val connections: ConnectionRepository,
    private val endpoints: WebhookEndpointRepository,
    private val events: EventRepository,
    private val jobs: JobRepository,
    private val cipher: CredentialCipher,
    private val json: JsonMapper,
    private val tx: Transactions,
    private val properties: IntegrationsProperties,
    private val clock: Clock
) {
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun create(
        connectionId: UUID,
        request: CreateEndpointRequest
    ): WebhookEndpointView {
        val user = access.manager()
        val connection = connections.find(user.organizationId, connectionId) ?: throw NotFoundException("Connection $connectionId not found")
        if (registry.find(connection.connectorId) !is WebhookHandler) {
            throw ValidationException("No webhooks", listOf(FieldViolation("connectorId", "connector '${connection.connectorId}' does not receive webhooks")))
        }
        if (properties.publicBaseUrl == null) throw ConflictException("Webhooks need wasichai.integrations.public-base-url")
        val id = UUID.randomUUID()
        val sealed = request.secret?.takeIf { it.isNotBlank() }?.let { cipher.seal(it, secretContext(user.organizationId, id)) }
        val endpoint =
            WebhookEndpoint(id, user.organizationId, connection.id, connection.connectorId, sealed, EndpointStatus.PENDING_VERIFICATION, null, clock.instant())
        endpoints.insert(endpoint)
        return view(endpoint)
    }

    suspend fun list(connectionId: UUID): List<WebhookEndpointView> {
        val user = access.reader()
        connections.find(user.organizationId, connectionId) ?: throw NotFoundException("Connection $connectionId not found")
        return endpoints.list(user.organizationId, connectionId).map(::view)
    }

    // some providers (notion) send a token the admin must paste back into the provider's ui: the one secret
    // the platform shows, to an administrator only, and only for an endpoint that holds one
    suspend fun verificationToken(id: UUID): VerificationTokenView {
        val user = access.manager()
        val endpoint = endpoints.find(user.organizationId, id) ?: throw NotFoundException("Webhook endpoint $id not found")
        val sealed = endpoint.secretSealed ?: throw NotFoundException("Webhook endpoint $id has received no verification yet")
        return VerificationTokenView(cipher.open(sealed, secretContext(endpoint.organizationId, endpoint.id)))
    }

    suspend fun delete(id: UUID) {
        val user = access.manager()
        if (!endpoints.delete(user.organizationId, id)) throw NotFoundException("Webhook endpoint $id not found")
    }

    // the public route. unknown endpoint and bad signature answer without telling which tenant or why
    suspend fun receive(
        connectorId: String,
        endpointId: String,
        headers: Map<String, List<String>>,
        body: ByteArray
    ): WebhookAnswer {
        if (body.size > properties.webhooks.maxBodyBytes) return WebhookAnswer(413)
        val id = runCatching { UUID.fromString(endpointId) }.getOrNull() ?: return WebhookAnswer(404)
        val endpoint = endpoints.findForDelivery(id, connectorId) ?: return WebhookAnswer(404)
        val handler = registry.find(connectorId) as? WebhookHandler ?: return WebhookAnswer(404)
        val secret = endpoint.secretSealed?.let { cipher.open(it, secretContext(endpoint.organizationId, endpoint.id)) }
        val request = WebhookRequest(headers, body)
        return when (val verdict = handler.verify(request, secret)) {
            is WebhookVerification.Invalid -> {
                log.info("integrations: webhook delivery to endpoint {} refused: {}", endpoint.id, verdict.reason)
                WebhookAnswer(401)
            }
            is WebhookVerification.Handshake -> {
                verdict.secretToStore?.let {
                    endpoints.storeHandshakeSecret(endpoint.id, cipher.seal(it, secretContext(endpoint.organizationId, endpoint.id)), clock.instant())
                }
                WebhookAnswer(verdict.status, verdict.responseBody)
            }
            WebhookVerification.Valid -> {
                accept(endpoint, handler, request)
                WebhookAnswer(200)
            }
        }
    }

    // stored and queued in one transaction: an event is never kept without its job, nor a job without its event
    private suspend fun accept(
        endpoint: WebhookEndpoint,
        handler: WebhookHandler,
        request: WebhookRequest
    ) {
        val now = clock.instant()
        val parsed = handler.parse(request)
        val payload =
            runCatching {
                json.readTree(request.body)
            }.getOrElse { JsonNodeFactory.instance.objectNode().put("base64", Base64.getEncoder().encodeToString(request.body)) }
        val bodyHash = sha256(request.body)
        tx.run {
            parsed.forEachIndexed { i, event ->
                val dedupe = event.providerEventId?.let { "id:$it" } ?: "sha256:$bodyHash:$i"
                val envelope =
                    IntegrationEvent(
                        id = UUID.randomUUID(),
                        organizationId = endpoint.organizationId,
                        connectionId = endpoint.connectionId,
                        connectorId = endpoint.connectorId,
                        endpointId = endpoint.id,
                        providerEventId = event.providerEventId,
                        dedupeKey = dedupe,
                        eventType = event.eventType,
                        resourceType = event.resourceType,
                        resourceId = event.resourceId,
                        occurredAt = event.occurredAt,
                        receivedAt = now,
                        source = EventSource.WEBHOOK,
                        schemaVersion = 1,
                        correlationId = UUID.randomUUID().toString(),
                        causationId = null,
                        payload = payload,
                        metadata = JsonNodeFactory.instance.objectNode(),
                        status = EventStatus.RECEIVED,
                        processedAt = null,
                        error = null
                    )
                val stored = events.insert(envelope) ?: return@forEachIndexed
                jobs.enqueue(endpoint.organizationId, EventJobs.KIND, mapOf("eventId" to stored.toString()), "event:$stored", now, envelope.correlationId)
            }
            endpoints.delivered(endpoint.id, now)
        }
    }

    suspend fun events(
        connectionId: UUID?,
        status: String?,
        cursor: String?,
        limit: Int?
    ): EventPage {
        val user = access.reader()
        val parsedStatus =
            status?.takeIf { it.isNotBlank() }?.let { s ->
                EventStatus.entries.firstOrNull { it.name == s.uppercase() }
                    ?: throw ValidationException("Invalid filter", "status", "one of ${EventStatus.entries.joinToString()}")
            }
        val size = limit ?: 50
        if (size !in 1..200) throw ValidationException("Invalid limit", "limit", "1 to 200")
        val before = cursor?.let(::decodeCursor)
        val page = events.page(user.organizationId, connectionId, parsedStatus, before, size + 1)
        val content = page.take(size)
        val next = if (page.size > size) content.last().let { encodeCursor(it.receivedAt, it.id) } else null
        return EventPage(content.map(::view), next)
    }

    suspend fun event(id: UUID): EventView {
        val user = access.reader()
        return view(events.find(user.organizationId, id) ?: throw NotFoundException("Event $id not found"))
    }

    // processing again is safe: listeners are idempotent (the sync engine compares before it writes)
    suspend fun replay(id: UUID): EventView {
        val user = access.manager()
        val event = events.find(user.organizationId, id) ?: throw NotFoundException("Event $id not found")
        val now = clock.instant()
        tx.run {
            events.reopen(user.organizationId, id)
            jobs.enqueue(user.organizationId, EventJobs.KIND, mapOf("eventId" to id.toString()), "event:$id", now, event.correlationId)
                ?: throw ConflictException("Event $id is already waiting to be processed")
        }
        return view(events.find(user.organizationId, id)!!)
    }

    private fun view(endpoint: WebhookEndpoint) =
        WebhookEndpointView(
            endpoint.id,
            endpoint.connectionId,
            endpoint.connectorId,
            properties.publicBaseUrl?.let { it.trimEnd('/') + "/api/integrations/webhooks/${endpoint.connectorId}/${endpoint.id}" },
            endpoint.status,
            endpoint.secretSealed != null,
            endpoint.lastDeliveryAt,
            endpoint.createdAt
        )

    private fun view(e: IntegrationEvent) =
        EventView(
            e.id,
            e.organizationId,
            e.connectionId,
            e.connectorId,
            e.providerEventId,
            e.resourceType,
            e.resourceId,
            e.eventType,
            e.occurredAt,
            e.receivedAt,
            e.schemaVersion,
            e.correlationId,
            e.causationId,
            e.source,
            e.status,
            e.processedAt,
            e.error,
            e.payload,
            e.metadata
        )

    companion object {
        fun secretContext(
            organizationId: UUID,
            id: UUID
        ) = SealContext(organizationId, "integration_webhook_endpoints", id, "secret_sealed")

        fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        fun encodeCursor(
            at: Instant,
            id: UUID
        ): String = Base64.getUrlEncoder().withoutPadding().encodeToString("$at|$id".toByteArray())

        fun decodeCursor(cursor: String): Pair<Instant, UUID> =
            runCatching {
                val (at, id) = String(Base64.getUrlDecoder().decode(cursor)).split("|", limit = 2)
                Instant.parse(at) to UUID.fromString(id)
            }.getOrElse { throw ValidationException("Invalid cursor", "cursor", "not a cursor this api returned") }
    }
}
