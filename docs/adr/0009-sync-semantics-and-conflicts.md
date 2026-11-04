# ADR-0009: Synchronization keeps links and snapshots, diffs per field, and never overwrites a conflict silently

**Status**: accepted · 2026-11-04 · details in [sync semantics](../architecture/sync-semantics.md)

## Context

Bidirectional sync is where integration products lose data: concurrent edits, clocks that cannot be trusted, retries
that duplicate records. Among the products studied, only end-user sync tools document conflict handling.

## Decision

- Durable `record_links` per definition pair the two record ids (never display names) and keep versions and written
  hashes; `record_snapshots` keep the last agreed values, the base of a three-way diff per field.
- Each mapping has an owner (A, B, both) and a conflict strategy: owner wins, source wins, target wins, last write wins
  (only when both sides declare reliable timestamps), manual. Manual stores a `sync_conflicts` row and holds the link.
- Writes use the provider's version or ETag where the connector supports conditional writes; a version conflict re-reads.
- Creates commit a pending link with a create token first; the token goes into an external-id field or equivalent, so a
  retried create finds the record.
- Reconciliation on a schedule compares hashes and finds missing, deleted and divergent records; a per-definition
  delete policy (propagate, ignore, flag) applies.

## Consequences

- Correctness depends on connectors reporting versions honestly; the manifest says which do.
- A held link stops that pair only; the rest of the definition keeps syncing.
