package wasichai.integrations.connector.openapi

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.integrations.connector.openapi.OpenApiFixtures.document
import wasichai.integrations.connector.openapi.OpenApiFixtures.petstore
import wasichai.integrations.connector.openapi.OpenApiFixtures.readOnly
import wasichai.integrations.connector.openapi.OpenApiFixtures.resource
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.ChangeFeed
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.ManifestValidator
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.PaginationStrategy
import wasichai.integrations.sdk.ProviderHttp
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.ProviderResponse
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceWriter
import wasichai.integrations.sdk.SyncDirectionSupport
import wasichai.integrations.sdk.testing.TestConnectorContext

// the config against its document: manifest, auth, derived schemas and refused mappings. no provider is called
class OpenApiConfigTest {
    private val base = "https://petstore.example/v1"
    private val full = petstore(base)

    private fun misconfigured(config: OpenApiConfig): String = assertThrows<IllegalArgumentException> { OpenApiConnector.of(config) }.message!!

    @Test
    fun `the manifest declares what the mapping provides, at the configured maturity`() {
        val connector = OpenApiConnector.of(full)
        val manifest = connector.manifest
        assertEquals("petstore", manifest.id)
        assertEquals("Petstore", manifest.provider.name)
        assertEquals("https://petstore.example/docs", manifest.provider.documentation)
        assertEquals(
            mapOf(
                Capability.DISCOVERY to Maturity.TESTED,
                Capability.READ to Maturity.TESTED,
                Capability.CHANGE_FEED to Maturity.TESTED,
                Capability.WRITE to Maturity.TESTED
            ),
            manifest.capabilities
        )
        assertEquals(listOf("pet", "owner", "toy", "visit", "species"), manifest.resources.map { it.name })
        assertTrue(manifest.resources.none { it.dynamic })
        assertEquals(setOf(PaginationStrategy.OFFSET, PaginationStrategy.CURSOR, PaginationStrategy.NEXT_URL), manifest.pagination)
        assertEquals(setOf(SyncDirectionSupport.SOURCE, SyncDirectionSupport.TARGET), manifest.syncDirections)
        assertNull(manifest.webhooks)
        assertTrue(manifest.actions.isEmpty())
        assertEquals(5.0, manifest.rateLimit!!.requestsPerSecond)
        assertTrue("pet (read, incremental read, create, update, delete)" in manifest.description)
        assertEquals(emptyList<String>(), ManifestValidator.problems(connector))
    }

    @Test
    fun `a mapping without writes or incremental reads implements neither, and defaults to IMPLEMENTED`() {
        val connector = OpenApiConnector.of(readOnly(base))
        assertEquals(mapOf(Capability.DISCOVERY to Maturity.IMPLEMENTED, Capability.READ to Maturity.IMPLEMENTED), connector.manifest.capabilities)
        assertInstanceOf(ResourceDiscovery::class.java, connector)
        assertInstanceOf(ResourceReader::class.java, connector)
        assertFalse(connector is ChangeFeed)
        assertFalse(connector is ResourceWriter)
        assertEquals(setOf(SyncDirectionSupport.SOURCE), connector.manifest.syncDirections)
        assertEquals(emptyList<String>(), ManifestValidator.problems(connector))
    }

    @Test
    fun `incremental reads alone and writes alone each give a valid manifest`() {
        val pet = resource(full, "pet")
        val feedOnly = OpenApiConnector.of(full.copy(resources = listOf(pet.copy(create = null, update = null, delete = null))))
        assertTrue(feedOnly is ChangeFeed && feedOnly !is ResourceWriter)
        assertEquals(emptyList<String>(), ManifestValidator.problems(feedOnly))
        val writeOnly = OpenApiConnector.of(full.copy(resources = listOf(pet.copy(updatedSinceParam = null, create = null, delete = null))))
        assertTrue(writeOnly is ResourceWriter && writeOnly !is ChangeFeed)
        assertEquals(emptyList<String>(), ManifestValidator.problems(writeOnly))
    }

    @Test
    fun `security schemes become the platform's auth methods`() {
        assertEquals(listOf(AuthMethod.ApiKey("X-API-Key", null)), OpenApiConnector.of(full).manifest.authMethods)
        assertEquals(
            listOf(AuthMethod.ApiKey("Authorization", "Bearer ")),
            OpenApiConnector.of(full.copy(securityScheme = "bearer")).manifest.authMethods
        )
        val oauth =
            OpenApiConnector
                .of(full.copy(securityScheme = "oauth"))
                .manifest.authMethods
                .single() as AuthMethod.OAuth2AuthorizationCode
        assertEquals("https://petstore.example/oauth/authorize", oauth.authorizeUrl)
        assertEquals("https://petstore.example/oauth/token", oauth.tokenUrl)
        assertEquals(listOf("read:pets", "write:pets"), oauth.defaultScopes)
        assertTrue(oauth.pkce)
        val narrowed =
            OpenApiConnector
                .of(full.copy(securityScheme = "oauth", scopes = listOf("read:pets"), pkce = false))
                .manifest.authMethods
                .single()
                as AuthMethod.OAuth2AuthorizationCode
        assertEquals(listOf("read:pets"), narrowed.defaultScopes)
        assertFalse(narrowed.pkce)
        assertTrue("only in a header" in misconfigured(full.copy(securityScheme = "queryKey")))
        assertTrue("http basic is not supported" in misconfigured(full.copy(securityScheme = "basic")))
        assertTrue("'nope' is not in components.securitySchemes" in misconfigured(full.copy(securityScheme = "nope")))
    }

    @Test
    fun `the record schema comes from the get response, allOf and refs resolved`() {
        val connector = OpenApiConnector.of(full) as ResourceDiscovery
        val schema = runBlocking { connector.describe(TestConnectorContext(), "pet") }
        assertEquals("Pet", schema.label)
        assertEquals(listOf("name", "status", "birthday", "vaccinated", "weight", "tags", "owner", "id", "updated_at"), schema.fields.map { it.name })
        val name = schema.field("name")!!
        assertEquals(FieldType.TEXT, name.type)
        assertEquals("Name", name.label)
        assertTrue(name.required, "required by the create body")
        assertEquals(FieldType.CHOICE, schema.field("status")!!.type)
        assertEquals(listOf("available", "pending", "sold"), schema.field("status")!!.options)
        assertEquals(FieldType.DATE, schema.field("birthday")!!.type)
        assertEquals(FieldType.BOOLEAN, schema.field("vaccinated")!!.type)
        assertEquals(FieldType.NUMBER, schema.field("weight")!!.type)
        assertEquals(FieldType.MULTI_CHOICE, schema.field("tags")!!.type)
        assertEquals(listOf("friendly", "shy", "loud"), schema.field("tags")!!.options)
        // a nested object stays json; its ref names the native type
        assertEquals(FieldType.JSON, schema.field("owner")!!.type)
        assertEquals("OwnerRef", schema.field("owner")!!.nativeType)
        assertEquals(FieldType.NUMBER, schema.field("id")!!.type)
        assertTrue(schema.field("id")!!.readOnly, "the id field is never written")
        assertEquals(FieldType.DATETIME, schema.field("updated_at")!!.type)
        assertTrue(schema.field("updated_at")!!.readOnly)
        assertFalse(schema.field("status")!!.required)
    }

    @Test
    fun `a get operation without a schema falls back to the list items`() {
        val connector = OpenApiConnector.of(full) as ResourceDiscovery
        val species = runBlocking { connector.describe(TestConnectorContext(), "species") }
        assertEquals(listOf("code", "label"), species.fields.map { it.name })
        assertTrue(species.field("code")!!.readOnly)
        val listed = runBlocking { connector.listResources(TestConnectorContext()) }
        assertEquals("A pet", listed.first().description)
    }

    @Test
    fun `a json document reads like a yaml one`() {
        val json =
            """
            {"openapi":"3.1.0","info":{"title":"Notes"},"servers":[{"url":"https://notes.example/api"}],
             "components":{"securitySchemes":{"token":{"type":"http","scheme":"bearer"}}},
             "paths":{
               "/notes":{"get":{"operationId":"listNotes","responses":{"200":{"description":"notes","content":{"application/json":{"schema":
                 {"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"text":{"type":["string","null"]}}}}}}}}}},
               "/notes/{id}":{"get":{"operationId":"getNote","parameters":[{"name":"id","in":"path","required":true}],"responses":{"200":{"description":"a note"}}}}
             }}
            """.trimIndent()
        val connector =
            OpenApiConnector.of(
                OpenApiConfig(id = "notes", document = json, securityScheme = "token", resources = listOf(OpenApiResource("note", "listNotes", "getNote")))
            )
        assertEquals("Notes", connector.manifest.provider.name)
        val schema = runBlocking { (connector as ResourceDiscovery).describe(TestConnectorContext(), "note") }
        assertEquals(FieldType.TEXT, schema.field("text")!!.type, "3.1 type arrays read their non-null type")
    }

    @Test
    fun `without a baseUrl the document's first server is used`() {
        val urls = mutableListOf<String>()
        val recording =
            object : ProviderHttp {
                override suspend fun exchange(request: ProviderRequest): ProviderResponse {
                    urls += request.url
                    return ProviderResponse(200, emptyMap(), """[{"code":"cat","label":"Cat"}]""".toByteArray())
                }
            }
        val connector = OpenApiConnector.of(readOnly(base).copy(baseUrl = null)) as ResourceReader
        val page = runBlocking { connector.list(TestConnectorContext(http = recording), wasichai.integrations.sdk.ReadRequest("species")) }
        assertEquals(listOf("cat"), page.records.map { it.id })
        assertEquals(listOf("https://petstore.example/v1/species"), urls)
    }

    @Test
    fun `a wrong mapping is refused at creation with every problem named`() {
        val pet = resource(full, "pet")
        val message =
            misconfigured(
                full.copy(
                    resources =
                        listOf(
                            pet.copy(list = "listPetz"),
                            pet.copy(name = "search", list = "searchPets"),
                            pet.copy(name = "badparam", pagination = OpenApiPagination.Page(pageParam = "p")),
                            pet.copy(name = "badid", idField = "uuid", updatedAtField = "name"),
                            pet.copy(name = "nofield", updatedAtField = null),
                            pet.copy(name = "nested", get = "listPets")
                        )
                )
            )
        assertTrue(message.startsWith("openapi connector 'petstore' is misconfigured: "), message)
        assertTrue("resource 'pet': list operation 'listPetz' is not in the document" in message, message)
        assertTrue("list operation 'searchPets' requires query parameter 'q', which the mapping does not send" in message, message)
        assertTrue("list operation 'listPets' has no query parameter 'p'" in message, message)
        assertTrue("id field 'uuid' is not in the record schema" in message, message)
        assertTrue("updatedAtField 'name' is not a date-time field" in message, message)
        assertTrue("resource 'nofield': updatedSinceParam needs updatedAtField" in message, message)
        assertTrue("get operation 'listPets' must take 1 path parameter(s), it takes 0" in message, message)
    }

    @Test
    fun `documents and servers the connector cannot use are refused`() {
        assertTrue("only openapi 3.x documents" in misconfigured(full.copy(document = """{"swagger":"2.0","info":{"title":"old"}}""")))
        assertTrue("neither json nor yaml" in misconfigured(full.copy(document = "{ not json")))
        val withVariables = document.replace("https://petstore.example/v1", "https://{region}.petstore.example/v1")
        assertTrue("has variables: set baseUrl" in misconfigured(full.copy(document = withVariables, baseUrl = null)))
        assertTrue("must be absolute https" in misconfigured(full.copy(baseUrl = "http://petstore.example/v1")))
        val external = document.replace("'#/components/schemas/OwnerRef'", "'owners.yaml#/OwnerRef'")
        assertTrue("only local \$refs are supported" in misconfigured(full.copy(document = external)))
        assertTrue("resource 'pet' is mapped twice" in misconfigured(full.copy(resources = full.resources + resource(full, "pet"))))
        assertTrue("no resource is mapped" in misconfigured(full.copy(resources = emptyList())))
    }
}
