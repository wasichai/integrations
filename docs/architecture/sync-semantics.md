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
6. For a field changed only here: it flows if this side may write it (owner is this side or `BOTH`); otherwise it keeps
   its old snapshot entry and stays pending, never forgotten.
7. For a field changed on both sides to different values (values equal through the transform are no clash), the
   owner decides: a field owned by `A` or `B` takes that side's value. A `BOTH` field (bidirectional definitions only)
   applies its `conflictStrategy` (`MergePlanner`):
   - `MANUAL` (the default) — a conflict row is stored, the link goes to `CONFLICT`, **nothing is written** for that
     pair until a person resolves it;
   - `A_WINS` / `B_WINS` — that side's value is kept;
   - `SOURCE_WINS` — the value of the side whose change is being processed is kept;
   - `LAST_WRITE_WINS` — the record with the later `modifiedAt` wins (the source on a tie). It is not refused at
     validation: when either side has no `modifiedAt` (the connector reports none it can rely on), the field becomes a
     conflict at run time, as with `MANUAL`.

   A winner whose value may not flow to the other side also leaves a conflict.
8. Write the target with the expected version when the connector supports conditional writes; a `Conflict` error
   re-reads both sides and merges once more, and a second `Conflict` opens a conflict for a person.
9. In one transaction: new versions, the written hash, the new snapshot, the run counters.

## Creates without duplicates

Before calling the provider, a `PENDING_CREATE` link with a random create token is committed. The target side's
`externalIdField` says where the token goes ([#32](https://github.com/wasichai/integrations/issues/32)):

- **upsert** — the connector implements upsert (Salesforce): every create is an upsert keyed by the token;
- **marker** — any other connector: the create writes the token into that writable text field (Notion: a property);
- **none** — no field set: the token stays in the platform only.

Every run starts with the links still `PENDING_CREATE` (a create cut short by a crash or a timeout): upsert writes again
with the same token, which is idempotent; marker lists the target and links the record that carries the token, then
carries what changed since, or creates it again with the token when none does; none marks the link `ORPHANED` and opens
an `ORPHANED_CREATE` conflict, because nothing can tell whether the record exists. Resolving that conflict unlinks the
pair. A recovery the provider refuses is treated as none: the first attempt may have succeeded.

## Deletes

Per definition `deletePolicy` (`SyncEngine`), when a linked record is deleted on one side:

- `FLAG` (the default): the link becomes `ORPHANED` and a `DELETED` conflict is opened for a person; resolving it
  unlinks the pair.
- `PROPAGATE`: when the direction lets the deleted side flow, the other record is deleted through the connector (Notion
  moves the page to the trash; already gone counts as done) and the link is removed. Against the direction it behaves
  as `IGNORE`.
- `IGNORE`: the link becomes `ORPHANED` and the other record stays; no conflict is opened.

Deletes come from provider feeds where they exist (Salesforce `deleted`), from a read that finds the record gone
(Notion archived or trashed pages) and from reconciliation.

## Reconciliation

On a schedule and on demand: list both sides fully (or by window), compare each linked pair's mapped hash with the
snapshot, find records with no link and links with no record, and feed differences through the same pipeline. It
catches missed webhooks, stale or expired cursors and edits that never produced an event.

## Guarantees

- At-least-once processing, idempotent effects: duplicates and replays write nothing new.
- No silent overwrite of a concurrent change: a field changed on both sides is resolved by a declared rule or stored as
  a conflict.
- No loops: echoes are detected by content hash and causation ids, never by the name of the user who made the change.
