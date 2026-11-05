package wasichai.integrations.sdk.testing.guide

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.testing.ConnectorContractTest
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import wasichai.integrations.sdk.testing.TestConnectorContext

// the contract kit checks the manifest; these tests check the provider behaviour against a stub built from
// the (made-up) provider's documented payloads. no network, no account.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TasksConnectorTest : ConnectorContractTest() {
    private val stub = StubProviderServer()
    private val tasks by lazy { TasksConnector(stub.baseUrl) }
    private val context = TestConnectorContext()

    override fun connector(): Connector = tasks

    @BeforeEach
    fun stubs() {
        stub.reset()
        stub.onJson(HttpMethod.GET, "/tasks/t1", task("t1", "7", "Write the guide", done = false))
    }

    @AfterAll
    fun stop() = stub.close()

    @Test
    fun `reads a task as typed fields with its version`() {
        val record = runBlocking { tasks.read(context, "task", "t1") }!!
        assertEquals("7", record.version)
        assertEquals(FieldValue.Text("Write the guide"), record.fields["title"])
        assertEquals(FieldValue.Bool(false), record.fields["done"])
        assertEquals("Bearer test-token", stub.requests.single().header("Authorization"))
    }

    @Test
    fun `a missing task reads as null`() {
        stub.onJson(HttpMethod.GET, "/tasks/gone", """{"error":"not_found"}""", status = 404)
        assertNull(runBlocking { tasks.read(context, "task", "gone") })
    }

    @Test
    fun `lists page by page with the provider cursor`() {
        stub.on(HttpMethod.GET, "/tasks") { call, _ ->
            if (call.query["cursor"] == null) {
                StubResponse.json("""{"items":[${task("t1", "7", "One", false)}],"next":"c2"}""")
            } else {
                StubResponse.json("""{"items":[${task("t2", "1", "Two", true)}],"next":null}""")
            }
        }
        val first = runBlocking { tasks.list(context, ReadRequest("task", pageSize = 1)) }
        val second = runBlocking { tasks.list(context, ReadRequest("task", first.nextCursor, 1)) }
        assertEquals(listOf("t1"), first.records.map { it.id })
        assertEquals(listOf("t2"), second.records.map { it.id })
        assertNull(second.nextCursor)
        assertEquals("1", stub.requests.first().query["limit"])
    }

    @Test
    fun `creates with the mapped fields as json`() {
        stub.on(HttpMethod.POST, "/tasks") { _, _ -> StubResponse.json(task("t9", "1", "New", false), status = 201) }
        val result = runBlocking { tasks.create(context, "task", mapOf("title" to FieldValue.Text("New"), "done" to FieldValue.Bool(false))) }
        assertEquals("t9", result.id)
        assertTrue(result.created)
        assertEquals("""{"title":"New","done":false}""", stub.requests.single().bodyText())
    }

    @Test
    fun `an update on a moved record is a Conflict`() {
        stub.onJson(HttpMethod.PATCH, "/tasks/t1", """{"error":"rev_mismatch"}""", status = 412)
        assertThrows<ConnectorException.Conflict> {
            runBlocking { tasks.update(context, "task", "t1", mapOf("done" to FieldValue.Bool(true)), "6") }
        }
        assertEquals("6", stub.requests.single().header("If-Match"))
    }

    @Test
    fun `a value the provider cannot store is refused before any call`() {
        assertThrows<ConnectorException.Permanent> {
            runBlocking { tasks.create(context, "task", mapOf("title" to FieldValue.Choice("x"))) }
        }
        assertTrue(stub.requests.isEmpty())
    }

    private fun task(
        id: String,
        rev: String,
        title: String,
        done: Boolean
    ) = """{"id":"$id","rev":"$rev","title":"$title","done":$done,"updated_at":"2026-11-04T10:00:00Z"}"""
}
