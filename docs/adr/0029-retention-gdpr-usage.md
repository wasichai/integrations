# ADR-0029: Retention, GDPR export and erasure, and usage metering are core SPIs each module fills for its own tables

**Status**: accepted · 2026-11-05 · builds on [ADR-0001](0001-repositories-and-module-boundaries.md),
[ADR-0005](0005-secret-management.md), [ADR-0007](0007-event-envelope-and-webhooks.md),
[ADR-0011](0011-multi-tenancy-and-permissions.md)

## Context

[#46](https://github.com/wasichai/integrations/issues/46) asks for three things that span every module: a purge of
finished history (events, jobs, sync runs, resolved conflicts, workflow runs, AI tool executions), GDPR export and
erasure of what is stored for a connection or for one provider record, and per-tenant daily usage counters. The core
(`wasichai-integrations`) must not know the tables of sync, workflows or AI (ADR-0001): they are optional modules that
depend on it, never the other way. Deleting a connection is refused (`409`) while something uses it
(`ConnectionUsage`), which is the right default and blocks an erasure.

## Decision

### One SPI per concern, in the core; each module contributes beans for its own tables

- **`RetentionTask`**: `name` (the property key), `table` (the metric tag), `defaultMaxAge`, and
  `purge(cutoff, limit): Long`. `SqlRetentionTask` is the one implementation used so far: a single `DELETE … WHERE id IN
  (SELECT … ORDER BY … LIMIT :limit FOR UPDATE SKIP LOCKED)`. PostgreSQL has no `DELETE … LIMIT`; the sub-select keeps
  each statement short and skips rows a worker holds.
- **`ExportContributor`**: `export(org, connectionId, offset, limit)` → section name → rows.
- **`ErasureContributor`**: `eraseConnection(org, connectionId)` and `eraseRecord(org, connectionId, resource,
  recordId)` → table → rows deleted or changed.
- **`UsageMeter`**: a core bean with `record(org, metric, amount)`; modules call it on their own code paths with their
  own metric names. The core names only its own metric (`provider.calls`).

The core collects every bean of each type (`ObjectProvider`), so a module that is switched off simply contributes
nothing. Two SPIs for export and erasure (not one) because a module could export without erasing (a read model) and
the two have different transaction needs; both have four users today.

### Retention

- **One core scheduler** (`RetentionScheduler`, a `SmartLifecycle`) runs every task each `retention.interval` (1 h,
  first run after `retention.initial-delay`, 5 min) under the cluster lock `integrations.retention`, like
  `SyncScheduler` and `WorkflowScheduler`: one replica per tick, ticks dropped while one runs. Each task runs in
  batches of `retention.batch-size` (1000) until a batch comes back short or `retention.max-batches` (100) were run;
  the next tick goes on. A failing task is logged and counted; the others still run.
- **Ages by task name**, `wasichai.integrations.retention.max-age.<name>`, so one property namespace covers every
  module without the core naming their tables. `0` keeps forever. Defaults:

  | Task | Table | Default | Deletes |
  |---|---|---|---|
  | `events` | `integration_events` | 30 days | events not `RECEIVED` (processed, ignored, failed) by `received_at` |
  | `jobs` | `integration_jobs` | 30 days | `SUCCEEDED` and `DEAD` jobs by `finished_at` |
  | `usage` | `integration_usage_daily` | 400 days | counter rows by `day` |
  | `sync.runs` | `integration_sync_runs` | 90 days | finished runs by `finished_at`, except the latest run of each definition and trigger and the latest successful run (the scheduler and the status read them) |
  | `sync.conflicts` | `integration_sync_conflicts` | 180 days | `RESOLVED` conflicts by `resolved_at` |
  | `workflows.executions` | `integration_workflow_executions` | 90 days | `SUCCEEDED`, `FAILED`, `CANCELLED` runs by `finished_at`, their steps by cascade |
  | `ai.executions` | `integration_ai_tool_executions` | 180 days | `SUCCEEDED`, `FAILED`, `REJECTED` executions by `finished_at` |

- **Never purged**: an event not processed yet, a pending or running job, an open conflict, a link or snapshot (they
  live as long as their definition), a running or waiting workflow run, a pending approval, a running tool execution.
  A purged `FAILED` event or `DEAD` job had the whole retention age to be replayed or retried; the purge is a
  configured, counted policy (`integrations.retention.purged{task,table}`), not a silent discard (rule 5).
- Retention is global by age, across tenants (platform background work, no `CurrentUser`), like the schedulers.

### GDPR export

- `GET /api/integrations/connections/{id}/export?page&size`, `MANAGE_ORGANIZATION`, the caller's tenant only (`404`).
- **Rows as stored**: each section is `to_jsonb(row)` of the table, snake_case columns, so nothing stored is left out by
  a hand-written view. Secrets are removed in SQL and replaced by a flag: a webhook endpoint's `secret_sealed` becomes
  `secret_set`, a workflow's `webhook_token_hash` becomes `webhook_token_set`. Credentials are never read into the
  export: `credentials` holds only booleans (`stored`, `readable`, `accessToken`, `refreshToken`, `apiKey`,
  `username`, `password`) and `accessExpiresAt`. Provider configs (the tenant's OAuth apps) are not connection data.
- **Paged per section**, `size` rows (default `export.page-size` 200, at most `export.max-page-size` 1000) of every
  section per page, in a stable order (by id); `hasMore` says whether any section goes on. A page is built in memory:
  its size is bounded by sections × size × row size, and an event payload is at most `webhooks.max-body-bytes`. Offset
  paging over live data may shift if rows are purged between pages; an export is a snapshot taken page by page, not a
  transaction. A streamed format (NDJSON) is left until a client needs exports beyond what paging gives.
- Sections: core `webhookEndpoints`, `events`; sync `syncDefinitions` (either side on the connection),
  `recordLinks`, `recordSnapshots`, `syncCursors`, `syncRuns`, `syncConflicts`; workflows `workflows` (draft, any version
  or trigger naming the connection), `workflowVersions`, `workflowTriggers`, `workflowExecutions`,
  `workflowStepExecutions`; AI `aiTools`, `aiToolExecutions`.

### Erasure of a connection: a separate route, plain `DELETE` keeps its `409`

- **`POST /api/integrations/connections/{id}/erase`** (not `DELETE ?purge=true`): an erasure deletes far more than the
  resource named by the url, works while the connection is used, and is a different, audited act; a query flag on
  `DELETE` would make a typo or a proxy that drops the query the difference between `409` and losing every sync
  definition. Plain `DELETE` still answers `409` while used.
- **One transaction**: every `ErasureContributor` deletes its rows (children first, so each table's count is
  reported), then the core deletes events, webhook endpoints and the connection (its credentials by cascade), and
  writes one Wasichai `audit_log` entry (`integration_connection`, `DELETE`, the connector, name and provider account
  before, the per-table counts after). Either everything goes or nothing does.
- **What stands on the connection goes whole**: a sync definition with either side on it (its links, snapshots,
  cursors, runs and conflicts, open ones included), a workflow whose draft, any version or trigger names it (versions,
  trigger and runs, a running one included: its pending step jobs then find nothing and end), an AI tool on it and every
  execution against it (pending approvals included). The connection on the other side of a sync definition stays.
- **Then the token is revoked**, best effort, with the credentials read before the transaction: the data is gone
  whether or not the provider answers; `revoked` in the answer says whether a revocation was sent.
- Jobs are not searched: their payloads hold ids only (definition, run, event, execution), and each handler ends
  quietly when its row is gone.

### Erasure of one provider record

`POST /api/integrations/connections/{id}/erase-record` `{resource, recordId}`, `MANAGE_ORGANIZATION`, one transaction,
audited (`integration_connection_record`; the record id itself is not written to the audit log).

- **Events: rows are deleted, not redacted.** An event of the connection whose `resource_id` is the record (and whose
  `resource_type` is the resource or unknown), or whose payload carries the id as any string value
  (`jsonb_path_exists($.** ? (@ == $id))`), is deleted whole. Redacting would keep an envelope whose `resource_id` is
  itself the personal datum. The payload scan reads the connection's events only.
- **Sync: the record's side is forgotten, the pair is kept as `ORPHANED`.** For every link holding the record on a side
  whose definition stands on this connection and resource: its conflicts (open or resolved; their fields hold values)
  and its snapshot (values of both sides) are deleted; when the other side's id is set, the link stays with this side's
  id, version and hash cleared and state `ORPHANED`, so the engine neither creates the paired record again on this side
  nor pairs it with anything (non-`LINKED` links are skipped); a link holding only this side is deleted. The paired
  record at the other provider is never touched. The record should be deleted at the provider first: if it is still
  there, the next run reads it as new.
- **Workflows**: a finished run of a workflow naming the connection whose trigger payload or any step input or output
  carries the id as a value is deleted with its steps. A run still going is left alone; definitions are not changed.
- **AI**: a finished execution against the connection whose input or output carries the id is deleted; a pending or
  running one is left alone.
- **Not erased**: Wasichai's `audit_log` (its own retention), job payloads (ids only), values a record shares with
  others in snapshots of other links, and data outside these modules.

### Usage metering

- Table `integration_usage_daily (organization_id, day, metric, count)`, primary key `(organization_id, day, metric)`,
  UTC days. Metrics: `provider.calls` (every HTTP exchange through `ConnectionHttp`, a retry after a refresh too),
  `sync.records` (records a run created, updated or deleted at a provider), `workflow.steps` (steps that ran to success
  or failure; retries are not steps of their own), `ai.tool_calls` (tool executions that ran, after approval when one
  was needed).
- **Buffered in memory, flushed periodically**: `record` only merges into a `ConcurrentHashMap` (atomic per key), so hot
  paths never wait for the database and never block. `UsageFlusher` writes the sums every `usage.flush-interval`
  (30 s) as `INSERT … ON CONFLICT DO UPDATE SET count = count + EXCLUDED.count` (replicas add, never overwrite) and
  once more on shutdown (bounded at 10 s, on the shutdown thread, not a reactive one). A failed write puts its counts
  back. A replica that crashes loses at most one interval of counts: usage is for insight and quotas, not billing to the
  unit.
- `GET /api/integrations/usage?from&to&metric`: any member, the caller's tenant only; default the last 30 days, at
  most 366.

## Consequences

- A new module adds retention, export and erasure by declaring beans; the core and the API do not change.
- Export rows are the storage shape: a migration that renames a column changes the export. That is acceptable for a
  data-subject export (it shows what is stored) and is why the UI does not render it field by field.
- Erasing a connection is irreversible and wide; the UI must name what goes (the `409` text of a plain delete lists
  it) before calling it.
- The usage table is not a ledger: counts are approximate across crashes.
