# ADR-0015: The sync engine's implementation refines links, runs, conflicts and reconciliation

**Status**: accepted · 2026-11-04 · refines [ADR-0009](0009-sync-semantics-and-conflicts.md) and
[ADR-0010](0010-loop-prevention.md), which stay as they are

## Context

ADR-0009 and ADR-0010 set the semantics of synchronization before any code existed. Building the engine
([#26](https://github.com/wasichai/integrations/pull/26)–[#28](https://github.com/wasichai/integrations/pull/28)) forced
decisions they leave open: what a snapshot holds, when a run exists, how many conflicts a pair may have, what happens to a
conflict when its pair is forgotten, which transforms may flow back, how a one-way definition treats drift on its target,
how reconciliation and stale cursors behave, and who starts scheduled runs. Some cases are knowingly left incomplete.

## Decision

- **The snapshot is the last agreed pair per mapping.** `integration_record_snapshots.values` maps each mapping key
  (`"<aField>-><bField>"`) to `{a, b}`: both sides' values as last agreed, not one canonical value. Lossy or one-way
  transforms (rounding, enum maps, statics) would otherwise make an untouched side look changed. A change that may not
  flow keeps its old entry, so it stays pending instead of being forgotten.
- **Runs start `QUEUED`, and the run id exists before its job.** The run row is inserted first and its id travels in
  the job payload, so `POST /run` answers `202 {runId}` at once. When the job's dedupe key (`sync:<definitionId>`) shows
  a run already queued or running, the new row is dropped and the call is `409`.
- **One open conflict per link, of kind `FIELDS` or `DELETED`.** A unique index on `link_id` where `status = 'OPEN'`
  keeps a held pair from piling up conflicts; the link is in `CONFLICT` (fields) or `ORPHANED` (a delete under `FLAG`)
  and is not processed until a person resolves it.
- **A resolved conflict outlives its link.** `link_id` is `ON DELETE SET NULL`. Resolving a `DELETED` conflict closes it
  first and then deletes the link (`resolution.choice = "UNLINK"`): the conflict stays as history with no link.
- **Transforms without an inverse need owner A.** `static` and `conditional` only flow A → B; a mapping using them must
  be owned by A in a definition that is not `B_TO_A`. The others carry an inverse used for B → A.
- **A target-side drift on a one-way definition is counted and never silently overwritten.** Reconciliation finds it
  (`diverged`) and merges again, but the side that does not flow may not carry its value back, so its mapping keeps the
  old snapshot entry; the target changes only when the source changes that field and the owner rule decides.
- **`RECONCILE` reads both sides whole without moving cursors**, including a side that never flows. Links not settled
  during the run are then checked: a linked record no longer seen is `missing` and the delete policy applies; drift is
  `diverged`.
- **A permanent change-feed error on a stored cursor resets that side once.** The cursor is cleared, the side is read
  from the start (counted in `cursorResets`) and fed to the same missing and drift checks; a second permanent error, or
  one without a cursor, fails the run.
- **The scheduler is platform background work across tenants.** `SyncScheduler` reads the ACTIVE definitions of every
  organization (no `CurrentUser`) under the cluster lock `integrations.sync.scheduler` and enqueues each run in its own
  organization through the engine, so one run per definition still holds; a reconcile goes before an incremental run.

## Known limitations

- A create interrupted after its `PENDING_CREATE` link is marked `ORPHANED` on the next run, never retried, until
  [#32](https://github.com/wasichai/integrations/issues/32) recovers creates through the create token or upsert.
- Resolving with B takes B's value through the inverse transform and back, which a lossy transform may alter, until
  [#33](https://github.com/wasichai/integrations/issues/33).
- Provider rate limits are per replica (ADR-0008): n replicas may reach n times the rate until
  [#47](https://github.com/wasichai/integrations/issues/47) makes them cluster-wide.

## Consequences

- The UI can show a run as soon as it is requested, and a conflict's history after its pair is gone.
- Operators read reconciliation findings on runs and on `/sync-definitions/{id}/status` instead of logs.
- Orphaned links need an operator until #32 lands.
