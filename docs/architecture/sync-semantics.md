# Synchronization semantics

A sync definition pairs a resource on connection A with a resource on connection B, a direction (A→B, B→A or
bidirectional) and a list of field mappings. Decisions are in [ADR-0009](../adr/0009-sync-semantics-and-conflicts.md)
and [ADR-0010](../adr/0010-loop-prevention.md).

## Field mappings

- A mapping names one field on each side, a transform, an **owner** (`A`, `B` or `BOTH`) and a **conflict strategy**.
- Transforms are a closed set, applied in both directions where invertible: `copy`, `static`, `default`, `enumMap`,
  `dateTime` (zone and format), `number` (scale), `conditional` (equality on a source value). No expressions, no code.
- Validation checks both fields exist in the discovered schemas, types are compatible, the target field is writable,
  and owners agree with the direction (a field owned by B in an A→B definition is an error).
- Similar names imply nothing: every mapping is explicit.

## State per pair

- **Record link**: (definition, A id, B id), last seen versions, the hash of the values we last wrote to each side,
  state `LINKED`, `PENDING_CREATE`, `CONFLICT` or `ORPHANED`.
- **Snapshot**: the mapped field values both sides last agreed on, and their hash. It is the base of every diff.
- **Cursor** per side: the provider's incremental position (timestamp, token), saved after a batch commits.

## Per change

1. Read the current record (the change is a signal, [event model](event-model.md)).
2. Find the link. None: a new record; go to *create*.
3. Map the record's values to the snapshot's shape and hash them. If the hash equals the hash we last wrote to this
   side, it is our own echo: update versions and stop.
4. Diff against the snapshot: fields changed on this side.
5. Read the other side's current values (or its last seen version) and diff them against the snapshot: fields changed
   there since the last sync.
6. For a field changed only here: it flows if this side may write it (owner is this side or `BOTH`).
7. For a field changed on both sides to different values, apply its strategy:
   - `OWNER_WINS` — the owner's value is kept (the default when the owner is one side);
   - `SOURCE_WINS` / `TARGET_WINS` — fixed per definition direction;
   - `LAST_WRITE_WINS` — only when both connectors declare reliable modification timestamps for the resource;
     otherwise refused at validation;
   - `MANUAL` — a conflict row is stored, the link goes to `CONFLICT`, **nothing is written** for that pair until a
     person resolves it.
8. Write the target with the expected version when the connector supports conditional writes; a `Conflict` error
   re-reads and restarts at step 4 (bounded).
9. In one transaction: new versions, the written hash, the new snapshot, the run counters.

## Creates without duplicates

Before calling the provider, a `PENDING_CREATE` link with a random create token is committed. The connector writes the
token where the provider allows (Salesforce: an external-ID field and upsert; Notion: a hidden property), so a retry
after a timeout finds the record instead of creating another. A pending link older than a threshold is resolved by that
lookup during reconciliation.

## Deletes

Per definition `deletePolicy`: `PROPAGATE` (delete or archive the other side), `IGNORE` (unlink), `FLAG` (link
`ORPHANED`, shown in the operational view). Deletes come from provider feeds where they exist (Salesforce `deleted`,
Notion archived/trashed pages) and from reconciliation.

## Reconciliation

On a schedule and on demand: list both sides fully (or by window), compare each linked pair's mapped hash with the
snapshot, find records with no link and links with no record, and feed differences through the same pipeline. It
catches missed webhooks, stale or expired cursors and edits that never produced an event.

## Guarantees

- At-least-once processing, idempotent effects: duplicates and replays write nothing new.
- No silent overwrite of a concurrent change: a field changed on both sides is resolved by a declared rule or stored as
  a conflict.
- No loops: echoes are detected by content hash and causation ids, never by the name of the user who made the change.
