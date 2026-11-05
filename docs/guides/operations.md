# Operations guide

What to watch and what to do when something fails: health, durable jobs, events, rate limits, retention, GDPR
requests, usage, metrics and logs. Paths
are relative to `/api/integrations` ([api.md](../architecture/api.md)). **R** is any member of the tenant, **M** needs
`MANAGE_ORGANIZATION` (`ADMIN` holds it). Every view is per tenant: operators of one organization never see another's.

## Health

`GET /health` (**R**) answers:

```json
{
  "connections": { "ACTIVE": 3, "NEEDS_REAUTH": 1, "DISABLED": 0, "ERROR": 0 },
  "failing": [
    { "id": "…", "connectorId": "salesforce", "name": "Salesforce", "status": "NEEDS_REAUTH", "health": "FAILING",
      "lastValidatedAt": "2026-11-03T09:00:00Z", "lastError": "authorization expired: invalid_grant", "rateLimitedUntil": null }
  ],
  "jobs": { "SUCCEEDED": 412, "DEAD": 2 }
}
```

- `connections` counts every status, zero included; `jobs` lists only the statuses that have jobs.
- `failing` holds connections that are not `ACTIVE`, or whose health is `DEGRADED` or `FAILING`. `rateLimitedUntil` is
  set while the replica that answered pauses the connection after a provider `429`.

A connection's status and health change in two places:

| Event | Status | Health |
|---|---|---|
| `POST /connections/{id}/test` succeeds (**M**) | `ACTIVE` | `HEALTHY` |
| the test fails with `AuthExpired` | `NEEDS_REAUTH` | `FAILING` |
| the test fails with another retryable error | unchanged | `DEGRADED` |
| the test fails with a non-retryable error | `ERROR` | `FAILING` |
| the provider refuses to refresh an OAuth token (any call; a `400`/`401` or a `2xx` naming an `error`) | `NEEDS_REAUTH` | `FAILING` |
| no configured key opens the stored credentials (any call, or the test) | `NEEDS_REAUTH` | `FAILING` |
| the account is connected again | `ACTIVE` | `HEALTHY` |

A failing sync run does not change the connection's health, by decision: health comes from tests, token refreshes and
credentials, while a run can fail for reasons that are not the connection's (a mapping, one record). Look at the
definition's status as well ([sync status](sync.md#status)).

A connection with `accountChecked: false` (its connector has no `Authenticator`) is never checked at the provider: a
successful test only means its credentials could be read back.

## Jobs

Durable work runs on a PostgreSQL queue (`integration_jobs`, [ADR-0008](../adr/0008-durable-jobs-in-postgresql.md)).
Kinds today: `integrations.event` (hand a stored event to its listeners) and `integrations.sync.run` (one sync run).

### Statuses and leases

`PENDING` → `RUNNING` → `SUCCEEDED` or back to `PENDING` (a retry) or `DEAD`.

- A worker claims up to `jobs.batch` due jobs every `jobs.poll-interval`, with `FOR UPDATE SKIP LOCKED`, and sets a lease
  of `jobs.lease`. A `RUNNING` job whose lease ran out is claimed again (its worker is taken to be gone). Only the
  holder of the current lease can finish a job, so a late worker cannot overwrite the outcome.
- Each claim counts an attempt. Jobs are created with 8 attempts at most.
- A job with a dedupe key is not queued twice while one is `PENDING` or `RUNNING` (one sync run per definition, one
  processing per event).
- Handlers run at least once and must be idempotent; the sync engine compares before it writes.

### Failures, backoff and `DEAD`

| Failure | `errorKind` | Outcome |
|---|---|---|
| `ConnectorException.RateLimited` | `rate_limited` | retry after the provider's `Retry-After`, else backoff |
| other retryable `ConnectorException` (`Transient`) | `transient` | retry with backoff |
| any other exception | `unexpected` | retry with backoff |
| `ConnectorException.AuthExpired` | `auth_expired` | `DEAD` at once |
| non-retryable `ConnectorException`, or a handler's `PermanentJobFailure` | `permanent` | `DEAD` at once |

Backoff is `jobs.backoff` doubled per attempt, capped at `jobs.max-backoff` (30 s, 1 min, 2 min, 4 min … 1 h). A
retryable failure on the last attempt makes the job `DEAD` too.

`GET /jobs` (**M**) lists jobs, most recently updated first, with filters `status`, `kind` and `limit` (1–500, default
50). Each `JobView` carries `attempts`, `maxAttempts`, `nextAttemptAt`, `lastError`, `errorKind`, `correlationId` and
`retryable`: false for `permanent` and `auth_expired`, which would fail the same way until the cause is fixed.

`POST /jobs/{id}/retry` (**M**) sends a `DEAD` job back to `PENDING` now, with its attempts reset to zero. Anything but a
`DEAD` job answers `409`. Fix the cause first (reconnect the account, correct the definition), then retry.

## Events and replay

Inbound webhook deliveries become events ([event model](../architecture/event-model.md)): stored with their processing
job in one transaction, deduplicated by provider event id (or body hash and position).

- `GET /events` (**R**) pages newest first: filters `connectionId`, `status`, `limit` (1–200, default 50), `cursor` from
  the previous page's `nextCursor`. `GET /events/{id}` shows one, with its payload.
- `status`: `RECEIVED` (waiting), `PROCESSED` (a listener acted: the sync queued runs), `IGNORED` (no active definition
  was concerned; kept, never dropped), `FAILED` (a listener threw; the job retries it and `error` says why).
- `POST /events/{id}/replay` (**M**) puts the event back to `RECEIVED` and queues it again; `409` when it is already
  waiting. Replay is safe: an event only queues sync runs, which read the providers and write nothing already in step.
  Use it after activating a definition that should have reacted, or after fixing a failure.

A webhook delivery refused for its signature answers `401`, is logged at info with the endpoint id and reason, and
stores nothing. A webhook endpoint becomes `ACTIVE` with its first accepted delivery; `lastDeliveryAt` shows the latest.

## Rate limits

- Each connection gets a token bucket from the connector's declared `rateLimit` (Notion 3 req/s, burst 6; Salesforce
  20 req/s, burst 20). A call waits for a token instead of failing.
- A provider `429` with `Retry-After` pauses the connection until then; health shows it as `rateLimitedUntil`. The
  connector raises `RateLimited`, the sync run fails and its job retries after the requested delay.
- Buckets and pauses are shared by every replica in `integration_rate_buckets`
  ([ADR-0030](../adr/0030-shared-rate-limits-and-kubernetes.md)): one upsert per provider call takes a token or
  reserves the next one, and the call waits for its slot with a coroutine delay. `rateLimitedUntil` shows a pause
  seen by any replica. AI tool limits use the same table and refuse with `429` instead of waiting.
- **Fallback**: when the database does not answer a rate-limit statement within 1 s, or fails, that call is paced by
  the replica's own in-memory bucket, `integrations.rate_limit.fallbacks` counts it, and the log says
  `Shared rate limits unavailable (...)` once per outage and `Shared rate limits answer again` when it recovers.
  Calls go on either way; while it lasts, n replicas may reach n times the rate. A pause is also kept in memory by the
  replica that saw the `429`.
- `wasichai.integrations.http.rate-limit.shared=false` keeps everything per replica, with no database work.
- To clear a pause by hand (the provider said `Retry-After` but the limit is lifted):
  `UPDATE <metadata schema>.integration_rate_buckets SET paused_until = NULL WHERE bucket_key = 'http:<connection id>'`.
- Salesforce also enforces an org-wide rolling 24-hour API allocation that this pacing does not track.

## Retention

A purge deletes finished history once it is older than its age ([ADR-0029](../adr/0029-retention-gdpr-usage.md)). It
runs every `retention.interval` (1 h) on one replica at a time (cluster lock), in batches of `retention.batch-size`
rows, at most `retention.max-batches` batches per task and run. Each module purges its own tables:

| Task (`retention.max-age.<task>`) | Default | Deleted | Never deleted |
|---|---|---|---|
| `events` | 30 days | processed, ignored and failed events, by `received_at` | events not processed yet (`RECEIVED`) |
| `jobs` | 30 days | `SUCCEEDED` and `DEAD` jobs, by `finished_at` | pending and running jobs |
| `usage` | 400 days | daily usage counters, by day | |
| `sync.runs` | 90 days | finished runs, by `finished_at` | the latest run of each definition and trigger, and its latest successful run |
| `sync.conflicts` | 180 days | resolved conflicts, by `resolved_at` | open conflicts; links and snapshots (kept while their definition exists) |
| `workflows.executions` | 90 days | succeeded, failed and cancelled runs with their steps, by `finished_at` | running and waiting runs (an approval) |
| `ai.executions` | 180 days | succeeded, failed and rejected tool executions, by `finished_at` | pending approvals and running executions |

`0` keeps a task's rows forever; `retention.enabled=false` stops the purge on a replica. Replay a failed event or
retry a dead job before its age passes: after that it is gone. Watch `integrations.retention.purged` (rows per task and
table) and `integrations.retention.failures` (a task that failed; the others still ran, the next run tries again).

## GDPR requests

A tenant administrator (`MANAGE_ORGANIZATION`) serves data-subject requests per connection
([api.md](../architecture/api.md#gdpr-export-and-erasure)):

- **Access / portability**: `GET /connections/{id}/export?page=0&size=200`, then `page=1`, … while `hasMore`. Every
  section is the stored rows of one table: the connection, its webhook endpoints and events, and what the sync,
  workflows and AI modules keep for it. Credentials and webhook secrets are never exported, only whether they are set.
- **Erasure of a whole account**: `POST /connections/{id}/erase`. It deletes, in one transaction, the connection, its
  credentials, events, webhook endpoints, every sync definition with a side on it (links, snapshots, cursors, runs,
  conflicts), every workflow naming it (versions, runs) and every AI tool on it with its executions, then revokes the
  OAuth token. It works while the connection is used; a plain `DELETE` still refuses (`409`). The answer counts the rows
  per table; the act is in Wasichai's `audit_log` (`integration_connection`).
- **Erasure of one person's record**: delete the record at the provider first, then
  `POST /connections/{id}/erase-record` `{resource, recordId}`. Events keyed by the record or carrying its id are
  deleted; sync links keep the paired record as `ORPHANED` without this side (the engine will neither re-create nor
  re-pair it; delete the paired record at the other provider yourself if the request covers it); finished workflow runs
  and AI tool executions carrying the id are deleted. Running workflow runs and pending tool executions are kept: run
  the request again once they finished. Wasichai's `audit_log` and job payloads (ids only) are not changed.

## Usage

`GET /usage?from&to&metric` (**R**) answers the tenant's daily counters: `provider.calls`, `sync.records`,
`workflow.steps`, `ai.tool_calls` (UTC days). Each replica adds its counts every `usage.flush-interval` (30 s) and when
it stops gracefully: the current day lags by up to that interval, and a replica killed without a shutdown loses at
most one interval. Counters are kept 400 days (`retention.max-age.usage`).

## Metrics

Meters go to the app's Micrometer `MeterRegistry` (add Spring Boot Actuator and a registry to export them; without a
registry bean the module keeps them in a private `SimpleMeterRegistry`). Tags never carry a tenant, url or record.

| Meter | Type | Tags | Records |
|---|---|---|---|
| `integrations.http.requests` | timer | `connector`, `method`, `status` (`2xx`, `4xx`, `5xx`…, or `io_error`) | every provider call through a connection |
| `integrations.rate_limit.waits` | timer | `source` (`shared`, `paused`, `local`) | time a provider call waited for its token or a pause |
| `integrations.rate_limit.fallbacks` | counter | `operation` (`reserve`, `try`, `pause`, `paused`, `forget`), `reason` (`timeout`, `error`) | rate-limit statements the database did not answer: limited per replica instead |
| `integrations.jobs` | timer | `kind`, `outcome` (`succeeded`, `retry`, `dead`) | every job attempt |
| `integrations.sync.runs` | counter | `kind` (trigger in lower case), `outcome` (`succeeded`, `partial`, `failed`) | every run attempt |
| `integrations.sync.records` | counter | `outcome` (`created`, `updated`, `skipped`, `failed`) | records per outcome |
| `integrations.sync.conflicts` | counter | | conflicts opened |
| `integrations.sync.reconcile` | counter | `finding` (`missing`, `diverged`, `cursor_reset`) | reconciliation findings |
| `integrations.workflows.executions` | counter | `trigger` (`event`, `webhook`, `schedule`, `manual`), `outcome` (`started`, `succeeded`, `failed`, `cancelled`) | workflow runs started and ended |
| `integrations.workflows.steps` | timer | `type` (step type), `outcome` (`succeeded`, `failed`, `retry`) | every step attempt that did work |
| `integrations.retention.purged` | counter | `task`, `table` | rows the retention purge deleted |
| `integrations.retention.failures` | counter | `task` | purge runs of a task that failed |

`kind` on `integrations.sync.runs` is one of `manual`, `schedule`, `event`, `initial`, `reconcile`.

Useful alerts: `integrations.jobs{outcome="dead"}` increasing; `integrations.sync.runs{outcome="failed"}` increasing;
`integrations.http.requests{status="4xx"}` (401/403/429 land there) or `io_error` rising for one connector;
`integrations.rate_limit.fallbacks` increasing (the database is slow or away, limits are per replica);
`integrations.sync.conflicts` rising after a change of mappings; `integrations.workflows.executions{outcome="failed"}`
increasing.

## Diagnosing failures

- **Connection `NEEDS_REAUTH`** (health `failing`, `lastError`): the refresh was refused or the token revoked;
  `lastError` "authorization expired: <code>" names the provider's refusal (`invalid_grant`, `invalid_refresh_token`…). Connect
  the same account again (it updates in place), then retry the dead jobs.
- **`lastError` "credentials cannot be decrypted with the configured keys; reconnect"** (log `credentials of
  connection ... cannot be opened`): the key they were sealed with was removed, or the row was damaged. Put the key
  back if it still exists; otherwise connect the account again (a connection with `accountChecked: false` is deleted
  and connected anew).
- **Run `FAILED` with `errorKind: permanent`** (definition status `lastError`, runs): the connection needs
  reauthorization, a connection or connector is gone, or the definition is no longer `ACTIVE`. Fix it, then run again.
- **Run `FAILED` with `errorKind: transient`** (runs, jobs): the provider is down, slow or rate limiting. The job retries
  by itself.
- **Run `PARTIAL`** (runs' `failed` count, logs): single records were refused (a provider validation error, a value it
  cannot store, a read-only field). Fix the data or the mapping.
- **Nothing syncs after a webhook** (events: `IGNORED`?, definition status): the definition is not `ACTIVE`, or the
  event names another resource. Activate it, then replay the event.
- **Webhook deliveries answer `401`** (log `webhook delivery ... refused`): a wrong signing secret, or a Notion
  endpoint that was already verified. Recreate the endpoint.
- **`pendingConflicts` above zero** (conflicts): fields changed on both sides under `MANUAL`, deletes under `FLAG`,
  interrupted creates without `externalIdField`. [Resolve](sync.md#resolving) them.
- **Links stuck `ORPHANED`** (status `links`): deletes under `IGNORE`, or `PROPAGATE` against the direction. They are
  not retried.
- **`cursorResets` above zero** (runs): the provider refused a checkpoint (too old); that side was read whole again.
  Nothing to do.
- **Activate answers `422`** (the violations): a field renamed or removed at the provider, or a type that no longer
  fits. Edit the mappings.
- **Discovery or validation answers `502`** (the problem `detail`): the provider failed (`PermissionDenied`,
  `Transient`, `Permanent`). Check the account's permissions at the provider.
- **Calls refused for a private address** (`lastError`, `PermissionDenied`): a provider url resolves to a private
  range; only `http.allow-private-addresses` (for tests) allows it.

`correlationId` on jobs and events, and Wasichai's `X-Correlation-Id` on every API response, tie a request, an event
and the work it caused together.

## Logs

The modules log under their class names, every line prefixed `integrations:`. They log ids, kinds and exception class
names, never secrets, tokens, credentials or record contents:

| Level | Line |
|---|---|
| info | `job {id} ({kind}) attempt {n} failed ({errorKind}), next at {time}` |
| warn | `job {id} ({kind}) is dead after {n} attempts: {errorKind}` |
| warn | `refresh refused for connection {id} ({oauth error code})` |
| warn | `code exchange refused for {connector} ({oauth error code})`, `oauth callback for {connector} failed: {exception}` |
| warn | `revoke for connection {id} failed ({exception})` (the connection is deleted or erased anyway) |
| info | `connection {id} erased with its derived data: {table counts}`, `a record of {resource} on connection {id} erased: {table counts}` |
| info / warn | `retention purged {n} rows of {table} older than {cutoff}`, `retention of {task} failed after {n} rows: {message}` |
| warn | `usage flush of {n} counters failed, kept for the next one: {message}`, `{n} usage counts were not written at shutdown` |
| info | `webhook delivery to endpoint {id} refused: {reason}` |
| warn | `sync {definition} record {id} of {side} failed: {exception}` |
| warn | `sync {definition} side {side} checkpoint refused (...); reading from the start` |
| warn | `sync {definition} link {link} ...; marked ORPHANED` |
| info | `sync {definition} link {link} recovered: ...`, `sync {definition} reconcile: record {id} of {side} is gone` |
| info | `workflow {definition} run {id} started by {trigger} [{correlationId}]`, `... run {id} {outcome} [...]` |
| info / warn | `workflow run {id} step '{step}' attempt {n} failed ({errorKind}); retried`, `... step '{step}' failed ({errorKind})` |
| warn | `workflow {definition} schedule failed to fire: {message}` |
| warn / error | `job drain failed`, `sync scheduler tick failed`, `workflow scheduler tick failed`, `retention tick failed`; `job worker stopped`, `sync scheduler stopped`, `workflow scheduler stopped`, `retention scheduler stopped`, `usage flusher stopped` |

Job and run errors (`lastError`, `error`) hold the exception class and message as the connector wrote it; connectors must
keep secrets and record contents out of their messages ([connector SDK](connector-sdk.md#connectorexception-taxonomy)).
Provider HTTP requests are not logged by the module.
