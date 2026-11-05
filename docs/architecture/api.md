# REST API

The contract with [integrations-ui](https://github.com/wasichai/integrations-ui), as the controllers implement it. Routes
exist only when the module is on the classpath and `wasichai.integrations.enabled` (or `wasichai.integrations.sync.enabled`
for the synchronization routes, `wasichai.integrations.workflows.enabled` for the workflow routes,
`wasichai.integrations.ai.enabled` for the AI tool routes, also `wasichai.integrations.ai.mcp.enabled` for the MCP
endpoint) is not false;
otherwise they answer `404` to an authenticated caller, as in
[Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/api/rest.md).

## Conventions (inherited from Wasichai)

- Base `/api/integrations`; Bearer JWT on every route except the public ones (OAuth callback, inbound webhooks,
  workflow webhooks), which run on their own security chain (`IntegrationsSecurityConfiguration`).
- Tenant from the token. An id that belongs to another tenant answers `404`, exactly like an id that does not exist.
- Errors: RFC 7807 `application/problem+json` `{type, title, status, detail, errors?: [{field, message}]}`; `errors` is
  present only with field violations. `400` invalid input (field violations, malformed `If-Match`, unknown enum value,
  bad cursor), `401` no or bad token, `403` missing permission, `404` unknown or another tenant's, `409` state conflict,
  `412` stale or missing `If-Match`, `422` well-formed but refused (a definition that does not validate).
- `X-Correlation-Id`: Wasichai's filter accepts one from the caller or makes one, and echoes it on every response.
- Lists are plain JSON arrays, newest or alphabetical first, with an optional `limit` where noted. Only `/events` and
  `/workflows/{id}/executions` page, with a keyset cursor (`{content, nextCursor}`).
- Versioned resources (provider configs, sync definitions, workflows) answer `ETag: "<version>"`; their `PUT` needs
  `If-Match: "<version>"` (`W/` and quotes are tolerated).
- Permission column: **R** reader = any authenticated member of the tenant; **M** manager = `MANAGE_ORGANIZATION`
  (`ADMIN` holds it), never a service account (Wasichai ADR-043); **P** public. See `IntegrationsAccess`.

### Provider failures inside an API call

Discovery, sync validation and the like call the provider. A `ConnectorException` is mapped (`ProviderErrors`), never
passed through with the provider's body:

| Connector exception | Answer |
|---|---|
| `RateLimited` | `429` with `Retry-After` (the provider's, else 30 s) |
| `AuthExpired` | `409` "The connection needs a new authorization" |
| `NotFound` | `404` |
| `Conflict` | `409` |
| `PermissionDenied`, `Transient`, `Permanent` | `502` |

`POST /connections/{id}/test` is the exception: its outcome is part of the `200` body. A failed sync run is not an HTTP
error either: it is the run's `status`, `error` and `errorKind`.

## Catalog

| Method | Path | Perm | Answer |
|---|---|---|---|
| GET | `/connectors` | R | `200` array of `ConnectorView` |
| GET | `/connectors/{id}` | R | `200` `ConnectorView`; `404` unknown or not installed |
| GET | `/connectors/{id}/capabilities` | R | `200` `{connectorId, capabilities: {CAPABILITY: MATURITY}}`; `404` |

`ConnectorView` (capabilities: `AUTHENTICATION`, `DISCOVERY`, `READ`, `CHANGE_FEED`, `WRITE`, `UPSERT`, `WEBHOOKS`,
`ACTIONS`):

| Field | Type | Notes |
|---|---|---|
| `id`, `provider`, `version`, `sdkVersion`, `description` | string | from the manifest |
| `homepage`, `documentation` | string? | |
| `authMethods` | `[{type, pkce?, defaultScopes}]` | `type`: `OAUTH2_AUTHORIZATION_CODE`, `OAUTH2_CLIENT_CREDENTIALS`, `API_KEY`, `BASIC` |
| `capabilities` | map | `CAPABILITY` → `DECLARED`, `IMPLEMENTED` or `TESTED` (capabilities listed below) |
| `resources` | `[{name, label, dynamic}]` | |
| `actions` | `[{name, label, sensitive}]` | |
| `syncDirections` | string[] | `SOURCE`, `TARGET` |
| `webhooks` | `{signatureScheme, events}?` | |
| `rateLimit` | `{requestsPerSecond, burst, scope}?` | |
| `requiredPermissions` | string[] | |
| `needsProviderConfig` | boolean | an OAuth connector needs the tenant's provider app first |
| `providerConfigured` | boolean | this tenant has one |

## Provider configs

The tenant's OAuth app at a provider. Every route is **M** (members get `403`, listing included).

| Method | Path | Answer |
|---|---|---|
| GET | `/provider-configs` | `200` array of `ProviderConfigView`, by `connectorId` |
| GET | `/provider-configs/{connectorId}` | `200` `ProviderConfigView` with `ETag`; `404` none |
| PUT | `/provider-configs/{connectorId}` | create (no `If-Match`) or replace (`If-Match` required) → `200` with `ETag` |
| DELETE | `/provider-configs/{connectorId}` | `204`; `404` none; `409` while connections to that connector exist |

Request `ProviderConfigRequest`:

| Field | Type | Rules |
|---|---|---|
| `clientId` | string | required, ≤ 500 |
| `clientSecret` | string? | write-only, ≤ 4000; absent keeps the stored one, `""` clears it |
| `scopes` | string[] | each non-blank, no whitespace, ≤ 200 |
| `options` | map string→string | keys `lower_snake_case` (≤ 64), values ≤ 2000 |

`ProviderConfigView`: `{id, connectorId, clientId, clientSecretSet, scopes, options, version, createdAt, updatedAt}`; the
secret is never answered.

`options` are stored and answered as given, for the app's own use. Nothing in the platform reads them: they do not
reach a connector (`ConnectorContext` carries the connection's `metadata`, not the provider config), and no built-in
connector has a setting there.

Errors: `400` violations (also `connectorId` "this connector does not use an oauth app"), `404` unknown connector, `412`
replace without `If-Match` or with a stale version, `409` a concurrent create won.

## Connections

| Method | Path | Perm | Answer |
|---|---|---|---|
| GET | `/connections` | R | `200` array of `ConnectionView` |
| POST | `/connections` | M | `201` `ConnectionView` (API key, basic, client credentials) |
| GET | `/connections/{id}` | R | `200` `ConnectionView`; `404` |
| DELETE | `/connections/{id}` | M | `204`; `404`; `409` while something uses it |
| POST | `/connections/{id}/test` | M | `200` `ConnectionTestView`; `404` |
| POST | `/connections/oauth/start` | M | `200` `{authorizationUrl, expiresAt}` |
| GET | `/oauth/callback` | P | `302` to the configured return url |

`CreateConnectionRequest`: `{connectorId, name?, authType, apiKey?, username?, password?, scopes?}`. `name` defaults to the
provider's name (1–200 characters). `authType` is `API_KEY` (needs `apiKey`), `BASIC` (needs `username`, `password`) or
`OAUTH2_CLIENT_CREDENTIALS` (uses the provider config; `scopes` default to the connector's); `OAUTH2_AUTHORIZATION_CODE`
is refused here. The credentials are checked against the provider before anything is stored; a refusal is a `400` on
field `credentials`. Connecting the same provider account again updates that connection in place.

`ConnectionView`: `{id, connectorId, name, providerAccountId?, displayName?, accountChecked, status, authType, scopes,
metadata, health, lastValidatedAt?, lastError?, version, createdAt, updatedAt}`; `status` `ACTIVE | NEEDS_REAUTH |
DISABLED | ERROR`, `health` `UNKNOWN | HEALTHY | DEGRADED | FAILING`. Secrets never appear.

`accountChecked` (boolean) says whether the connector can ask the provider who the account is (it implements
`Authenticator`). When `false` the credentials were stored without a check, `providerAccountId` is null, connecting the
same account again adds a second connection, and a test only proves that the stored credentials can be read. It is
also `false` when the connector is no longer installed.

`ConnectionTestView`: `{ok, error?, retryable?, accountChecked, connection}`. `accountChecked` is `true` when the
provider was asked for the account; `ok: true` with `accountChecked: false` means nothing was checked at the provider.
The test first opens the stored credentials, for every connector. A failure marks the connection: `AuthExpired` ⇒
`NEEDS_REAUTH`/`FAILING`, a retryable failure ⇒ `DEGRADED`, any other ⇒ `ERROR`/`FAILING`. `retryable` is `false` for an expired or
unreadable authorization: only a reconnect helps.

Credentials that no configured key opens (sealed with a key removed since, or a damaged row) are an `AuthExpired` with
the message `credentials cannot be decrypted with the configured keys; reconnect`, wherever they are needed: the
connection becomes `NEEDS_REAUTH`/`FAILING` with that `lastError`; a test answers `200` with `ok: false`, `retryable:
false`; discovery and other API calls on the connection answer `409` ("The connection needs a new authorization"); a
sync run fails as `permanent` and its job is not retried. Connecting the same account again replaces them; a
connection with `accountChecked: false` has to be deleted and connected anew.

A connection's status and health change only through a test, a token refresh (refused ⇒ `NEEDS_REAUTH`/`FAILING`),
unreadable credentials and connecting the account again. A refresh is refused when the token endpoint answers
`400`/`401` with an RFC 6749 `error`, or a `2xx` without `access_token` that carries an `error` (some providers refuse
inside a `200`); a `2xx` with neither is a malformed answer, a permanent failure that leaves the status alone
([ADR-0023](../adr/0023-token-refusals-in-ok-responses.md)). A failed sync run or job does not change them: its error is
on the run and the definition's status.

`DELETE` answers `409` naming what still uses the connection (each sync definition, in any status, with a side on
it; each workflow whose draft or published version names it); nothing is revoked or deleted then, so no link, run or
open conflict disappears with it. Otherwise it revokes the token at the provider when the connector declares a revoke
url (best effort), then deletes. `test` is a manager's action because it calls the provider and changes the
connection's status and health.

### OAuth

`POST /connections/oauth/start` takes `{connectorId, name?}`: `400` unknown connector or one without authorization-code
OAuth, `409` no provider config for it, `409` `wasichai.integrations.public-base-url` not set. The answer's
`authorizationUrl` carries a single-use `state` (valid `wasichai.integrations.oauth.state-ttl`, 10 min by default) and
a PKCE S256 challenge when the connector uses PKCE; the UI sends the browser there.

`GET /oauth/callback?code&state&error` (public) always answers `302` to `wasichai.integrations.oauth.return-url`
(configuration, never a request value) with:

- success: `?status=connected&connectionId=<id>`
- failure: `?status=error&error=<code>`, where `<code>` is one of

| Code | Meaning |
|---|---|
| `invalid_state` | state missing, unknown, expired or already used (checked first) |
| `access_denied` | the user declined at the provider |
| `provider_error` | the provider sent any other `error` |
| `missing_code` | no `code` |
| `unknown_connector` | the connector is no longer installed |
| `provider_not_configured` | the provider config was deleted meanwhile |
| `token_refused` | the token endpoint refused the code: a `400`/`401` with an `error`, or a `2xx` without `access_token` that carries an `error` |
| `provider_unavailable` | the provider failed while exchanging or reading the account |

### Discovery

| Method | Path | Perm | Answer |
|---|---|---|---|
| GET | `/connections/{id}/resources` | R | `200` `[{name, label, description?}]` |
| GET | `/connections/{id}/resources/{resource}/schema` | R | `200` `{resource, label, fields}` |

`fields`: `[{name, label, type, required, readOnly, externalId, options, nativeType?}]`, `type` one of `TEXT`, `NUMBER`,
`BOOLEAN`, `DATE`, `DATETIME`, `CHOICE`, `MULTI_CHOICE`, `REFERENCE`, `JSON`. `400` when the connector has no discovery;
provider failures as mapped above.

## Webhooks and events

| Method | Path | Perm | Answer |
|---|---|---|---|
| POST | `/connections/{id}/webhook-endpoints` | M | body `{secret?}` (optional) → `201` `WebhookEndpointView` |
| GET | `/connections/{id}/webhook-endpoints` | R | `200` array of `WebhookEndpointView`, oldest first |
| GET | `/webhook-endpoints/{id}/verification-token` | M | `200` `{token}` |
| DELETE | `/webhook-endpoints/{id}` | M | `204` |
| POST | `/webhooks/{connectorId}/{endpointId}` | P | see below |
| GET | `/events` | R | `200` `{content: [EventView], nextCursor?}` |
| GET | `/events/{id}` | R | `200` `EventView` |
| POST | `/events/{id}/replay` | M | `200` `EventView` (status `RECEIVED` again) |

`WebhookEndpointView`: `{id, connectionId, connectorId, url, status, secretSet, lastDeliveryAt?, createdAt}`; `url` is
what to register at the provider, `null` when `wasichai.integrations.public-base-url` was removed after the endpoint was
made; `status` `PENDING_VERIFICATION | ACTIVE | DISABLED` (the first accepted delivery makes
it `ACTIVE`). `secret` is the signing secret a provider shows at registration; leave it out for handshake providers.
Create: `404` connection, `400` connector without webhooks, `409` no `public-base-url`. The verification token is the
one secret the API shows (a provider such as Notion wants it pasted back): `404` until a handshake stored one.

The public delivery route reads the raw body and answers, in order:

- `413` when the body exceeds `wasichai.integrations.webhooks.max-body-bytes` (1 MiB);
- `404` for a malformed or unknown endpoint id, an endpoint of another connector, a `DISABLED` endpoint or a connector
  without webhooks (nothing says which tenant or why);
- `401` when the connector's signature check fails; nothing is stored;
- a handshake: the status and body the connector asks for (usually `200`); its secret is stored only while none is set;
- `200` for a valid delivery: each parsed event is stored with its processing job in one transaction. A duplicate
  (same provider event id, else same body hash and position) is stored once and still answered `200`.

`EventView`: `{eventId, tenantId, connectionId, provider, providerEventId?, resourceType?, resourceId?, eventType,
occurredAt?, receivedAt, schemaVersion, correlationId, causationId?, source, status, processedAt?, error?, payload,
metadata}`; `source` `WEBHOOK | POLL | SYNC | SYSTEM`, `status` `RECEIVED | PROCESSED | IGNORED | FAILED` (no listener
acting on an event makes it `IGNORED`, never dropped).

`GET /events` filters `connectionId`, `status`; pages newest first by `(receivedAt, id)` with `limit` (1–200, default
50) and `cursor` (the previous page's `nextCursor`; `400` for any other value). Replay: `409` when the event is already
waiting to be processed.

## Jobs and health

| Method | Path | Perm | Answer |
|---|---|---|---|
| GET | `/jobs` | M | `200` array of `JobView`, most recently updated first; filters `status`, `kind`; `limit` 1–500 (50) |
| POST | `/jobs/{id}/retry` | M | `200` `JobView` back to `PENDING` with zero attempts; `409` unless `DEAD`; `404` |
| GET | `/health` | R | `200` `HealthView` |

`JobView`: `{id, kind, status, attempts, maxAttempts, nextAttemptAt, lastError?, errorKind?, retryable, correlationId?,
createdAt, updatedAt, finishedAt?}`; `status` `PENDING | RUNNING | SUCCEEDED | DEAD`; `retryable` is false for a
permanent error, which would fail again as it is.

`HealthView`: `{connections: {STATUS: count}, failing: [{id, connectorId, name, status, health, lastValidatedAt?,
lastError?, rateLimitedUntil?}], jobs: {STATUS: count}}`. `connections` lists every status, zero included; `jobs` only
the statuses that have jobs. `failing` holds connections that are not `ACTIVE`, or `DEGRADED` or `FAILING`.

## Synchronization

Module `wasichai-integrations-sync`.

### Definitions

| Method | Path | Perm | Answer |
|---|---|---|---|
| GET | `/sync-definitions` | R | `200` array of `SyncDefinitionView` |
| POST | `/sync-definitions` | M | `201` `SyncDefinitionView`, status `DRAFT` |
| GET | `/sync-definitions/{id}` | R | `200` with `ETag` |
| PUT | `/sync-definitions/{id}` | M | `If-Match` required → `200` with `ETag`; an `ACTIVE` definition goes back to `DRAFT` |
| DELETE | `/sync-definitions/{id}` | M | `204`; cascades to its links, snapshots, cursors, runs and conflicts |
| POST | `/sync-definitions/{id}/validate` | M | `200` `{valid, errors: [{field, message}]}` |
| POST | `/sync-definitions/{id}/activate` | M | `200` definition `ACTIVE`; `422` with the violations when it does not validate |
| POST | `/sync-definitions/{id}/deactivate` | M | `200` definition `PAUSED` |

`SyncDefinitionRequest`:

| Field | Type | Rules |
|---|---|---|
| `name` | string | 1–200 |
| `a`, `b` | `{connectionId, resource, externalIdField?}` | the tenant's connection (another tenant's is "unknown connection"); resource ≤ 300 |
| `direction` | enum | `A_TO_B`, `B_TO_A`, `BIDIRECTIONAL` |
| `deletePolicy` | enum | `PROPAGATE`, `IGNORE`, `FLAG` (default) |
| `scheduleSeconds` | int? | ≥ 60; incremental runs by the scheduler |
| `reconcileSeconds` | int? | ≥ 300; reconcile runs by the scheduler |
| `matchOn` | `{aField, bField}?` | links existing records with equal values instead of creating them |
| `mappings` | `FieldMapping[]` | 1–200; a field of A or of B mapped once at most |

`externalIdField` (1–200, optional) names a field of a side the sync creates records in, never a mapped one. When that
side's connector upserts it is the upsert key and must be an external id field of the schema; otherwise it must be a
writable `TEXT` field and holds the create token (marker mode). Both make an interrupted create recoverable without a
duplicate; without it, an unfinished create becomes an `ORPHANED_CREATE` conflict. Create and replace check where it
may be used (`400`); `validate` and `activate` check it against the schema.

`FieldMapping`: `{aField, bField, transform?, owner, conflictStrategy}`. `owner` `A | B | BOTH` (default `BOTH`, which
needs `BIDIRECTIONAL`; `A` cannot be used with `B_TO_A`, `B` not with `A_TO_B`). `conflictStrategy` `SOURCE_WINS |
A_WINS | B_WINS | LAST_WRITE_WINS | MANUAL` (default `MANUAL`) applies to `BOTH`; an owner always wins its field.

`transform` is a JSON object, written A → B; absent means `copy`:

| `type` | Other keys | Inverse (B → A) |
|---|---|---|
| `copy` | | the same value |
| `static` | `value` | none: owner must be `A` and direction not `B_TO_A` |
| `default` | `value` (used when A is null) | the value as it is |
| `enumMap` | `map`: `{aValue: bValue}`, non-empty | first A value naming the B value; unmapped ⇒ null |
| `dateTime` | `zone` (default `UTC`), `a`, `b`: `"DATE"` or other | date ↔ date-time in the zone |
| `number` | `scale` 0–12 (half up on the way to B) | the value as it is |
| `conditional` | `equals`, `then`, `else` | none: owner must be `A` and direction not `B_TO_A` |

Create and replace check the shape only (`400` with violations such as `mappings[2].owner`). `validate` and `activate`
also read both sides' schemas: the connectors can describe, read and (where the direction writes) write; the resource
and every field exist; a written field is not read-only; types fit through the transform; `enumMap` values are options.
A missing resource is a violation; any other provider failure is mapped as above. `PUT` without `If-Match` or with a
stale version is `412`.

`SyncDefinitionView`: the request's fields plus `{id, status: DRAFT | ACTIVE | PAUSED, version, createdAt, updatedAt}`.
Every status change bumps `version`.

### Runs and status

| Method | Path | Perm | Answer |
|---|---|---|---|
| POST | `/sync-definitions/{id}/run` | M | body `{kind?}` (optional) → `202` `{runId}` |
| GET | `/sync-definitions/{id}/runs` | R | `200` array of `SyncRunView`, newest first; `limit` 1–200 (50) |
| GET | `/sync-definitions/{id}/status` | R | `200` `SyncStatusView` |

`kind`: `INCREMENTAL` (default; recorded as trigger `MANUAL`), `INITIAL` (both cursors from the start), `RECONCILE`
(both sides read whole, cursors untouched). The run row is created `QUEUED` before its job, so `runId` is known at once.
`409` when the definition is not `ACTIVE` or one of its runs is already queued or running; `400` unknown kind.

`SyncRunView`: `{id, definitionId, trigger, status, read, created, updated, skipped, conflicts, failed, missing, diverged,
cursorResets, error?, errorKind?, queuedAt, startedAt?, finishedAt?}`. `trigger` `MANUAL | SCHEDULE | EVENT | INITIAL |
RECONCILE`; `status` `QUEUED | RUNNING | SUCCEEDED | PARTIAL` (some records failed alone) `| FAILED`; `errorKind`
`transient` (the job retries) or `permanent` (a person must act). `missing` and `diverged` are reconciliation findings,
`cursorResets` checkpoints the provider refused.

`SyncStatusView`: `{definitionId, status, lastRun?, lastSuccessAt?, lastError?, errorKind?, pendingConflicts, links:
{LINKED, PENDING_CREATE, CONFLICT, ORPHANED: count}, cursors: [{side, checkpoint?, updatedAt}], nextScheduledAt?,
nextReconcileAt?, queuedOrRunning}`. `lastError` comes from the latest finished run only when it failed; the next times
are set for an `ACTIVE` definition with a schedule (now, when it never ran).

### Conflicts

| Method | Path | Perm | Answer |
|---|---|---|---|
| GET | `/conflicts` | R | `200` array of `ConflictView`, newest first; filters `status` (`OPEN`, `RESOLVED`), `definitionId`; `limit` 1–200 (50) |
| GET | `/conflicts/{id}` | R | `200` `ConflictView` |
| POST | `/conflicts/{id}/resolve` | M | `200` the resolved `ConflictView` |

`ConflictView`: `{id, definitionId, linkId?, kind, fields, status, resolution?, detectedAt, resolvedAt?}`. `kind`
`FIELDS` (both sides changed a field and no rule decided), `DELETED` (a linked record was deleted under policy
`FLAG`, or under `PROPAGATE` when the direction does not write the other side; `fields` is empty) or
`ORPHANED_CREATE` (a create was interrupted and cannot be recovered without an `externalIdField`; `fields` is empty). `fields`: `[{key, aField, bField, a, b, base?: {a, b}}]`, values typed as `{"t": "text", "v":
"…"}` (`t` one of `null`, `text`, `number`, `bool`, `date`, `datetime`, `choice`, `multi`, `ref` with `r`, `json`).
`linkId` is null once the pair is forgotten.

```json
{
  "id": "6f0c…",
  "definitionId": "2b1e…",
  "linkId": "9a7d…",
  "kind": "FIELDS",
  "fields": [
    {
      "key": "Industry->Industry",
      "aField": "Industry",
      "bField": "Industry",
      "a": { "t": "choice", "v": "Retail" },
      "b": { "t": "choice", "v": "Energy" },
      "base": { "a": { "t": "choice", "v": "Banking" }, "b": { "t": "choice", "v": "Banking" } }
    }
  ],
  "status": "OPEN",
  "resolution": null,
  "detectedAt": "2026-11-04T18:20:00Z",
  "resolvedAt": null
}
```

Resolve takes `{choice: A | B | CUSTOM, values?, fields?}`. With `CUSTOM`, each conflicting field is settled by
`values` (a plain JSON value, or a typed one, keyed by A's field name) or else by `fields` (`"A"` or `"B"` keyed by the
mapping key, e.g. `{"Industry->Industry": "B"}`). A value taken from B, by `choice: B` or a `fields` pick, goes back
to A through the mapping's inverse transform (an enum map backwards, a date-time to its day); a pick of B on a
`static` or `conditional` mapping is refused with a violation on `fields.<key>`, since they have no inverse. The chosen
values are written where the direction allows, the snapshot
records them, and the link is `LINKED` again. A `DELETED` conflict ignores the body: resolving it unlinks the pair
(`resolution: {choice: "UNLINK"}`) and the conflict stays as history. Errors: `400` invalid choice, a missing custom
value, a pick other than `A` or `B`, or a pick of B without an inverse; `404` the conflict, or its link, no longer
exists; `409` already resolved (or resolved meanwhile), a record gone or changed while resolving.

## Workflows

Module `wasichai-integrations-workflows` ([ADR-0022](../adr/0022-integration-workflows.md)). A workflow is a draft
graph that is validated and published into immutable, numbered versions; the published version runs on the
integrations job queue ([ADR-0025](../adr/0025-workflow-execution.md)). Guide: [workflows](../guides/workflows.md).

| Method | Path | Perm | Answer |
|---|---|---|---|
| GET | `/workflows` | R | `200` array of `WorkflowView`, by name |
| POST | `/workflows` | M | `201` `WorkflowView`, status `DRAFT` |
| GET | `/workflows/{id}` | R | `200` with `ETag` |
| PUT | `/workflows/{id}` | M | `If-Match` required → `200` with `ETag`; changes the draft only, never a version |
| DELETE | `/workflows/{id}` | M | `204`; deletes its versions and trigger |
| POST | `/workflows/{id}/validate` | M | `200` `{valid, errors: [{field, message}]}` for the draft |
| POST | `/workflows/{id}/publish` | M | `201` `WorkflowVersionView` of the next number; `If-Match` optional |
| GET | `/workflows/{id}/versions` | R | `200` array of `{number, publishedAt, publishedBy}`, newest first |
| GET | `/workflows/{id}/versions/{n}` | R | `200` `WorkflowVersionView`; `404` no such version |
| POST | `/workflows/{id}/run` | M | `202` `ExecutionView`; body `{payload?}` (a JSON object, `400` otherwise); `409` not published or paused |
| POST | `/workflows/{id}/pause` | M | `200` `WorkflowView` with `paused: true`: no trigger starts a run; running ones finish |
| POST | `/workflows/{id}/resume` | M | `200` `WorkflowView` with `paused: false` |
| POST | `/workflows/{id}/webhook` | M | `201` `{url, path}`: a new webhook url (the previous one stops working), shown once |
| GET | `/workflows/{id}/executions` | R | `200` `{content: [ExecutionView], nextCursor}`, newest first; `status`, `cursor`, `limit` (1–200, default 50) |
| GET | `/workflows/executions/{executionId}` | R | `200` `{execution: ExecutionView, steps: [StepExecutionView]}` |
| POST | `/workflows/executions/{executionId}/cancel` | M | `200` detail, `CANCELLED`; `409` already over |
| POST | `/workflows/executions/{executionId}/steps/{stepId}/approve` | M | `200` detail; body `{comment?}` (≤ 2000); `404` no such approval step; `409` not waiting or run over |
| POST | `/workflows/executions/{executionId}/steps/{stepId}/reject` | M | as approve |
| POST | `/webhooks/workflows/{id}/{secret}` | P | `202` `{executionId}`; see below |

`WorkflowRequest`: `{name, graph}`. `name` 1–200 (trimmed). `graph` a JSON object of at most 256 KiB, absent means
`{}`. Create and update check only that (`400`): a draft may be half built. `PUT` without `If-Match` or with a stale
version is `412`.

`WorkflowView`: `{id, name, status: DRAFT | PUBLISHED, graph, publishedVersion, unpublishedChanges, paused, version,
createdAt, updatedAt}`. Pausing does not change `version`. `publishedVersion` is null until the first publish; `unpublishedChanges` is true while the draft differs
from that version (compared as JSON, so key order does not count). Publishing bumps `version`.

`WorkflowVersionView`: `{workflowId, number, graph, publishedAt, publishedBy}`. A version never changes, and the
database refuses an update of one.

`publish` validates the draft (as `validate`) and answers `422` with the violations when it does not; `409` when the
draft is already the published version; `412` when `If-Match` is stale or the draft changed while it was validated.
It stores the draft as version `publishedVersion + 1` and replaces the workflow's trigger row in one transaction.

### Executions

A run starts from the published version's trigger (or `run`) and stays pinned to that version. Executions and their
steps are the tenant's: another tenant's are `404`.

`ExecutionView`: `{id, workflowId, version, status, triggerType: event | webhook | schedule | manual, trigger,
correlationId, startedBy, error, createdAt, startedAt, finishedAt}`. `status` is `QUEUED`, `RUNNING`, `WAITING` (a
step waits: a delay or an approval; with parallel branches the latest change wins), `SUCCEEDED`, `FAILED` (`error`
names the step) or `CANCELLED`. `trigger` is what `trigger.…` refs read: `{type, payload}` plus, for events,
`eventId, connectionId, eventType, resourceType, resourceId, occurredAt` and, for schedules, `firedAt`. The event's
`payload` is the provider's body as received; a manual run's or a webhook's is the JSON sent.

`StepExecutionView`: `{stepId, type, status, attempt, input, output, error, waitUntil, decidedBy, createdAt, startedAt,
finishedAt}`, in the order the run reached them. `status` is `PENDING`, `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`
or `SKIPPED` (the run ended first). Outputs: an action's is the connector's answer; a write's `{id, version, created,
modifiedAt}` (`{id, deleted}` for a delete); a condition's or branch's `{result}`; an approval's `{approved, decidedBy,
decidedAt, comment?}` or `{approved: false, timedOut: true}`. A step not reached has no row.

Semantics ([ADR-0025](../adr/0025-workflow-execution.md)): a finished step never runs again; a step's provider call is
at least once (a `create` or an action retried after a lost answer may repeat; `upsert` does not). A failure the queue
may retry is retried with its backoff up to `step-max-attempts`; then `onError`, else the run fails. A join runs once,
after every branch arrived. `end`, or a `condition` that does not hold, ends the run `SUCCEEDED`. Rejecting without a
`rejected` edge ends the run `SUCCEEDED`; a timeout follows `rejected` or fails the step. Cancel ends the run: steps
not yet run are `SKIPPED` and their jobs do nothing.

Workflow webhook: `POST /api/integrations/webhooks/workflows/{id}/{secret}` needs no token (the secret authenticates;
only its SHA-256 is stored). Body: JSON, at most `wasichai.integrations.webhooks.max-body-bytes` (`413`), empty means
`{}`, not JSON is `400`. `Idempotency-Key` (≤ 200) starts one run per key: a repeat answers the same `executionId`.
Unknown workflow, wrong secret or a trigger that is not `webhook`: `404`. Paused: `409`. `url` in the issue answer is
null while `wasichai.integrations.public-base-url` is unset; `path` is always there.

An event starts each published, unpaused workflow whose trigger names its connection and event type, once per event
(a replay starts nothing new); the event is then `PROCESSED`. A schedule fires the latest due time once across
replicas (missed firings collapse into one).

### Graph

```json
{
  "trigger": {"type": "event", "connectionId": "…", "eventType": "message.created", "next": "check"},
  "steps": [
    {"id": "check", "type": "branch", "condition": {"op": "exists", "ref": "trigger.payload.email"}, "then": "upsert", "else": "done"},
    {"id": "upsert", "type": "action", "connectionId": "…",
     "write": {"resource": "contacts", "operation": "upsert", "externalIdField": "extId", "externalId": {"ref": "trigger.payload.id"}},
     "input": {"email": {"ref": "trigger.payload.email"}, "tier": {"value": "gold"}}, "next": "notify", "onError": "done"},
    {"id": "notify", "type": "action", "connectionId": "…", "action": "send_message",
     "input": {"text": {"ref": "steps.upsert.output.id"}}, "next": "done"},
    {"id": "done", "type": "end"}
  ]
}
```

| Trigger `type` | Keys |
|---|---|
| `event` | `connectionId`, `eventType` (one of the connector's declared webhook events) |
| `webhook` | none; the url comes from `POST /workflows/{id}/webhook` |
| `schedule` | `cron` (Spring six fields, at most once a minute), `zone` (default `UTC`) |
| `manual` | none |

Every trigger has `next`, the first step.

| Step `type` | Keys | Edges |
|---|---|---|
| `action` | `connectionId`; `action` (a declared connector action) **or** `write`: `{resource, operation: create \| update \| upsert \| delete, recordId?, externalIdField?, externalId?}`; `input`: `{field: operand}` (≤ 100) | `next`, `onError?` |
| `condition` | `condition`; the run goes on when it holds and ends otherwise | `next`, `onError?` |
| `branch` | `condition` | `then`, `else`, `onError?` |
| `parallel` | `branches` (2–20 step ids), `join` (where every branch meets) | `branches[i]`, `onError?` |
| `delay` | `duration` ISO-8601, 1 s to 30 days | `next`, `onError?` |
| `approval` | `title` 1–200, `timeout?` 1 min to 30 days | `next` (approved), `rejected?`, `onError?` |
| `end` | | none |

Step ids match `[A-Za-z][A-Za-z0-9_-]{0,63}`; at most 200 steps. An operand is `{"ref": "<path>"}` or
`{"value": <json>}`. A path is `trigger[.field…]` or `steps.<id>[.field…]`. `update` and `delete` need `recordId`,
`upsert` needs `externalIdField` and `externalId`, `delete` takes no input, `create` at least one field.

Conditions are a closed set, never an expression: `{"op": "equals" | "notEquals", "ref", "value"}`, `{"op": "exists",
"ref"}`, `{"op": "in", "ref", "values": [1–100 literals]}`, `{"op": "gt" | "lt", "ref", "value"}` (a number, an ISO date
or an ISO date-time with offset), `{"op": "and" | "or", "conditions": [1–100]}`, `{"op": "not", "condition"}`; at most 8
deep. Values are literals (string, number, boolean, null).

`validate` (and `publish`) reports every problem with its path, e.g. `graph.steps[2].input.email`:

- shape: exactly one trigger object, known types, required keys, ids, durations, cron and zone;
- edges: every edge (and `trigger.next`, `join`) names an existing step, ids are unique, every step is reachable from
  the trigger, no edge closes a cycle (no loops), each parallel branch reaches its join;
- references: `steps.<id>` names an `action` or `approval` step upstream of the step reading it;
- parallel: a step reachable from two branches of one parallel must not lead to its join (branches meet only there);
- connections: each is the tenant's (another tenant's is "unknown connection") and its connector is installed; an event
  trigger's connector declares `WEBHOOKS` and the event type; an action's connector declares `ACTIONS` and the action;
  a write's connector declares `WRITE` (`UPSERT` for an upsert) and the resource (or discovers resources);
- schema, for writes on a connector with discovery (called only when all of the above hold): the resource exists, each
  input field exists and is not read-only, a literal fits the field type (and a choice's options), a create sets every
  required field, an upsert's `externalIdField` exists and is an external id field. A reference's type is known only
  when the workflow runs; a connector action's input is not checked (the manifest declares no input schema).

A missing resource is a violation; any other provider failure is mapped as above.

## AI tools

Module `wasichai-integrations-ai` ([ADR-0026](../adr/0026-ai-tools.md)), base `/api/integrations/ai`. A tool is a
connector action or one record operation that a tenant administrator exposed; nothing is exposed by default. Every
execution, from here or from the [MCP endpoint](#mcp-endpoint), goes through the same policy and is recorded. Guide: [AI tools](../guides/ai-tools.md).

| Method | Path | Perm | Answer |
|---|---|---|---|
| GET | `/ai/tools` | R | `200` array of `ToolView`, by name; a manager sees all, anyone else the enabled tools a role of theirs may call |
| POST | `/ai/tools` | M | `201` `ToolView` |
| GET | `/ai/tools/{name}` | R | `200` with `ETag`; `404` also for a tool the caller may not call |
| PUT | `/ai/tools/{name}` | M | `If-Match` required → `200` with `ETag`; replaces the whole tool (it may be renamed) |
| DELETE | `/ai/tools/{name}` | M | `204`; its executions stay |
| POST | `/ai/tools/{name}/execute` | caller | body = the input object → `200` `ExecutionView` that ran, `202` waiting for approval |
| GET | `/ai/executions` | R | `200` `{content: [ExecutionView], nextCursor?}`; a manager reads all, anyone else their own |
| GET | `/ai/executions/{id}` | R | `200` `ExecutionView`; `404` for someone else's unless manager |
| POST | `/ai/executions/{id}/approve` | M | `200` `ExecutionView` that ran; `409` when not `PENDING_APPROVAL`, or the tool was deleted, disabled or changed |
| POST | `/ai/executions/{id}/reject` | M | body `{reason?}` (≤ 1000) → `200` `ExecutionView` `REJECTED`; `409` when not `PENDING_APPROVAL` |

**caller**: an authenticated member holding one of the tool's `allowedRoles`, or `MANAGE_ORGANIZATION`.

`ToolRequest` (POST and PUT, the whole tool every time): `{name, description, connectionId, action? | (resource,
operation), externalIdField?, inputSchema?, allowedRoles?, approvalRequired?, rateLimitPerMinute?, enabled?}`.

- `name` `^[a-z][a-z0-9_]{0,63}$`, unique in the tenant (`409` when taken); `description` 1–1000 characters.
- `connectionId` one of the tenant's connections whose connector is installed.
- Either `action`, a connector action the connector declares and implements (`ACTIONS`), with `inputSchema` required;
  or `resource` and `operation` `read | list | create | update | upsert | delete` (any case), which the connector
  declares and implements (`READ`, `WRITE`, `UPSERT`), on a resource it declares or discovers; `inputSchema` must be
  left out (it is derived). `upsert` needs `externalIdField`, an external id field of the resource. A write other than
  `delete` needs a connector with discovery. A resource the connector describes as missing is `400`; another provider
  failure is mapped as above.
- `allowedRoles` role names of the tenant (case-insensitive, stored upper case), at most 50; default empty: managers only.
- `approvalRequired` default `true` for actions, create, update, upsert and delete, `false` for read and list.
- `rateLimitPerMinute` 1–10000, default 60, per tool and per replica. `enabled` default `true`.

Every violation is `400` with its field (`allowedRoles[0]`, `inputSchema.properties.x.pattern`).

`ToolView`: `{id, name, description, connectionId, kind: ACTION | RESOURCE, action?, resource?, operation?,
externalIdField?, inputSchema, allowedRoles, approvalRequired, rateLimitPerMinute, enabled, version, createdAt,
updatedAt}`.

**Input schemas** are a JSON Schema subset: `type` (`object`, `string`, `number`, `integer`, `boolean`, `array`),
`properties`, `required`, `enum` (1–100), `minLength`, `maxLength`, `format` (`date`, `date-time`), `minimum`,
`maximum`, `items`, `minItems`, `maxItems`, `title`, `description` (`$schema` is accepted and dropped). The root is an
object; at most 8 deep and 32 KiB. Objects are closed: `additionalProperties` may only be `false`, and is added when
absent. Any other keyword is `400`, as is a property named like a credential (`password`, `secret`, `token`,
`apiKey`, `authorization`, `credential`, `cookie`, …). A record tool's derived input:

| Operation | Input |
|---|---|
| `read`, `delete` | `{id}` |
| `list` | `{cursor?, pageSize? 1–100 (20)}` |
| `create` | `{fields: {…}}`, the resource's required fields required |
| `update` | `{id, fields: {…}}` |
| `upsert` | `{externalId, fields: {…}}` (the external id field itself is not in `fields`) |

`fields` holds the resource's writable fields as typed properties (text → string, number, boolean, date and date-time
→ string with `format`, choice → string with `enum`, multi-choice → array, json → object); read-only, reference and
credential-like fields are left out. Outputs: `read` `{record: {id, version, modifiedAt, fields} | null}`, `list`
`{records, nextCursor}`, writes `{id, version, modifiedAt, created}`, `delete` `{id, deleted: true}`, an action its own
JSON.

**Execute** checks, in order: the tool exists in the caller's tenant (`404`), the caller may call it (`403`), it is
enabled and its connection `ACTIVE` (`409`), its rate limit (`429` with `Retry-After`), the body fits the schema
(`400`, `errors` with paths such as `input.fields.name`; an empty body is `{}`; at most 64 KiB). Then the execution is
recorded and either runs (`200`) or waits for a manager (`202`). A provider failure is not an HTTP error: the answer is
`200` with status `FAILED`, `error` (the mapped message) and `errorKind` (`rate_limited`, `auth_expired`, `not_found`,
`permission_denied`, `transient`, `conflict`, `permanent`, `unsupported`, `unexpected`, `cancelled`).

`ExecutionView`: `{id, toolId?, toolName, connectionId, callerId?, serviceAccount?, status: PENDING_APPROVAL | RUNNING |
SUCCEEDED | FAILED | REJECTED, input, output?, outputTruncated, error?, errorKind?, decidedBy?, decidedAt?, reason?,
correlationId?, createdAt, startedAt?, finishedAt?}`. The answers of `execute` and `approve` carry the provider's whole
`output`; a stored execution (list, get, reject) carries its summary: credential-like keys masked as `"***"`, at most
16 KiB, else a truncated string with `outputTruncated: true`.

`GET /ai/executions` filters `status`, `tool` (the name); pages newest first by `(createdAt, id)` with `limit` (1–200,
default 50) and `cursor` (the previous page's `nextCursor`; `400` for any other value).

### MCP endpoint

[ADR-0027](../adr/0027-mcp-endpoint.md). `POST /api/integrations/mcp`: the Model Context Protocol, revision
**2025-06-18**, streamable HTTP, stateless, JSON answers only. Bearer JWT like every route here (a person's, or a service
account's from `/api/auth/token`); none or a bad one is `401`. Each POST body is one JSON-RPC 2.0 message (no batches).

| Request | Answer |
|---|---|
| a request (`id`, `method`) | `200` `application/json`, one JSON-RPC response with the same `id` |
| a notification (no `id`, e.g. `notifications/initialized`) or a client's JSON-RPC response | `202`, no body |
| not JSON (`-32700`); not a JSON-RPC 2.0 object, a batch, a null id (`-32600`) | `400`, JSON-RPC error with `id: null` |
| `MCP-Protocol-Version` present and not `2025-06-18` | `400`, `-32600` (absent is accepted) |
| `Origin` present and not in `wasichai.integrations.ai.mcp.allowed-origins` | `403`, `-32001` |
| `GET` or `DELETE` | `405`, `Allow: POST` (no event stream, no session) |

No `Mcp-Session-Id` is issued; one sent is ignored.

| Method | Params | Result |
|---|---|---|
| `initialize` | `{protocolVersion, capabilities?, clientInfo?}` | `{protocolVersion, capabilities: {tools: {listChanged: false}}, serverInfo, instructions}` |
| `ping` | — | `{}` |
| `tools/list` | `{cursor?}` | `{tools: [{name, description, inputSchema, annotations}], nextCursor?}` |
| `tools/call` | `{name, arguments?}` | see below |

`initialize` echoes the client's `protocolVersion` when it is `2025-06-18`, else answers `2025-06-18`; `serverInfo` is
`{name: "wasichai-integrations", title, version}`. `tools/list` holds the enabled tools the caller may call, by name,
`page-size` per page; `annotations` are `{readOnlyHint, destructiveHint, openWorldHint}`, hints only.
Any other method is `-32601`; params that are not an object, or a missing `protocolVersion`, `name`, a non-object
`arguments`, a cursor not issued here: `-32602`.

`tools/call` runs `ToolExecutions.execute` (the policy of `POST /ai/tools/{name}/execute`, same execution row and audit).
Every result carries `_meta: {"dev.wasichai/executionId": "<id>"}`.

- Ran, `SUCCEEDED`: `{content: [{type: "text", text: "<output as JSON>"}], structuredContent: <output, when an object>, isError: false}`.
- Ran, `FAILED` (the provider refused): `{content: [{type: "text", text: "{executionId, status, error, errorKind}"}], isError: true}`.
- Waits for approval: `{content: [{type: "text", text: "Execution … waits for a person's approval …"}],
  structuredContent: {executionId, tool, status: "PENDING_APPROVAL", statusUrl: "/api/integrations/ai/executions/<id>"},
  isError: false}`. The outcome is read with `GET /ai/executions/{id}`; a manager approves over REST.

Refusals are JSON-RPC errors (HTTP `200`):

| Policy | Error |
|---|---|
| no such tool in the caller's tenant | `-32602` `Unknown tool: <name>` |
| arguments do not fit the schema | `-32602`, `data: {errors: [{field: "arguments.fields.name", message}]}` |
| no allowed role | `-32001` |
| disabled tool, connection not active | `-32002` |
| over the rate limit | `-32003`, `data: {retryAfter: <seconds>}` |
| unexpected | `-32603` |

## Not implemented yet

Planned in Phase 0 and absent from the code:

- `Idempotency-Key` on creates, and paged list envelopes (`{content, page, size, totalElements, …}`) outside `/events`.
- An `eventType` filter on `/events`; a keyset cursor on `/jobs`.
