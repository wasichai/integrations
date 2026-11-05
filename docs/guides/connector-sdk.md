# Connector SDK reference

The contracts a connector implements, as they are in `integrations-connector-sdk` (package `wasichai.integrations.sdk`).
The SDK has no Spring dependency: a connector is plain Kotlin plus coroutines and Jackson 3. Decisions behind it:
[ADR-0002](../adr/0002-connector-sdk-contracts.md), [ADR-0003](../adr/0003-in-process-trusted-connectors.md),
[ADR-0004](../adr/0004-connector-manifest-versioning-and-maturity.md), [ADR-0012](../adr/0012-connector-distribution-and-versioning.md).

To write one step by step, read the [connector developer guide](connector-developer.md).

## The shape of a connector

A connector is a `Connector` (it has a `manifest`) plus the optional capability interfaces it really implements. The
platform never calls an interface the connector does not implement, and refuses to start when the manifest and the
interfaces disagree (see [ManifestValidator](#manifestvalidator)).

```kotlin
interface Connector {
    val manifest: ConnectorManifest
}
```

Every capability call is a `suspend` function that receives a `ConnectorContext` for one connection.

| Capability | Interface | Methods |
|---|---|---|
| `AUTHENTICATION` | `Authenticator` | `accountInfo(context)`, `connectionMetadata(grant)`, `authorizationParameters()` |
| `DISCOVERY` | `ResourceDiscovery` | `listResources(context)`, `describe(context, resource)` |
| `READ` | `ResourceReader` | `read(context, resource, id)`, `list(context, request)` |
| `CHANGE_FEED` | `ChangeFeed` | `changes(context, resource, checkpoint, pageSize = 100)` |
| `WRITE` | `ResourceWriter` | `create(...)`, `update(..., expectedVersion)`, `delete(...)` |
| `UPSERT` | `ResourceUpserter` | `upsert(context, resource, externalIdField, externalId, fields)` |
| `WEBHOOKS` | `WebhookHandler` | `verify(request, secret)`, `parse(request)` (not suspending) |
| `ACTIONS` | `ActionExecutor` | `execute(context, action, input: JsonNode): JsonNode` |

### Authenticator

The platform runs the OAuth flows and stores the secrets; the connector only answers who the account is.

- `accountInfo` returns `AccountInfo(providerAccountId, displayName?, metadata)`. `providerAccountId` is the stable
  identity of the account (Notion: the workspace id; Salesforce: the organization id), never a display name: connecting
  the same account again updates the existing connection instead of adding one. It is called after the flow and again
  by "test connection".
- `connectionMetadata(grant: TokenGrant)` keeps non-secret values from the token response as connection metadata
  (Salesforce keeps `instance_url`; Notion keeps `workspace_id`, `workspace_name`, `bot_id`). Never return a secret here.
- `authorizationParameters()` adds query parameters to the authorize url (Notion adds `owner=user`).

`Authenticator` is optional, but without it nothing checks the credentials against the provider: a connect stores them
as given, "test connection" always succeeds, and the connection has no `providerAccountId`, so connecting the same
account again adds a second connection. Implement it whenever the provider has a "who am I" call. Either way, the
manifest lists the connector's `authMethods`.

### ResourceDiscovery

`listResources` returns `ResourceDescriptor(name, label, description?)`. `describe` returns a `ResourceSchema(resource,
label, fields)` whose `FieldDescriptor`s carry `name`, `label`, `type` (`FieldType`: `TEXT`, `NUMBER`, `BOOLEAN`, `DATE`,
`DATETIME`, `CHOICE`, `MULTI_CHOICE`, `REFERENCE`, `JSON`), `required`, `readOnly`, `externalId` (usable as an upsert
key), `options` and `nativeType`. Names are the provider's own: there is no canonical model. A missing resource is a
`ConnectorException.NotFound`. The sync module needs discovery to validate a definition.

### ResourceReader

- `read` returns one `ProviderRecord`, or `null` when the record does not exist (the sync engine reads `null` as a
  deletion, so a trashed or archived record should read as `null` too, as Notion does).
- `list` takes a `ReadRequest(resource, cursor?, pageSize = 100)` (`pageSize` within 1..1000) and returns a
  `RecordPage(records, nextCursor?)`; `nextCursor` is opaque to the platform and `null` on the last page.

`ProviderRecord(id, version?, modifiedAt?, fields, native?)`: `version` is an etag, revision or timestamp the provider can
check on write; `modifiedAt` is the provider's change time, `null` when it has no reliable one (`LAST_WRITE_WINS` needs
it on both sides); `fields` are typed `FieldValue`s; `native` is the untouched payload.

`FieldValue` is a closed set: `Null`, `Text`, `Number` (`BigDecimal`), `Bool`, `Date` (`LocalDate`), `DateTime` (`Instant`),
`Choice`, `MultiChoice`, `Reference(resource, id)`, `Json`. The sync engine compares and hashes these values, never raw
JSON.

### ChangeFeed

`changes(context, resource, checkpoint, pageSize)` returns a `ChangePage(changes, checkpoint?, hasMore)`. A `Change` is
`Upserted(record)` or `Deleted(id, deletedAt?)`. The checkpoint is a string only the connector reads; `null` means "from
the start". The engine saves the returned checkpoint after each page and calls again while `hasMore` is true.

A checkpoint the provider no longer accepts must be reported as `ConnectorException.Permanent`: the engine then starts
that side over once (counted as a cursor reset on the run). A feed may return the same record twice (Notion's
`last_edited_time` is rounded to the minute): the engine drops repeats by content hash. A feed that cannot see deletions
(Notion) leaves them to webhooks and reconciliation.

### ResourceWriter and ResourceUpserter

- `create(context, resource, fields)` and `update(context, resource, id, fields, expectedVersion?)` return a
  `WriteResult(id, version?, modifiedAt?, created)`. `fields` hold only the fields being written.
- `expectedVersion`, when given, must make the write fail with `ConnectorException.Conflict` if the record moved on.
  Use the provider's conditional write where it has one; Notion has none, so it reads and compares first, which narrows
  the race without closing it ([ADR-0009](../adr/0009-sync-semantics-and-conflicts.md)).
- `delete(context, resource, id)`: Notion moves the page to the trash.
- `ResourceUpserter.upsert` is an idempotent create-or-update keyed by an external id field (Salesforce:
  `PATCH .../sobjects/{type}/{field}/{value}`); `WriteResult.created` says which one happened. The sync engine uses it to
  make creates recoverable ([sync guide](sync.md#create-modes-and-externalidfield)).

### WebhookHandler

- `verify(request: WebhookRequest, secret: String?)` checks the raw bytes (`request.body`) and answers
  `WebhookVerification.Valid`, `Invalid(reason)` or `Handshake(status = 200, responseBody?, secretToStore?)`. `secret` is
  the endpoint's stored secret, `null` until one is stored. A handshake's `secretToStore` is kept only while the endpoint
  has none (Notion posts a `verification_token` first, then signs every delivery with it).
- `parse(request)` returns `ProviderEvent(providerEventId?, eventType, resourceType?, resourceId?, occurredAt?)`s. The
  platform wraps each one in its own event envelope ([event model](../architecture/event-model.md)). Events are hints:
  the sync engine reads the records again rather than trusting the payload.

### ActionExecutor

`execute(context, action, input)` runs a named action declared in `manifest.actions` (`ActionDeclaration(name, label,
sensitive = true)`). No shipped connector implements actions, and no REST route calls them yet; exposing actions as MCP
tools is planned ([ADR-0013](../adr/0013-mcp-exposure-later-and-opt-in.md)).

## The manifest

```kotlin
data class ConnectorManifest(
    val id: String,                                  // ^[a-z][a-z0-9-]{1,39}$, unique in the app
    val provider: ProviderInfo,                      // name, homepage?, documentation?
    val version: String,                             // semver x.y.z[-pre]
    val sdkVersion: SdkVersion,                      // the contract it was built for
    val description: String,
    val authMethods: List<AuthMethod>,               // at least one, each type once
    val capabilities: Map<Capability, Maturity>,
    val resources: List<ResourceDeclaration> = emptyList(),
    val actions: List<ActionDeclaration> = emptyList(),
    val syncDirections: Set<SyncDirectionSupport> = emptySet(),   // SOURCE, TARGET
    val pagination: Set<PaginationStrategy> = emptySet(),          // CURSOR, OFFSET, NEXT_URL
    val rateLimit: RateLimitHint? = null,
    val webhooks: WebhookSupport? = null,
    val requiredPermissions: List<String> = emptyList()
)
```

- `authMethods` (`AuthMethod`): `OAuth2AuthorizationCode(authorizeUrl, tokenUrl, defaultScopes, pkce = true,
  clientAuthentication = BODY, revokeUrl?, scopeSeparator = " ")`, `OAuth2ClientCredentials(tokenUrl, defaultScopes,
  clientAuthentication)`, `ApiKey(headerName = "Authorization", prefix = "Bearer ")`, `Basic`. `clientAuthentication`
  is `BODY` or `BASIC` (client credentials in the token request body or as HTTP basic). A connector with an OAuth method
  needs the tenant's provider app before anyone connects ([deployment](deployment.md#provider-apps-per-tenant)).
- `resources`: `ResourceDeclaration(name, label, dynamic = false)`. A static resource must be found and described by
  discovery (the contract kit checks it); a dynamic one (a Notion data source, a Salesforce custom object) is found by
  discovery only. Resource and action names match `^[A-Za-z][A-Za-z0-9_.-]{0,99}$`.
- `rateLimit`: `RateLimitHint(requestsPerSecond > 0, burst >= 1, scope)`. The platform paces each connection with it
  ([operations](operations.md#rate-limits)).
- `webhooks`: `WebhookSupport(signatureScheme, events, payloadIsAuthoritative = false)`.
- `requiredPermissions`: what the provider account needs, shown in the catalog.

The catalog (`GET /api/integrations/connectors`) shows the manifest as is ([api.md](../architecture/api.md#catalog)).

## Capabilities and maturity

`Capability`: `AUTHENTICATION`, `DISCOVERY`, `READ`, `CHANGE_FEED`, `WRITE`, `UPSERT`, `WEBHOOKS`, `ACTIONS`.

`Maturity` is ordered:

| Maturity | Means | Counts as supported |
|---|---|---|
| `DECLARED` | planned, no code yet | no |
| `IMPLEMENTED` | the interface is implemented | yes |
| `TESTED` | implemented and covered by tests that pass against a provider stub | yes |

`manifest.supports(capability)` is true from `IMPLEMENTED` up. A capability the connector does not mention is not
supported at all. Mark `TESTED` only when tests exercise it: the catalog shows the label to users as a promise.

Shipped connectors today:

| Connector | Capabilities (all `TESTED`) | Auth |
|---|---|---|
| `notion` (`integrations-connector-notion`) | `AUTHENTICATION`, `DISCOVERY`, `READ`, `CHANGE_FEED`, `WRITE`, `WEBHOOKS` | OAuth code without PKCE, API key |
| `salesforce` (`integrations-connector-salesforce`) | `AUTHENTICATION`, `DISCOVERY`, `READ`, `CHANGE_FEED`, `WRITE`, `UPSERT` | OAuth code with PKCE |

Salesforce's change feed polls `SystemModstamp` and the deleted records; Change Data Capture is planned
([#14](https://github.com/wasichai/integrations/issues/14), [ADR-0014](../adr/0014-salesforce-change-capture.md)). More
connectors (Jira, GitHub, Slack, OpenAPI-based) are planned ([#15](https://github.com/wasichai/integrations/issues/15)),
none ships yet.

## ManifestValidator

`ManifestValidator.problems(connector, runtime = SdkVersion.CURRENT)` lists what is wrong; `validate` throws
`InvalidManifestException(connectorId, problems)`. The `ConnectorRegistry` validates every `Connector` bean at start-up
and fails the application on a problem or on two connectors with the same id. It checks:

- `id` matches `^[a-z][a-z0-9-]{1,39}$`; `version` is semantic; `sdkVersion` is supported by the runtime;
- the provider name is not blank; at least one auth method, no auth type twice;
- for every capability: declared at `IMPLEMENTED`/`TESTED` but the interface is missing, or the interface is
  implemented but the capability is not declared at all (a `DECLARED` entry is enough to own up to code in progress);
- `WEBHOOKS` supported if and only if `webhooks` support is declared; `ACTIONS` supported needs at least one action;
- no resource or action declared twice; names match `^[A-Za-z][A-Za-z0-9_.-]{0,99}$`.

## SdkVersion

`SdkVersion(major, minor)`, `SdkVersion.CURRENT` is `1.0`. A connector built for `1.2` runs on a `1.5` runtime, never on
`2.x` nor on `1.1` (`isSupportedBy`). `SdkVersion.parse("1.0")` reads the `<major>.<minor>` form. Within a major the SDK
only adds (new optional interfaces, new optional manifest fields).

## ConnectorException taxonomy

Every failure a connector reports is a `ConnectorException` (a sealed class); `retryable` tells the platform whether
trying later may help.

| Exception | `retryable` | Use for | Sync engine | Job runner | REST (discovery, validation) |
|---|---|---|---|---|---|
| `Transient(message, cause?)` | yes | 5xx, timeouts, provider-side clashes | fails the run, the job retries | retry with backoff | `502` |
| `RateLimited(message, retryAfter?)` | yes | 429 | fails the run, the job retries | retry after `retryAfter` | `429` + `Retry-After` |
| `AuthExpired(message)` | yes | 401, revoked or expired credentials | run fails `permanent` | `DEAD` (`auth_expired`) | `409` |
| `PermissionDenied(message)` | no | 403, a private address | the record fails alone | `DEAD` | `502` |
| `NotFound(message)` | no | 404, 410 | the record fails alone | `DEAD` | `404` |
| `Conflict(message, currentVersion?)` | no | 409, 412, an `expectedVersion` mismatch | re-read and merge, then a conflict | `DEAD` | `409` |
| `Permanent(message, details)` | no | validation errors, refused checkpoints, bad ids | the record fails alone | `DEAD` | `502` |

`AuthExpired` is retryable inside one call: on a `401` the platform refreshes an OAuth token once and repeats the
request ([ProviderHttp](#providerhttp)). When the refresh is refused, the connection becomes `NEEDS_REAUTH` and a person
must reconnect.

`HttpErrors.classify(response)` is the default mapping of an unsuccessful response: `401` → `AuthExpired`, `403` →
`PermissionDenied`, `404`/`410` → `NotFound`, `409`/`412` → `Conflict`, `429` → `RateLimited` (with `Retry-After` in
seconds), `5xx` → `Transient`, anything else → `Permanent`. Connectors refine it with the provider's error codes and fall
back to it (`NotionErrors`, `SalesforceErrors`). Messages must not contain secrets or record contents: they end up in
job and run errors.

## ProviderHttp

```kotlin
interface ProviderHttp {
    suspend fun exchange(request: ProviderRequest): ProviderResponse
}
```

The only way a connector reaches its provider. `ProviderRequest(method, url, headers, query, body?, contentType?)` takes an
absolute url; `ProviderRequest.json(method, url, body, headers)` serialises a body as JSON. `ProviderResponse(status,
headers, body)` has `header(name)` (case-insensitive), `json()` and `successful` (2xx). `HttpMethod`: `GET`, `POST`, `PUT`,
`PATCH`, `DELETE`.

In the running platform the `ProviderHttp` of a connection:

- adds the connection's credentials (bearer token, API key header, basic auth); the connector never sees a secret;
- refreshes an expiring OAuth token before the call, and on a `401` refreshes once and retries;
- paces calls with the manifest's `rateLimit` and pauses the connection after a `429` with `Retry-After`;
- accepts only absolute `http`/`https` urls without user info, refuses hosts that resolve to private, loopback or
  link-local addresses (`PermissionDenied`) unless `wasichai.integrations.http.allow-private-addresses` is on;
- applies `wasichai.integrations.http.timeout` (a timeout is `Transient`) and `max-response-bytes`;
- records the `integrations.http.requests` timer ([operations](operations.md#metrics)).

It does not throw on an unsuccessful status: the connector reads `status` and decides (`null` for a `404` on `read`,
`HttpErrors.classify` otherwise).

## ConnectorContext

```kotlin
interface ConnectorContext {
    val connectionId: String
    val http: ProviderHttp
    val metadata: Map<String, String>   // non-secret connection metadata kept at connect time
    val clock: Clock
}
```

One context per call on one connection. Keep caches keyed by `connectionId` (Notion caches schemas per connection and
data source). Read provider-specific locations from `metadata` (Salesforce's `instance_url`), never from user input.
Use `clock` rather than the system clock so tests stay deterministic.
