# REST API

The contract with [integrations-ui](https://github.com/wasichai/integrations-ui). Routes exist only when the module is
on the classpath and `wasichai.integrations.enabled` (or `wasichai.integrations-sync.enabled`) is not false; otherwise
they answer `404` to an authenticated caller, as in [Wasichai](https://github.com/wasichai/wasichai/blob/main/docs/api/rest.md).

## Conventions (inherited from Wasichai)

- Base `/api/integrations`; Bearer JWT except the two public routes.
- Errors: RFC 7807 `application/problem+json` `{type, title, status, detail, errors: [{field, message}]}`.
- `X-Correlation-Id` on every response; stored on jobs, events and runs.
- Lists: `{content, page, size, totalElements, totalPages, nextCursor}` with `page`, `size` (max 200) and `after` for
  keyset reads; event and job lists are keyset only (`after`, `limit` ≤ 500).
- Updates take `If-Match` (`412` on a stale version); creates take `Idempotency-Key`.
- Permission column: **R** = authenticated member, **M** = `ADMIN` or `MANAGE_ORGANIZATION`, **P** = public.

## Catalog

| Method | Path | Perm | Body / answer |
|---|---|---|---|
| GET | `/connectors` | R | `[{id, provider, name, version, sdkVersion, authMethods, capabilities: {name: maturity}}]` |
| GET | `/connectors/{id}` | R | full manifest (resources, actions, scopes, rate limits, webhook support) |
| GET | `/connectors/{id}/capabilities` | R | `{capabilities: {authentication, discovery, read, write, webhooks, incremental, …: maturity}}` |

## Provider configs

| Method | Path | Perm | Body / answer |
|---|---|---|---|
| GET | `/provider-configs` | M | `[{id, connectorId, clientId, clientSecretSet: true, redirectUri, scopes, version}]` |
| PUT | `/provider-configs/{connectorId}` | M | `{clientId, clientSecret?, redirectUri, scopes}`; secret write-only |
| DELETE | `/provider-configs/{connectorId}` | M | `204`; `409` while connections use it |

## Connections

| Method | Path | Perm | Body / answer |
|---|---|---|---|
| GET | `/connections` | R | page of `{id, connectorId, name, providerAccountId, status, authType, scopes, health, lastValidatedAt, lastError}` |
| POST | `/connections` | M | `{connectorId, name, authType: API_KEY\|BASIC\|CLIENT_CREDENTIALS, credentials: {…}}` → `201` connection |
| POST | `/connections/oauth/start` | M | `{connectorId, name, returnTo}` → `{authorizationUrl}` |
| GET | `/oauth/callback` | P | `?code&state` → `303` to `returnTo?connection=<id>` or `?error=<code>` |
| GET | `/connections/{id}` | R | connection |
| DELETE | `/connections/{id}` | M | `204`; revokes at the provider when supported; `409` while an active definition uses it |
| POST | `/connections/{id}/test` | M | `{ok, checkedAt, error?: {kind, message}}` |
| GET | `/connections/{id}/resources` | R | `[{name, label, capabilities}]` |
| GET | `/connections/{id}/resources/{resource}/schema` | R | `{name, fields: [{name, label, type, required, readOnly, externalId, options?, relationship?}]}` |

## Webhooks and events

| Method | Path | Perm | Body / answer |
|---|---|---|---|
| POST | `/webhooks/{connectorId}/{endpointId}` | P | raw provider body → `200` (also for duplicates), `401` bad signature, `404` unknown |
| GET | `/events` | R | keyset list of Event Envelopes; filters `connectionId`, `status`, `eventType` |
| POST | `/events/{id}/replay` | M | `202` |

## Jobs and health

| Method | Path | Perm | Body / answer |
|---|---|---|---|
| GET | `/jobs` | R | keyset list `{id, kind, status, attempts, nextAttemptAt, lastError, correlationId}` |
| POST | `/jobs/{id}/retry` | M | `202`; only `FAILED` or `DEAD` jobs, else `409` |
| GET | `/health` | R | `{connections: [{id, status, health, lastValidatedAt, lastError}], jobs: {pending, failed, dead}}` |

## Synchronization

| Method | Path | Perm | Body / answer |
|---|---|---|---|
| GET | `/sync-definitions` | R | page of definitions |
| POST | `/sync-definitions` | M | `{name, a: {connectionId, resource}, b: {…}, direction, schedule?, reconcileSchedule?, deletePolicy, mappings: [{aField, bField, transform, owner, conflictStrategy}]}` → `201` (status `DRAFT`) |
| GET / PUT | `/sync-definitions/{id}` | R / M | definition; PUT with `If-Match` |
| POST | `/sync-definitions/{id}/validate` | M | `{valid, errors: [{field, message}]}` |
| POST | `/sync-definitions/{id}/activate` · `/deactivate` | M | definition |
| POST | `/sync-definitions/{id}/run` | M | `{kind: INCREMENTAL\|INITIAL\|RECONCILE}` → `202 {runId}` |
| GET | `/sync-definitions/{id}/runs` | R | page of runs `{id, kind, status, startedAt, finishedAt, counts, error}` |
| GET | `/sync-definitions/{id}/status` | R | `{pending, lastSuccessAt, lastError: {kind, transient, message}, openConflicts}` |
| GET | `/conflicts` | R | page `{id, definitionId, linkId, fields: [{field, a, b, base}], detectedAt, status}` |
| POST | `/conflicts/{id}/resolve` | M | `{choices: {field: A\|B\|VALUE}, values?}` → resolved conflict |

## Errors specific to this API

`409` connection or provider config in use, job not retryable, conflict already resolved; `422` invalid mapping or
definition (with field violations). A provider's failure is never answered as a `5xx` of ours: on `test` it is part of
the answer body, on a run it is the run's `error`.
