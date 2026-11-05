package wasichai.integrations.workflows

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.JsonNodeFactory
import wasichai.core.common.ConflictException
import wasichai.core.common.NotFoundException
import wasichai.core.common.ValidationException
import wasichai.core.platform.ChangeOrigin
import wasichai.integrations.IntegrationsAccess
import wasichai.integrations.autoconfigure.IntegrationsProperties
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

// what people do with published workflows: run by hand, pause, read the history, decide approvals, cancel; and the
// workflow's own webhook url. reading is any member; everything else MANAGE_ORGANIZATION
class WorkflowRunService(
    private val access: IntegrationsAccess,
    private val workflows: WorkflowRepository,
    private val executions: ExecutionRepository,
    private val engine: WorkflowEngine,
    private val json: JsonMapper,
    private val properties: IntegrationsProperties,
    private val clock: Clock
) {
    private val random = SecureRandom()

    suspend fun run(
        id: UUID,
        request: RunRequest?
    ): ExecutionView {
        val user = access.manager()
        val trigger = live(user.organizationId, id)
        val payload = request?.payload?.takeUnless { it.isNull } ?: json.createObjectNode()
        if (!payload.isObject) throw ValidationException("Invalid payload", "payload", "a JSON object")
        val context =
            JsonNodeFactory.instance
                .objectNode()
                .put("type", TriggerType.MANUAL.json)
                .set("payload", payload)
        val correlation = ChangeOrigin.correlationId() ?: ChangeOrigin.newCorrelationId()
        val started =
            engine.start(user.organizationId, id, trigger.versionNumber, TriggerType.MANUAL, context, null, correlation, user.userId)!!
        return executions.find(user.organizationId, started)!!.view()
    }

    suspend fun pause(
        id: UUID,
        paused: Boolean
    ): WorkflowView {
        val user = access.manager()
        if (!workflows.setPaused(user.organizationId, id, paused, clock.instant())) throw NotFoundException("Workflow $id not found")
        return WorkflowService.view(workflows.find(user.organizationId, id)!!)
    }

    suspend fun executions(
        id: UUID,
        status: String?,
        cursor: String?,
        limit: Int?
    ): ExecutionPage {
        val user = access.reader()
        workflows.find(user.organizationId, id) ?: throw NotFoundException("Workflow $id not found")
        val parsed =
            status?.takeIf { it.isNotBlank() }?.let { s ->
                ExecutionStatus.entries.firstOrNull { it.name == s.uppercase() }
                    ?: throw ValidationException("Invalid filter", "status", "one of ${ExecutionStatus.entries.joinToString()}")
            }
        val size = limit ?: 50
        if (size !in 1..200) throw ValidationException("Invalid limit", "limit", "1 to 200")
        val page = executions.page(user.organizationId, id, parsed, cursor?.let(::decodeCursor), size + 1)
        val content = page.take(size)
        val next = if (page.size > size) content.last().let { encodeCursor(it.createdAt, it.id) } else null
        return ExecutionPage(content.map { it.view() }, next)
    }

    suspend fun execution(id: UUID): ExecutionDetailView {
        val user = access.reader()
        val execution = executions.find(user.organizationId, id) ?: throw NotFoundException("Execution $id not found")
        return ExecutionDetailView(execution.view(), executions.steps(id).map { it.view() })
    }

    suspend fun cancel(id: UUID): ExecutionDetailView {
        val user = access.manager()
        engine.cancel(user.organizationId, id, user.userId)
        return execution(id)
    }

    suspend fun decide(
        id: UUID,
        stepId: String,
        approved: Boolean,
        request: DecisionRequest?
    ): ExecutionDetailView {
        val user = access.manager()
        val comment = request?.comment?.trim()?.takeIf { it.isNotEmpty() }
        if (comment != null && comment.length > 2000) throw ValidationException("Invalid comment", "comment", "at most 2000 characters")
        engine.decide(user.organizationId, id, stepId, approved, user.userId, comment)
        return execution(id)
    }

    // a new secret for the workflow's webhook url; the one before stops working. shown here, once
    suspend fun issueWebhook(id: UUID): WebhookUrlView {
        val user = access.manager()
        val secret = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        if (!workflows.setWebhookToken(user.organizationId, id, sha256(secret), clock.instant())) throw NotFoundException("Workflow $id not found")
        val path = "$WEBHOOK_BASE/$id/$secret"
        return WebhookUrlView(properties.publicBaseUrl?.let { it.trimEnd('/') + path }, path)
    }

    // the public route: the secret in the path is the authentication. an unknown workflow, a wrong secret and a
    // workflow without a webhook trigger answer alike (404), never telling which
    suspend fun receive(
        id: String,
        secret: String,
        idempotencyKey: String?,
        body: ByteArray
    ): Pair<Int, StartedView?> {
        if (body.size > properties.webhooks.maxBodyBytes) return 413 to null
        val definitionId = runCatching { UUID.fromString(id) }.getOrNull() ?: return 404 to null
        val trigger = executions.trigger(null, definitionId) ?: return 404 to null
        val hash = trigger.webhookTokenHash ?: return 404 to null
        if (!MessageDigest.isEqual(hash.toByteArray(), sha256(secret).toByteArray()) || trigger.type != TriggerType.WEBHOOK) return 404 to null
        if (trigger.paused) return 409 to null
        val payload: JsonNode =
            if (body.isEmpty()) json.createObjectNode() else runCatching { json.readTree(body) }.getOrNull() ?: return 400 to null
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
        if (key != null && key.length > 200) return 400 to null
        val context =
            JsonNodeFactory.instance
                .objectNode()
                .put("type", TriggerType.WEBHOOK.json)
                .set("payload", payload)
        val triggerKey = key?.let { "webhook:$it" }
        val started =
            engine.start(
                trigger.organizationId,
                definitionId,
                trigger.versionNumber,
                TriggerType.WEBHOOK,
                context,
                triggerKey,
                ChangeOrigin.newCorrelationId(),
                null
            )
                ?: executions.findByTriggerKey(definitionId, triggerKey!!)
        return 202 to started?.let { StartedView(it) }
    }

    // the published, unpaused trigger: a draft never runs
    private suspend fun live(
        organizationId: UUID,
        id: UUID
    ): LiveTrigger {
        workflows.find(organizationId, id) ?: throw NotFoundException("Workflow $id not found")
        val trigger = executions.trigger(organizationId, id) ?: throw ConflictException("Workflow $id is not published")
        if (trigger.paused) throw ConflictException("Workflow $id is paused")
        return trigger
    }

    companion object {
        const val WEBHOOK_BASE = "/api/integrations/webhooks/workflows"

        fun sha256(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

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
