package wasichai.integrations.connector.openapi

import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.RateLimitHint
import wasichai.integrations.sdk.testing.StubProviderServer
import wasichai.integrations.sdk.testing.StubProviderServer.StubResponse
import java.time.Instant

// the petstore document in test resources, its mapping and a stateful stub that answers as the document says
internal object OpenApiFixtures {
    val document: String = OpenApiFixtures::class.java.getResource("/petstore.yaml")!!.readText()

    // the mapping the developer guide quotes
    fun petstore(baseUrl: String) =
        OpenApiConfig(
            id = "petstore",
            document = document,
            securityScheme = "apiKey",
            baseUrl = baseUrl,
            rateLimit = RateLimitHint(requestsPerSecond = 5.0, burst = 10, scope = "connection"),
            // this module's tests cover the mapping below against PetstoreStub
            maturity = Maturity.TESTED,
            resources =
                listOf(
                    OpenApiResource(
                        name = "pet",
                        label = "Pet",
                        list = "listPets",
                        get = "getPet",
                        create = "createPet",
                        update = "updatePet",
                        delete = "deletePet",
                        pagination = OpenApiPagination.Page(pageParam = "page", limitParam = "limit"),
                        updatedAtField = "updated_at",
                        updatedSinceParam = "updated_since"
                    ),
                    OpenApiResource(
                        name = "owner",
                        list = "listOwners",
                        get = "getOwner",
                        itemsField = "data",
                        pagination = OpenApiPagination.Cursor(cursorField = "meta.next_cursor", cursorParam = "cursor", limitParam = "limit")
                    ),
                    OpenApiResource(
                        name = "toy",
                        list = "listToys",
                        get = "getToy",
                        itemsField = "items",
                        pagination = OpenApiPagination.NextUrl(nextField = "next")
                    ),
                    OpenApiResource(name = "visit", list = "listVisits", get = "getVisit", pagination = OpenApiPagination.NextUrl(limitParam = "per_page")),
                    OpenApiResource(name = "species", list = "listSpecies", get = "getSpecies", idField = "code")
                )
        )

    // only the reading resources: no write, no change feed
    fun readOnly(baseUrl: String) = petstore(baseUrl).copy(maturity = Maturity.IMPLEMENTED, resources = petstore(baseUrl).resources.drop(1))

    fun resource(
        config: OpenApiConfig,
        name: String
    ) = config.resources.single { it.name == name }

    // pets in a map; updated_at moves one minute per write so the change feed has something to follow
    class PetstoreStub(
        private val stub: StubProviderServer
    ) {
        private val json = JsonMapper.builder().build()
        val pets = sortedMapOf<Int, ObjectNode>()
        private var clock = Instant.parse("2026-11-01T00:00:00Z")
        private var nextId = 1

        fun pet(
            name: String,
            status: String = "available"
        ): ObjectNode {
            clock = clock.plusSeconds(60)
            val pet =
                json.createObjectNode().apply {
                    put("id", nextId)
                    put("name", name)
                    put("status", status)
                    put("birthday", "2020-05-17")
                    put("vaccinated", true)
                    put("weight", 4.5)
                    putArray("tags").add("friendly")
                    putObject("owner").put("id", "o1")
                    put("updated_at", clock.toString())
                }
            pets[nextId++] = pet
            return pet
        }

        fun install() {
            stub.on(HttpMethod.GET, "/v1/pets") { call, _ ->
                val since = call.query["updated_since"]?.let(Instant::parse)
                val page = call.query["page"]?.toInt() ?: 1
                val limit = call.query["limit"]?.toInt() ?: 100
                val matching = pets.values.filter { since == null || !Instant.parse(it.path("updated_at").asString()).isBefore(since) }
                StubResponse.json(json.writeValueAsString(matching.drop((page - 1) * limit).take(limit)))
            }
            stub.on(HttpMethod.GET, "/v1/pets/([^/]+)") { _, match ->
                pets[match.groupValues[1].toIntOrNull()]?.let { StubResponse.json(it.toString()) }
                    ?: StubResponse.json("""{"code":"not_found"}""", 404)
            }
            stub.on(HttpMethod.POST, "/v1/pets") { call, _ ->
                val body = json.readTree(call.body) as ObjectNode
                val pet = pet(body.path("name").asString())
                pet.setAll(body)
                StubResponse.json(pet.toString(), 201)
            }
            stub.on(HttpMethod.PATCH, "/v1/pets/([^/]+)") { call, match ->
                val pet = pets[match.groupValues[1].toInt()] ?: return@on StubResponse.json("""{"code":"not_found"}""", 404)
                clock = clock.plusSeconds(60)
                pet.setAll(json.readTree(call.body) as ObjectNode)
                pet.put("updated_at", clock.toString())
                StubResponse.json(pet.toString())
            }
            stub.on(HttpMethod.DELETE, "/v1/pets/([^/]+)") { _, match ->
                if (pets.remove(match.groupValues[1].toInt()) == null) StubResponse.json("""{"code":"not_found"}""", 404) else StubResponse(204)
            }
        }
    }

    fun owner(id: String) = """{"id":"$id","name":"Owner $id","email":"$id@petstore.example"}"""

    fun toy(id: String) = """{"id":"$id","name":"Toy $id"}"""

    fun visit(id: String) = """{"id":"$id","pet":1,"at":"2026-11-02T10:00:00Z"}"""
}
