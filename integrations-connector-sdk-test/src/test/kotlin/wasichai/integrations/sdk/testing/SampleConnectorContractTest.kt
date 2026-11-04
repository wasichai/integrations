package wasichai.integrations.sdk.testing

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import wasichai.integrations.sdk.AccountInfo
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.ConnectorManifest
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.HttpErrors
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.ProviderInfo
import wasichai.integrations.sdk.ProviderRecord
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.ReadRequest
import wasichai.integrations.sdk.RecordPage
import wasichai.integrations.sdk.ResourceDeclaration
import wasichai.integrations.sdk.ResourceDescriptor
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceSchema
import wasichai.integrations.sdk.SdkVersion

// a tiny connector over the stub proves the kit itself: contract checks, recorded requests, error mapping
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SampleConnectorContractTest : ConnectorContractTest() {
    private val stub = StubProviderServer()

    private class Sample(
        private val base: String
    ) : Connector,
        ResourceDiscovery,
        ResourceReader {
        override val manifest =
            ConnectorManifest(
                id = "sample",
                provider = ProviderInfo("Sample"),
                version = "0.1.0",
                sdkVersion = SdkVersion.CURRENT,
                description = "kit self-test",
                authMethods = listOf(AuthMethod.ApiKey()),
                capabilities = mapOf(Capability.DISCOVERY to Maturity.TESTED, Capability.READ to Maturity.TESTED),
                resources = listOf(ResourceDeclaration("Note", "Note"))
            )

        override suspend fun listResources(context: ConnectorContext) = listOf(ResourceDescriptor("Note", "Note"))

        override suspend fun describe(
            context: ConnectorContext,
            resource: String
        ) = ResourceSchema(resource, resource, listOf(FieldDescriptor("title", "Title", FieldType.TEXT)))

        override suspend fun read(
            context: ConnectorContext,
            resource: String,
            id: String
        ): ProviderRecord? {
            val response = context.http.exchange(ProviderRequest(HttpMethod.GET, "$base/notes/$id"))
            if (response.status == 404) return null
            if (!response.successful) throw HttpErrors.classify(response)
            val json = response.json()
            return ProviderRecord(id, json.get("rev").asString(), null, mapOf("title" to FieldValue.Text(json.get("title").asString())))
        }

        override suspend fun list(
            context: ConnectorContext,
            request: ReadRequest
        ) = RecordPage(emptyList(), null)

        @Suppress("unused")
        fun account() = AccountInfo("x")
    }

    override fun connector(): Connector = Sample(stub.baseUrl)

    override fun discoveryContext() = TestConnectorContext()

    @AfterAll
    fun stop() = stub.close()

    @Test
    fun `reads through the stub and records the request with the bearer token`() {
        stub.reset()
        stub.onJson(HttpMethod.GET, "/notes/(\\w+)", """{"rev":"3","title":"Hello"}""")
        val record = runBlocking { (connector() as ResourceReader).read(TestConnectorContext(), "Note", "n1") }
        assertEquals(FieldValue.Text("Hello"), record!!.fields["title"])
        assertEquals("3", record.version)
        assertEquals("Bearer test-token", stub.requests.single().header("Authorization"))
    }

    @Test
    fun `provider errors surface as the taxonomy`() {
        stub.reset()
        stub.onJson(HttpMethod.GET, "/notes/.*", """{"error":"slow down"}""", status = 429)
        val error =
            assertThrows<ConnectorException> {
                runBlocking { (connector() as ResourceReader).read(TestConnectorContext(), "Note", "n1") }
            }
        assertInstanceOf(ConnectorException.RateLimited::class.java, error)
    }

    @Test
    fun `query parameters are encoded`() {
        assertEquals("http://h/x?q=a%20b&n=1", KitProviderHttp.withQuery("http://h/x", linkedMapOf("q" to "a b", "n" to "1")))
    }
}
