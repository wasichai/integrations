package wasichai.integrations.connector.salesforce.pubsub

import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import org.slf4j.LoggerFactory
import wasichai.integrations.connector.salesforce.SalesforceConfig
import wasichai.integrations.connector.salesforce.SalesforceConnector
import wasichai.integrations.sdk.Authenticator
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.ChangeFeed
import wasichai.integrations.sdk.ChangePage
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.ConnectorManifest
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceUpserter
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.SdkVersion
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

// the salesforce connector with change data capture for the objects that have a channel (ADR-0024). everything but
// the change feed is the polling connector's; so is the change feed of an object without a channel.
// each changes() call is one bounded pull: subscribe from the stored replay id, take what arrives, close.
class SalesforcePubSubConnector internal constructor(
    private val base: SalesforceConnector,
    private val config: SalesforcePubSubConfig,
    private val channel: Channel
) : Connector,
    Authenticator by base,
    ResourceDiscovery by base,
    ResourceReader by base,
    ChangeFeed,
    ResourceWriter by base,
    ResourceUpserter by base,
    AutoCloseable {
    constructor(
        pubSub: SalesforcePubSubConfig,
        salesforce: SalesforceConfig = SalesforceConfig()
    ) : this(SalesforceConnector(salesforce), pubSub, OkHttpChannelBuilder.forTarget(pubSub.endpoint).useTransportSecurity().build())

    private val log = LoggerFactory.getLogger(javaClass)
    private val client = PubSubClient(channel)
    private val tenants = ConcurrentHashMap<String, String>()

    // same id as the polling connector: an app registers one of the two, and its connections keep working
    override val manifest: ConnectorManifest =
        base.manifest.copy(
            // ProviderHttp.credentialHeaders, which the grpc stream needs, is sdk 1.1
            sdkVersion = SdkVersion.CURRENT,
            description =
                "Salesforce REST API: sObject describe, SOQL reads, CRUD, upsert by external id. Incremental changes by Change " +
                    "Data Capture over the Pub/Sub API for ${config.channels.keys.sorted().joinToString(", ")}, by polling for other objects.",
            requiredPermissions = base.manifest.requiredPermissions + "Change Data Capture on the configured objects, with their channels"
        )

    override suspend fun changes(
        context: ConnectorContext,
        resource: String,
        checkpoint: String?,
        pageSize: Int
    ): ChangePage {
        val topic = config.channels[resource]
        val state = checkpoint?.let { CdcCheckpoint.parseOrNull(it) }
        if (topic == null) {
            // a checkpoint this object's polling feed cannot read: the engine starts the side over
            if (state != null) throw restart("$resource reads by polling now", "feed_changed")
            return base.changes(context, resource, checkpoint, pageSize)
        }
        if (checkpoint != null && state == null) throw restart("$resource reads by change data capture now", "feed_changed")
        if (state != null && state.channel != topic) throw restart("the replay id of $resource belongs to another channel", "channel_changed")
        if (state == null || state.full != null) return fullRead(context, resource, topic, state, pageSize)
        return stream(context, resource, topic, state, minOf(pageSize, config.batchSize))
    }

    // the start (and every restart) is a full read by the polling feed; the stream then picks up from the oldest
    // retained event, skipping what this read already covered. a stream subscribed from LATEST instead would lose
    // what happens between two pulls until its first event names a replay id
    private suspend fun fullRead(
        context: ConnectorContext,
        resource: String,
        topic: String,
        state: CdcCheckpoint?,
        pageSize: Int
    ): ChangePage {
        val since = state?.since ?: context.clock.instant()
        val page = base.changes(context, resource, state?.full, pageSize)
        val next = CdcCheckpoint(topic, null, since, page.checkpoint.takeIf { page.hasMore })
        return ChangePage(page.changes, next.format(), page.hasMore)
    }

    private suspend fun stream(
        context: ConnectorContext,
        resource: String,
        topic: String,
        state: CdcCheckpoint,
        wanted: Int
    ): ChangePage {
        var auth = auth(context, refresh = false)
        val pulled =
            try {
                client.pull(auth, topic, state.replay, wanted, config.drain)
            } catch (_: ConnectorException.AuthExpired) {
                // the token was refused: one renewal, then the connection needs its user
                auth = auth(context, refresh = true)
                client.pull(auth, topic, state.replay, wanted, config.drain)
            }
        var since = state.since
        // last change per record wins: a create then a delete is a delete
        val ops = LinkedHashMap<String, Instant?>()
        var covered = 0
        var others = 0
        for (event in pulled.events) {
            val header = ChangeEvents.header(client.schema(auth, event.event.schemaId), event.event.payload.toByteArray())
            if (header.entityName != resource) {
                // another object on a shared channel (/data/ChangeEvents): its own feed reads it
                others++
                continue
            }
            if (since != null && header.commitTimestamp.isBefore(if (header.gap) since else since.minus(CLOCK_SKEW))) {
                covered++
                continue
            }
            if (header.gap || header.changeType !in KNOWN || header.recordIds.isEmpty() || header.recordIds.any { !RECORD_ID.matches(it) }) {
                // no reliable list of what changed: read the object whole and let reconciliation find the rest
                throw restart("change data capture on $topic sent ${header.changeType} for $resource", "reconcile", header.changeType)
            }
            header.recordIds.forEach { id ->
                ops.remove(id)
                ops[id] = if (header.changeType == "DELETE") header.commitTimestamp else null
            }
            if (since != null && !header.commitTimestamp.isBefore(since)) since = null
        }
        val fetched = base.readMany(context, resource, ops.filterValues { it == null }.keys).associateBy { it.id }
        val changes = mutableListOf<Change>()
        var unseen = 0
        ops.forEach { (id, deletedAt) ->
            if (deletedAt != null) {
                changes += Change.Deleted(id, deletedAt)
            } else {
                fetched[id]?.let { changes += Change.Upserted(it) } ?: unseen++
            }
        }
        if (covered + others + unseen > 0) {
            log.info(
                "integrations: salesforce cdc {} on {}: {} covered by the full read, {} of other objects, {} gone or not visible since",
                resource,
                topic,
                covered,
                others,
                unseen
            )
        }
        val next = CdcCheckpoint(topic, pulled.replayId?.toByteArray() ?: state.replay, since, null)
        return ChangePage(changes, next.format(), pulled.events.size >= wanted)
    }

    private suspend fun auth(
        context: ConnectorContext,
        refresh: Boolean
    ): PubSubAuth {
        val token =
            context.http.credentialHeaders(refresh)["Authorization"]?.removePrefix("Bearer ")
                ?: throw ConnectorException.AuthExpired("the connection lends no bearer token")
        val instance =
            context.metadata[SalesforceConnector.INSTANCE_URL]?.trimEnd('/')
                ?: throw ConnectorException.Permanent("connection has no instance_url; reconnect the account")
        return PubSubAuth(token, instance, tenant(context))
    }

    // the org id: from the identity url kept at connect time (https://login.salesforce.com/id/<org>/<user>), else userinfo
    private suspend fun tenant(context: ConnectorContext): String {
        tenants[context.connectionId]?.let { return it }
        val org =
            context.metadata["id"]?.let { IDENTITY.find(it)?.groupValues?.get(1) }
                ?: base.accountInfo(context).providerAccountId
        tenants[context.connectionId] = org
        return org
    }

    // Permanent on a checkpoint is what the sync engine turns into a cursor reset, a full read and the reconcile checks
    private fun restart(
        why: String,
        reason: String,
        changeType: String? = null
    ) = ConnectorException.Permanent(why, mapOf("reason" to reason) + listOfNotNull(changeType?.let { "changeType" to it }))

    override fun close() {
        (channel as? ManagedChannel)?.shutdown()
    }

    companion object {
        // events committed shortly before the full read began are read again: the two clocks are not the same
        val CLOCK_SKEW: Duration = Duration.ofMinutes(5)

        private val KNOWN = setOf("CREATE", "UPDATE", "DELETE", "UNDELETE")
        private val RECORD_ID = Regex("""^[A-Za-z0-9]{15}(?:[A-Za-z0-9]{3})?$""")
        private val IDENTITY = Regex("""/id/([A-Za-z0-9]{15,18})/""")
    }
}
