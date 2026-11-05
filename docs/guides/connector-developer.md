# Writing a connector

A step-by-step guide to a new connector: a Kotlin library on `integrations-connector-sdk`, tested with
`integrations-connector-sdk-test`, installed in an app as a `Connector` bean. The contracts themselves are described in
the [connector SDK reference](connector-sdk.md).

The two shipped connectors are the real references:

- `integrations-connector-notion` (`NotionConnector`): OAuth and API key, discovery of data sources, reads, a change feed
  on `last_edited_time`, writes, signed webhooks with a verification handshake.
- `integrations-connector-salesforce` (`SalesforceConnector`): OAuth with PKCE, sObject describe, SOQL reads, CRUD,
  upsert by external id, a change feed polling `SystemModstamp` and deleted records.
  `integrations-connector-salesforce-pubsub` (`SalesforcePubSubConnector`) shows a connector that reuses another by
  delegation and runs its own transport (gRPC) with `ProviderHttp.credentialHeaders`
  ([ADR-0024](../adr/0024-salesforce-cdc-pub-sub.md)).

The example below is a small made-up provider, "Tasks", that implements `Connector`, `ResourceReader` and
`ResourceWriter`. Every snippet is an excerpt of
[`TasksConnector.kt`](../../integrations-connector-sdk-test/src/test/kotlin/wasichai/integrations/sdk/testing/guide/TasksConnector.kt)
and [`TasksConnectorTest.kt`](../../integrations-connector-sdk-test/src/test/kotlin/wasichai/integrations/sdk/testing/guide/TasksConnectorTest.kt),
which `./gradlew build` compiles and runs, so the guide stays true to the SDK.

## When you don't need to write a connector: the OpenAPI connector

When the provider publishes an OpenAPI 3 document and its records are plain JSON objects behind list and get
operations, `integrations-connector-openapi` may be enough. The app writes the mapping in code (tenants never upload
one) and declares the connector as a bean; the config is checked against the document when the bean is created and
every problem is named in one `IllegalArgumentException`, so a wrong `operationId` fails the start, not a sync. This
excerpt of
[`OpenApiFixtures.kt`](../../integrations-connector-openapi/src/test/kotlin/wasichai/integrations/connector/openapi/OpenApiFixtures.kt)
maps the petstore document of its tests:

```kotlin
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
```

In the app: `@Bean fun petstore(): Connector = OpenApiConnector.of(config)`, with `document` read from a classpath
resource. What the mapping gives:

- **Auth** from `components.securitySchemes[securityScheme]`: an API key in a header, HTTP bearer, or OAuth 2
  authorization code (scopes from the flow unless `scopes` narrows them, PKCE unless `pkce = false`). API keys in a
  query or cookie, HTTP basic and other OAuth flows are refused: the platform cannot send them.
- **Discovery**: the mapped resources; fields from the get operation's response schema (or the list items'), with
  local `$ref`s and `allOf` resolved, `enum` strings as choices, `date`/`date-time` formats as dates, nested objects
  and `oneOf`/`anyOf` as JSON. The id field and `readOnly` properties are read-only; `required` comes from the create
  body.
- **Reads**: get answers `null` on 404/410; list pages by `Page`, `Cursor`, `NextUrl` (a body field or the `Link`
  header, followed only under the base url) or `None`.
- **Change feed** when a resource has `updatedAtField` and `updatedSinceParam` (the parameter must keep records updated
  *at or after* the instant). Deletions are not reported: reconciliation finds them.
- **Writes** when a resource maps create, update or delete; an update with an expected version reads and compares the
  updated-at field first. Nothing else is guessed: no webhooks, no actions, no account lookup (`AUTHENTICATION`).

Capabilities are `IMPLEMENTED` until the app's own tests cover its mapping (`ConnectorContractTest` plus stub tests like
this module's); then set `maturity = Maturity.TESTED`. When the API needs more (nested paths, custom error codes,
webhooks, a composite id), write a connector. Decisions in [ADR-0021](../adr/0021-openapi-connector.md).

## 1. Read the provider's documentation first

Before any code, collect from the provider's API reference: how an account authenticates, what a "record" is, how
lists page, whether there is an incremental feed and whether it reports deletions, whether writes can be conditional
(an etag, a revision), the error format and codes, the rate limits, and the webhook signature scheme. Copy real
payloads from the documentation: they become your stub fixtures (step 5). The platform runs no real provider account in
CI.

## 2. Create the module

A connector is its own Gradle module that depends on the SDK only, and on the contract kit for tests. The shipped ones:

```kotlin
plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

dependencies {
    api(project(":integrations-connector-sdk"))
    testImplementation(project(":integrations-connector-sdk-test"))
}
```

In this repository a folder named `integrations-*` with a `build.gradle.kts` is a module automatically
(`settings.gradle.kts`); a new published module must also be added on purpose to
`.github/scripts/check-maven-publications.sh` ([releasing](../development/releasing.md)). Outside this repository,
depend on `wasichai:integrations-connector-sdk` and `wasichai:integrations-connector-sdk-test` (test scope) through
`wasichai:integrations-bom`.

Rules that hold for every connector: no Spring, no provider name outside the connector module, no blocking calls (every
capability is a `suspend` function and HTTP goes through `context.http`), no secrets in code, logs or exception messages.

## 3. Write the manifest

The manifest says what the connector is and what it really does. Declare only the capabilities you implement, at the
maturity they have: `IMPLEMENTED` once the code exists, `TESTED` only when tests exercise it.

```kotlin
class TasksConnector(
    private val baseUrl: String = "https://api.tasks.example"
) : Connector,
    ResourceReader,
    ResourceWriter {
    override val manifest =
        ConnectorManifest(
            id = "tasks",
            provider = ProviderInfo("Tasks", "https://tasks.example", "https://tasks.example/docs/api"),
            version = "0.1.0",
            sdkVersion = SdkVersion.CURRENT,
            description = "Tasks of a Tasks workspace: read, create, update and delete.",
            authMethods = listOf(AuthMethod.ApiKey()),
            capabilities =
                mapOf(
                    Capability.READ to Maturity.TESTED,
                    Capability.WRITE to Maturity.TESTED
                ),
            resources = listOf(ResourceDeclaration("task", "Task")),
            syncDirections = setOf(SyncDirectionSupport.SOURCE, SyncDirectionSupport.TARGET),
            pagination = setOf(PaginationStrategy.CURSOR),
            rateLimit = RateLimitHint(requestsPerSecond = 5.0, burst = 10, scope = "connection")
        )
```

Choices to make here:

- `id`: lowercase, stable forever (connections and sync definitions store it).
- `authMethods`: an `ApiKey()` sends `Authorization: Bearer <key>`; change `headerName`/`prefix` for other schemes. An
  OAuth method needs `authorizeUrl` and `tokenUrl`; set `pkce = false` and `clientAuthentication = BASIC` only when the
  provider documents it (Notion does).
- `resources`: static resources are checked against discovery by the contract kit; mark provider-defined ones
  `dynamic = true`.
- `rateLimit`: the documented per-account rate (Notion declares 3 requests per second, burst 6).

This example has no `Authenticator`: it cannot tell who the account is, so the platform cannot check a key at connect
time nor recognise the same account connected twice. A real connector implements it when the provider has a "who am I"
call (Notion: `GET /v1/users/me`; Salesforce: `/services/oauth2/userinfo`).

## 4. Implement the capabilities

Reads map the provider's payload to a `ProviderRecord` with typed `FieldValue`s, its version and its modification time.
A record that does not exist (or is trashed) reads as `null`; any other unsuccessful answer becomes a
`ConnectorException`:

```kotlin
    override suspend fun read(
        context: ConnectorContext,
        resource: String,
        id: String
    ): ProviderRecord? {
        val response = context.http.exchange(ProviderRequest(HttpMethod.GET, "$baseUrl/tasks/${safe(id)}"))
        // gone is an answer, not a failure: the sync engine treats null as a deletion
        if (response.status == 404) return null
        return record(checked(response).json())
    }
```

Writes honour `expectedVersion`. When the provider supports a conditional write, pass the version; its `409`/`412` maps
to `ConnectorException.Conflict`, which the sync engine answers by reading again and merging:

```kotlin
    // the provider checks If-Match itself: a 412 becomes ConnectorException.Conflict through HttpErrors
    override suspend fun update(
        context: ConnectorContext,
        resource: String,
        id: String,
        fields: Map<String, FieldValue>,
        expectedVersion: String?
    ): WriteResult {
        val headers = expectedVersion?.let { mapOf("If-Match" to it) } ?: emptyMap()
        val request = ProviderRequest.json(HttpMethod.PATCH, "$baseUrl/tasks/${safe(id)}", body(fields), headers)
        return result(checked(context.http.exchange(request)).json(), created = false)
    }
```

Convert typed values back to the provider's format and refuse what it cannot store; map errors through `HttpErrors`
(or your own classifier that falls back to it):

```kotlin
    // typed values back to the provider's json; anything this provider cannot store is refused, not guessed
    private fun body(fields: Map<String, FieldValue>): Map<String, Any?> =
        fields.mapValues { (name, value) ->
            when (value) {
                is FieldValue.Text -> value.value
                is FieldValue.Bool -> value.value
                FieldValue.Null -> null
                else -> throw ConnectorException.Permanent("tasks cannot store ${value.javaClass.simpleName} in '$name'", mapOf("field" to name))
            }
        }

    private fun checked(response: ProviderResponse): ProviderResponse {
        if (!response.successful) throw HttpErrors.classify(response)
        return response
    }
```

Things the shipped connectors do that a new one should consider:

- **Error codes.** Refine `HttpErrors.classify` with the provider's codes: Notion maps `rate_limited` (and `529`) to
  `RateLimited`, `conflict_error` to `Transient` (its documented answer is to retry), `validation_error` to `Permanent`.
- **Ids in urls.** Validate ids before putting them in a path (`NotionConnector.id`, `TasksConnector.safe`); quote
  values in query languages (`Soql` in the Salesforce connector).
- **Change feeds.** Return an opaque checkpoint that resumes exactly; report a checkpoint the provider refuses as
  `Permanent` so the engine starts over. Notion encodes the last minute seen plus the query cursor
  (`NotionCheckpoint`); Salesforce keeps the modstamp window, the deleted-records window and the next-records url.
- **No conditional write.** Read and compare before writing, as Notion's `update` does, and say in a comment that the
  race is narrowed, not closed.
- **Upsert.** Implement `ResourceUpserter` when the provider has an idempotent create keyed by an external id field
  (Salesforce); describe such fields with `externalId = true` so the sync can use them.
- **Webhooks.** Verify over the raw bytes in constant time (`MessageDigest.isEqual`); accept a handshake secret only
  while the endpoint has none (`NotionWebhooks`).
- **Connection metadata.** Keep what later calls need from the token response (`connectionMetadata`), such as
  Salesforce's `instance_url`; read it from `context.metadata`.

## 5. Test against a deterministic stub

The contract kit, `integrations-connector-sdk-test`, has three pieces:

- `StubProviderServer`: an HTTP server on `127.0.0.1` with a random port. `on(method, pathRegex) { recorded, match -> }`
  answers with a `StubResponse` (handlers may keep state), `onJson(method, path, body, status)` answers a fixed body,
  later routes win, `requests` lists every recorded call (method, path, query, headers, body), `reset()` clears both.
  An unmatched request answers `404`.
- `TestConnectorContext(http = KitProviderHttp(), metadata, connectionId, clock)`: a context whose `KitProviderHttp`
  sends what the connector asks plus `Authorization: Bearer test-token`. It does not rate limit or check hosts (the
  platform's tests cover those).
- `ConnectorContractTest`: an abstract JUnit class. Extend it, return your connector from `connector()`, and it checks
  that the manifest is valid and matches the implemented interfaces, that nothing `TESTED` is unsupported, and, when
  `discoveryContext()` returns a context, that discovery lists and describes every static resource.

Point the connector at the stub (`baseUrl`, `NotionConfig(apiBaseUrl = stub.baseUrl)`,
`SalesforceConfig(loginUrl = stub.baseUrl)` or `instance_url` in the context metadata) and build fixtures from the
provider's documented payloads (`NotionFixtures`, Salesforce's `Fixtures`):

```kotlin
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
```

Test the error paths as well as the happy ones:

```kotlin
    @Test
    fun `an update on a moved record is a Conflict`() {
        stub.onJson(HttpMethod.PATCH, "/tasks/t1", """{"error":"rev_mismatch"}""", status = 412)
        assertThrows<ConnectorException.Conflict> {
            runBlocking { tasks.update(context, "task", "t1", mapOf("done" to FieldValue.Bool(true)), "6") }
        }
        assertEquals("6", stub.requests.single().header("If-Match"))
    }

```

The connector has no discovery, so the kit's discovery check is skipped (JUnit reports it as skipped). Salesforce's
`SalesforceContractTest` shows the discovery variant: it starts a stub with the describe payloads and returns
`TestConnectorContext(metadata = mapOf("instance_url" to stub.baseUrl))` from `discoveryContext()`.

The connector configs accept `http://127.0.0.1` only so that tests can reach the stub; everything else must be `https`.
End-to-end tests that run the platform (Testcontainers PostgreSQL, tagged `integration`) live in
`integrations-integration-tests`, where `NotionStub` and `SalesforceStub` play stateful providers.

## 6. Install it in an app

The platform's `ConnectorRegistry` collects every `Connector` bean of the application context, validates each one and
fails the start on a bad manifest or a duplicate id. The core module never names a connector. Declare the bean in the
app:

```kotlin
@Configuration
class ConnectorsConfiguration {
    @Bean
    fun notionConnector(): NotionConnector = NotionConnector()

    @Bean
    fun salesforceConnector(): SalesforceConnector = SalesforceConnector(SalesforceConfig(loginUrl = "https://test.salesforce.com"))
}
```

`integrations-integration-tests` registers both shipped connectors exactly this way (`IntegrationsTestApplication`). The
shipped connector modules have no Spring dependency and no auto-configuration of their own, so adding the dependency
alone registers nothing ([ADR-0017](../adr/0017-connectors-as-plain-libraries.md), which supersedes ADR-0012 on this
point). A third-party connector may ship a Spring auto-configuration that declares its `Connector` bean; keep its SDK
code free of Spring either way and guard the bean with `@ConditionalOnMissingBean` so an app can replace it.

A connector runs in-process with the platform's trust ([ADR-0003](../adr/0003-in-process-trusted-connectors.md)):
install only code you would run in your app.

## 7. Version it

- `manifest.version` follows semver: a patch fixes behaviour, a minor adds capabilities or resources, a major changes
  what existing connections or definitions rely on (an id, a field name, a resource name).
- `sdkVersion = SdkVersion.CURRENT` (today `1.0`) records the contract the connector was built for. It runs on a
  runtime with the same major and an equal or newer minor; the registry refuses it otherwise.
- In this repository every module shares one release version (`gradle.properties`, release-please); the manifest's
  `version` is the connector's own and is bumped by hand with its behaviour.

## 8. Be honest about maturity

The catalog shows each capability's maturity to the people who connect accounts. `DECLARED` announces work that does
not exist yet and is not usable; `IMPLEMENTED` means the code exists without tests against a stub; `TESTED` means the
tests above exercise it and pass. Both shipped connectors mark every capability they implement `TESTED` because their
suites cover each one. When a test is removed or a capability is only partly covered, lower the label.

## Checklist

- [ ] The manifest declares exactly the implemented interfaces; `ConnectorContractTest` passes.
- [ ] Every capability marked `TESTED` has tests against a stub built from documented payloads.
- [ ] Unsuccessful answers map to the `ConnectorException` taxonomy; nothing secret in messages.
- [ ] `read` answers `null` for a missing record; `update` honours `expectedVersion`.
- [ ] Ids and query values are validated or escaped before they reach a url.
- [ ] `./gradlew ktlintFormat build` is green.
