package wasichai.integrations.connector.salesforce.pubsub

import io.grpc.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import wasichai.integrations.connector.salesforce.SalesforceConfig
import wasichai.integrations.connector.salesforce.SalesforceConnector
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.API
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.CHANNEL
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.CONTACT
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.ID1
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.ID2
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.ID3
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.ORG
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.SCHEMA_ID
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.SHARED
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.account
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.event
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.query
import wasichai.integrations.connector.salesforce.pubsub.CdcFixtures.salesforceBasics
import wasichai.integrations.connector.salesforce.pubsub.proto.ReplayPreset
import wasichai.integrations.sdk.Change
import wasichai.integrations.sdk.ChangePage
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.testing.KitProviderHttp
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import wasichai.integrations.sdk.testing.TestConnectorContext
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SalesforcePubSubConnectorTest {
    private val rest = StubProviderServer()
    private lateinit var pubsub: StubPubSub
    private lateinit var connector: SalesforcePubSubConnector
    private val now = Instant.parse("2026-11-05T10:00:00Z")

    // what the org holds right now: soql answers from it, by id or whole
    private val live = ConcurrentHashMap<String, String>()

    private fun connector(
        channels: Map<String, String> = mapOf("Account" to CHANNEL),
        batchSize: Int = 100,
        drain: Duration = Duration.ofMillis(300)
    ) = SalesforcePubSubConnector(
        SalesforceConnector(SalesforceConfig(apiVersion = "66.0")),
        SalesforcePubSubConfig(channels, batchSize = batchSize, drain = drain),
        pubsub.channel()
    )

    private fun context(
        http: KitProviderHttp = KitProviderHttp("token-1"),
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    ) = TestConnectorContext(
        http = http,
        metadata = mapOf("instance_url" to rest.baseUrl, "id" to "https://login.salesforce.com/id/$ORG/005000000000001AAA"),
        connectionId = UUID.randomUUID().toString(),
        clock = clock
    )

    @BeforeEach
    fun start() {
        rest.reset()
        rest.salesforceBasics()
        live.clear()
        rest.on(HttpMethod.GET, "$API/query/") { call, _ ->
            val soql = call.query.getValue("q")
            val ids = Regex("""'([A-Za-z0-9]{18})'""").findAll(soql).map { it.groupValues[1] }.toList()
            val answer = if ("Id IN" in soql) ids.mapNotNull { live[it] } else live.values.toList()
            StubResponse.json(query(answer))
        }
        pubsub = StubPubSub().apply { schema(SCHEMA_ID, CdcFixtures.schemaJson) }
        connector = connector()
    }

    @AfterEach
    fun stopPubSub() {
        connector.close()
        pubsub.close()
    }

    @AfterAll
    fun stop() = rest.close()

    private fun publish(
        changeType: String,
        ids: List<String>,
        at: Instant,
        entity: String = "Account",
        topic: String = CHANNEL
    ) = pubsub.publish(topic, SCHEMA_ID, event(changeType, ids, at, entity))

    private fun changes(
        checkpoint: String?,
        ctx: TestConnectorContext = context(),
        resource: String = "Account",
        on: SalesforcePubSubConnector = connector
    ): ChangePage = runBlocking { on.changes(ctx, resource, checkpoint) }

    private fun checkpoint(page: ChangePage) = CdcCheckpoint.parseOrNull(page.checkpoint!!)!!

    // the start of every feed: a full read, then the stream from the oldest retained event
    private fun started(ctx: TestConnectorContext = context()): String {
        val first = changes(null, ctx)
        assertFalse(first.hasMore)
        return first.checkpoint!!
    }

    @Test
    fun `the first read is a full soql read that remembers when it began, without touching pub sub`() {
        live[ID1] = account(ID1, "A")
        val page = changes(null)
        assertEquals(listOf(ID1), page.changes.map { it.id })
        val state = checkpoint(page)
        assertEquals(CdcCheckpoint(CHANNEL, null, now, null), state)
        assertTrue(pubsub.calls.isEmpty())
    }

    @Test
    fun `a paged full read keeps its polling checkpoint until the last page`() {
        val next = "$API/query/01gD0000002HU6KIAW-200"
        rest.onJson(HttpMethod.GET, "$API/query/", query(listOf(account(ID1, "A")), next))
        rest.onJson(HttpMethod.GET, next, query(listOf(account(ID2, "B"))))
        val first = changes(null)
        assertTrue(first.hasMore)
        assertNotNull(checkpoint(first).full)
        val later = context(clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC))
        val last = changes(first.checkpoint, later)
        assertEquals(listOf(ID2), last.changes.map { it.id })
        assertEquals(CdcCheckpoint(CHANNEL, null, now, null), checkpoint(last))
    }

    @Test
    fun `after the full read it subscribes from the oldest event and skips what that read covered`() {
        val start = started()
        publish("UPDATE", listOf(ID3), now.minus(Duration.ofHours(2)))
        publish("GAP_OVERFLOW", emptyList(), now.minusSeconds(30))
        val created = publish("CREATE", listOf(ID1), now.plusSeconds(10))
        live[ID1] = account(ID1, "Created")
        val page = changes(start)
        assertEquals(ReplayPreset.EARLIEST, pubsub.fetches().single().replayPreset)
        assertEquals(listOf(Change.Upserted::class), page.changes.map { it::class })
        assertEquals(ID1, page.changes.single().id)
        val state = checkpoint(page)
        assertArrayEquals(created, state.replay)
        assertNull(state.since, "an event after the full read began: nothing before it is left to skip")
        assertFalse(page.hasMore)
    }

    @Test
    fun `creates and updates are fetched by id in one query, deletes come from the header, last change per record wins`() {
        val start = started()
        live[ID1] = account(ID1, "Updated")
        live[ID2] = account(ID2, "Undeleted")
        publish("CREATE", listOf(ID1), now.plusSeconds(1))
        publish("UPDATE", listOf(ID1), now.plusSeconds(2))
        publish("CREATE", listOf(ID3), now.plusSeconds(3))
        publish("DELETE", listOf(ID3), now.plusSeconds(4))
        publish("UNDELETE", listOf(ID2), now.plusSeconds(5))
        val page = changes(start)
        assertEquals(listOf(ID1, ID3, ID2), page.changes.map { it.id })
        val upserted = page.changes.filterIsInstance<Change.Upserted>()
        assertEquals(
            "Updated",
            upserted
                .first()
                .record.native!!
                .path("Name")
                .asString()
        )
        assertEquals(Change.Deleted(ID3, now.plusSeconds(4)), page.changes[1])
        val soql =
            rest.requests
                .filter { it.path == "$API/query/" }
                .map { it.query.getValue("q") }
                .last()
        assertTrue(soql.endsWith("WHERE Id IN ('$ID1', '$ID2')"), soql)
    }

    @Test
    fun `the stored replay id is sent back as it was received, and the feed resumes after it`() {
        val start = started()
        val first = publish("CREATE", listOf(ID1), now.plusSeconds(1))
        live[ID1] = account(ID1, "A")
        val one = changes(start)
        assertArrayEquals(first, checkpoint(one).replay)
        assertEquals(Base64.getEncoder().encodeToString(first), Regex(""""replay":"([^"]+)"""").find(one.checkpoint!!)!!.groupValues[1])
        val second = publish("UPDATE", listOf(ID2), now.plusSeconds(2))
        live[ID2] = account(ID2, "B")
        val two = changes(one.checkpoint)
        val fetch = pubsub.fetches().last()
        assertEquals(ReplayPreset.CUSTOM, fetch.replayPreset)
        assertArrayEquals(first, fetch.replayId.toByteArray())
        assertEquals(listOf(ID2), two.changes.map { it.id })
        assertArrayEquals(second, checkpoint(two).replay)
        // nothing new: the replay id stays
        val three = changes(two.checkpoint)
        assertTrue(three.changes.isEmpty())
        assertArrayEquals(second, checkpoint(three).replay)
    }

    @Test
    fun `every call carries the token, the instance url and the org id`() {
        changes(started())
        val headers = pubsub.calls.first { it.method == "Subscribe" }.headers
        assertEquals("token-1", headers["accesstoken"])
        assertEquals(rest.baseUrl, headers["instanceurl"])
        assertEquals(ORG, headers["tenantid"])
    }

    @Test
    fun `the schema is fetched once per schema id, with the same headers`() {
        val start = started()
        publish("CREATE", listOf(ID1), now.plusSeconds(1))
        publish("UPDATE", listOf(ID1), now.plusSeconds(2))
        val one = changes(start)
        publish("UPDATE", listOf(ID1), now.plusSeconds(3))
        changes(one.checkpoint)
        val schemaCalls = pubsub.calls.filter { it.method == "GetSchema" }
        assertEquals(1, schemaCalls.size)
        assertEquals("token-1", schemaCalls.single().headers["accesstoken"])
    }

    @Test
    fun `a replay id salesforce no longer has is refused as a checkpoint, which the sync engine reads as start over`() {
        val start = started()
        publish("CREATE", listOf(ID1), now.plusSeconds(1))
        live[ID1] = account(ID1, "A")
        val one = changes(start)
        pubsub.expire(CHANNEL, 1)
        val refused = assertThrows<ConnectorException.Permanent> { changes(one.checkpoint) }
        assertEquals(PubSubClient.REPLAY_REFUSED, refused.details["reason"])
        assertEquals(StubPubSub.REPLAY_CORRUPTED, refused.details["errorCode"])
        // what the engine then does: the side from the start, a full read, then the stream from the oldest event
        val restarted = changes(null, context(clock = Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC)))
        assertEquals(listOf(ID1), restarted.changes.map { it.id })
        assertNull(checkpoint(restarted).replay)
    }

    @Test
    fun `a gap event asks for a full read and reconciliation instead of guessing`() {
        val start = started()
        publish("CREATE", listOf(ID1), now.plusSeconds(1))
        publish("GAP_UPDATE", listOf(ID2), now.plusSeconds(2))
        val refused = assertThrows<ConnectorException.Permanent> { changes(start) }
        assertEquals("reconcile", refused.details["reason"])
        assertEquals("GAP_UPDATE", refused.details["changeType"])
    }

    @Test
    fun `an overflow without record ids asks for a reconciliation too`() {
        val start = started()
        publish("GAP_OVERFLOW", emptyList(), now.plusSeconds(1))
        val refused = assertThrows<ConnectorException.Permanent> { changes(start) }
        assertEquals("GAP_OVERFLOW", refused.details["changeType"])
    }

    @Test
    fun `on a shared channel only this object's events count, and the replay id still moves on`() {
        val shared = connector(mapOf("Account" to SHARED, "Contact" to SHARED))
        val start = runBlocking { shared.changes(context(), "Account", null) }.checkpoint
        val contact = publish("UPDATE", listOf(CONTACT), now.plusSeconds(1), entity = "Contact", topic = SHARED)
        val page = changes(start, on = shared)
        assertTrue(page.changes.isEmpty())
        assertArrayEquals(contact, checkpoint(page).replay)
        shared.close()
    }

    @Test
    fun `a full batch says there is more`() {
        val small = connector(batchSize = 2)
        val start = runBlocking { small.changes(context(), "Account", null) }.checkpoint
        live[ID1] = account(ID1, "A")
        live[ID2] = account(ID2, "B")
        live[ID3] = account(ID3, "C")
        publish("CREATE", listOf(ID1), now.plusSeconds(1))
        publish("CREATE", listOf(ID2), now.plusSeconds(2))
        publish("CREATE", listOf(ID3), now.plusSeconds(3))
        val first = changes(start, on = small)
        assertTrue(first.hasMore)
        assertEquals(2, pubsub.fetches().last().numRequested)
        val second = changes(first.checkpoint, on = small)
        assertEquals(listOf(ID3), second.changes.map { it.id })
        assertFalse(second.hasMore)
        small.close()
    }

    @Test
    fun `a refused token is renewed once through the platform, then the stream goes on`() {
        val start = started()
        publish("CREATE", listOf(ID1), now.plusSeconds(1))
        live[ID1] = account(ID1, "A")
        pubsub.refusedTokens += "token-1"
        val page = changes(start, context(http = KitProviderHttp("token-1", refreshedToken = "token-2")))
        assertEquals(listOf(ID1), page.changes.map { it.id })
        assertEquals(listOf("token-1", "token-2"), pubsub.calls.filter { it.method == "Subscribe" }.map { it.headers["accesstoken"] })
        pubsub.refusedTokens += "token-2"
        assertThrows<ConnectorException.AuthExpired> { changes(start, context(http = KitProviderHttp("token-1", refreshedToken = "token-2"))) }
    }

    @Test
    fun `other refusals fail the run instead of restarting the feed`() {
        val start = started()
        pubsub.failSubscribe = Status.PERMISSION_DENIED.withDescription("no access to the channel") to "sfdc.platform.eventbus.grpc.service.auth.error"
        assertThrows<ConnectorException.PermissionDenied> { changes(start) }
        pubsub.failSubscribe = Status.INVALID_ARGUMENT.withDescription("unknown topic") to null
        assertThrows<ConnectorException.PermissionDenied> { changes(start) }
        pubsub.failSubscribe = Status.UNAVAILABLE to null
        assertThrows<ConnectorException.Transient> { changes(start) }
        pubsub.failSubscribe = Status.RESOURCE_EXHAUSTED to "sfdc.platform.eventbus.grpc.subscription.limit.exceeded"
        assertThrows<ConnectorException.RateLimited> { changes(start) }
    }

    @Test
    fun `objects without a channel keep polling, and switching feeds starts the side over`() {
        live[ID1] = account(ID1, "A")
        val polled = changes(null, on = connector(mapOf("Contact" to "/data/ContactChangeEvent")))
        assertNull(CdcCheckpoint.parseOrNull(polled.checkpoint!!), "a polling checkpoint")
        assertTrue(pubsub.calls.isEmpty())
        // the same object, now with a channel: its polling checkpoint means nothing to the stream
        val switched = assertThrows<ConnectorException.Permanent> { changes(polled.checkpoint) }
        assertEquals("feed_changed", switched.details["reason"])
        // and back
        val back =
            assertThrows<ConnectorException.Permanent> {
                changes(started(), on = connector(mapOf("Contact" to "/data/ContactChangeEvent")))
            }
        assertEquals("feed_changed", back.details["reason"])
        // a replay id of another channel is never sent
        val moved = assertThrows<ConnectorException.Permanent> { changes(started(), on = connector(mapOf("Account" to SHARED))) }
        assertEquals("channel_changed", moved.details["reason"])
    }

    @Test
    fun `waiting for events never holds the calling thread`() {
        val start = started()
        runBlocking {
            // runBlocking is one thread: the ticker moves only if the pull suspends instead of blocking
            var ticks = 0
            val ticker =
                launch {
                    while (isActive) {
                        delay(20)
                        ticks++
                    }
                }
            val page = connector.changes(context(), "Account", start)
            ticker.cancel()
            assertTrue(page.changes.isEmpty())
            assertTrue(ticks >= 5, "ticks: $ticks")
        }
    }
}
