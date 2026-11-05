# Synchronization guide

How to keep records the same between two connections with `wasichai-integrations-sync`: definitions, mappings,
conflicts, creates, deletes, reconciliation and scheduling. The REST contract is in
[api.md](../architecture/api.md#synchronization); the reasoning is in [ADR-0009](../adr/0009-sync-semantics-and-conflicts.md),
[ADR-0010](../adr/0010-loop-prevention.md) and [ADR-0015](../adr/0015-sync-implementation-decisions.md). Paths below are
relative to `/api/integrations`.

Who may do what: any member of the tenant reads definitions, runs, status and conflicts; creating, changing, validating,
activating, running, deleting definitions and resolving conflicts needs `MANAGE_ORGANIZATION` (`ADMIN` holds it).

## Definitions

A sync definition pairs a resource of one connection (side **A**) with a resource of another (side **B**), a direction
and a list of field mappings.

```json
{
  "name": "Accounts: Notion ↔ Salesforce",
  "a": { "connectionId": "<notion connection id>", "resource": "<notion data source id>", "externalIdField": "Sync key" },
  "b": { "connectionId": "<salesforce connection id>", "resource": "Account", "externalIdField": "Wasichai_Key__c" },
  "direction": "BIDIRECTIONAL",
  "deletePolicy": "FLAG",
  "scheduleSeconds": 300,
  "reconcileSeconds": 86400,
  "matchOn": { "aField": "Name", "bField": "Name" },
  "mappings": [
    { "aField": "Name", "bField": "Name" },
    { "aField": "Industry", "bField": "Industry", "transform": { "type": "enumMap", "map": { "Energy": "Energy", "Retail": "Retail" } } },
    { "aField": "Employees", "bField": "NumberOfEmployees", "transform": { "type": "number", "scale": 0 }, "owner": "B" }
  ]
}
```

| Field | Rules |
|---|---|
| `name` | 1–200 characters |
| `a`, `b` | `connectionId` of the tenant's own connection, `resource` (≤ 300) as discovery names it, optional `externalIdField` |
| `direction` | `A_TO_B`, `B_TO_A` or `BIDIRECTIONAL` |
| `deletePolicy` | `PROPAGATE`, `IGNORE` or `FLAG` (default) |
| `scheduleSeconds` | optional, ≥ 60: incremental runs by the scheduler |
| `reconcileSeconds` | optional, ≥ 300: reconcile runs by the scheduler |
| `matchOn` | optional `{aField, bField}`: pair existing records with equal values instead of creating them |
| `mappings` | 1–200; a field of A, or of B, is mapped at most once |

Lifecycle:

1. `POST /sync-definitions` stores it as `DRAFT`. Create and replace check the shape only (`400` with violations such as
   `mappings[2].owner`); no provider is called.
2. `POST /sync-definitions/{id}/validate` reads both sides' schemas and answers `{valid, errors}`.
3. `POST /sync-definitions/{id}/activate` validates the same way and makes it `ACTIVE`, or answers `422` with the
   violations.
4. `PUT /sync-definitions/{id}` needs `If-Match: "<version>"` (`412` without it or when stale); an `ACTIVE` definition
   goes back to `DRAFT` and must be activated again.
5. `POST /sync-definitions/{id}/deactivate` makes it `PAUSED`: no run starts, nothing is forgotten.
6. `DELETE /sync-definitions/{id}` deletes it with its links, snapshots, cursors, runs and conflicts. Records already
   written stay where they are. A connection used by a definition cannot be deleted (`409`) until the definition is.

Validation against the schemas checks that: both connectors can describe and read, and the side being written can write;
the resource exists; every mapped field exists; a field that is written is not read-only; types fit through the
transform; `enumMap` values are options of their field (when the field lists options); `matchOn` fields exist; an
`externalIdField` fits its side ([below](#create-modes-and-externalidfield)). A provider failure during validation is an
HTTP error ([api.md](../architecture/api.md#provider-failures-inside-an-api-call)), not a violation.

## Sides and direction

`A_TO_B` carries changes from A to B only, `B_TO_A` the reverse, `BIDIRECTIONAL` both ways. A side that never flows is
still read by a reconcile run, so drift there is put back from the other side.

## Mappings

```json
{ "aField": "Industry", "bField": "Industry", "transform": { "type": "copy" }, "owner": "BOTH", "conflictStrategy": "MANUAL" }
```

Every mapping is explicit: similar names imply nothing. A mapping's key, used in snapshots and conflicts, is
`"<aField>-><bField>"`.

### Transforms (the closed set)

A transform is written A → B; the inverse (B → A) applies where one exists. There are no expressions and no code.

| `type` | Keys | A → B | B → A | Field types (A, B) |
|---|---|---|---|---|
| `copy` (default) | | the value | the value | the same type; `TEXT`/`CHOICE` mix; `JSON` with anything |
| `static` | `value` | always `value` | none | any |
| `default` | `value` | `value` when A is null | the value | as `copy` |
| `enumMap` | `map` `{aValue: bValue}`, non-empty | mapped value, unmapped ⇒ null | first A value naming the B value | `TEXT` or `CHOICE` |
| `dateTime` | `zone` (default `UTC`), `a`, `b` (`"DATE"` or not) | date ↔ date-time in the zone | the same, back | `DATE` or `DATETIME` |
| `number` | `scale` 0–12 | rounded half up to `scale` | the value | `NUMBER` or `TEXT` → `NUMBER` |
| `conditional` | `equals`, `then`, `else` | `then` when A equals `equals`, else `else` | none | any |

`static` and `conditional` have no inverse: such a mapping must be owned by `A` in a definition that is not `B_TO_A`.
Values in `value`, `equals`, `then`, `else` are plain JSON (a string is text, a number a number, an array a multi-choice) or
typed `{"t": ..., "v": ...}` values as conflicts show them.

### Owners

| `owner` | Meaning | Allowed with |
|---|---|---|
| `BOTH` (default) | either side may change it; a clash is settled by the conflict strategy | `BIDIRECTIONAL` only |
| `A` | only A's value travels; A always wins | `A_TO_B`, `BIDIRECTIONAL` |
| `B` | only B's value travels; B always wins | `B_TO_A`, `BIDIRECTIONAL` |

A change made on the side that does not own a field does not flow; it is kept pending in the snapshot, not forgotten.

### Conflict strategies

The strategy decides a field of a `BOTH` mapping that changed on both sides since the last sync, to different values:

| `conflictStrategy` | Winner |
|---|---|
| `MANUAL` (default) | nobody: a conflict is opened |
| `A_WINS` / `B_WINS` | that side |
| `SOURCE_WINS` | the side whose change the run is processing |
| `LAST_WRITE_WINS` | the record with the later `modifiedAt`; when either side has no `modifiedAt` the field becomes a conflict |

Values equal through the transform are not a clash.

## How a run processes a change

For each side that flows, the run reads the connector's change feed from the saved checkpoint (or lists the whole
resource when the connector has no feed), and for each record:

1. Finds the link of the record. None: it pairs it with an unlinked record of the other side whose `matchOn` value is
   equal, or else creates it there ([creates](#create-modes-and-externalidfield)).
2. Skips it when its mapped values hash to what the sync last wrote to that side (its own echo, ADR-0010), or when the
   link is not `LINKED` (a pair in `CONFLICT` or `ORPHANED` waits for a person).
3. Reads the other side's current record (gone ⇒ handled as a [delete](#deletes)) and diffs both against the snapshot,
   the values both sides last agreed on.
4. Writes what flows, with the expected version. If the record moved in between (`Conflict`), it reads both again and
   merges once more; if it moved again, the pair is held as a `FIELDS` conflict.
5. Saves the new versions, hashes and snapshot, then the checkpoint after each page.

A provider that is down, rate limiting or rejecting the credentials fails the run (the job retries transient failures);
a record the provider refuses fails alone and the run ends `PARTIAL`. Runs are at least once and idempotent: re-reading a
page writes nothing new.

Events (webhooks) are hints: an event on a connection queues an `EVENT` run of each active definition on that
connection whose side may flow (and, when the event names a resource, whose resource is that one). The run reads the
feeds; it does not trust the event payload.

## Conflicts

| `kind` | When | `fields` |
|---|---|---|
| `FIELDS` | a field changed on both sides and no owner or strategy decided, or a write kept losing the race | one entry per contested mapping |
| `DELETED` | a linked record was deleted under `deletePolicy: FLAG`, or under `PROPAGATE` against the direction | empty |
| `ORPHANED_CREATE` | a create was cut short and cannot be found again (no `externalIdField`, or the recovery was refused) | empty |

While a conflict is open, the pair's link is `CONFLICT` (or `ORPHANED`) and nothing is written for it. `GET /conflicts`
(filters `status=OPEN|RESOLVED`, `definitionId`, `limit` 1–200) and `GET /conflicts/{id}` show them. A `FIELDS` entry
carries `key`, `aField`, `bField`, both current values `a` and `b`, and `base`, the values both sides last agreed on, as
typed values (`{"t": "choice", "v": "Retail"}`; `t` one of `null`, `text`, `number`, `bool`, `date`, `datetime`,
`choice`, `multi`, `ref` with `r`, `json`).

### Resolving

`POST /conflicts/{id}/resolve` with `{choice, values?, fields?}`:

- `{"choice": "A"}`: every contested field takes A's value.
- `{"choice": "B"}`: every contested field takes B's value, brought back to A through the mapping's inverse transform.
- `{"choice": "CUSTOM", "values": {...}, "fields": {...}}`: each contested field is settled by `values` (a plain or typed
  value keyed by **A's field name**) or else by `fields` (`"A"` or `"B"` keyed by the **mapping key**):

```json
{ "choice": "CUSTOM", "values": { "Name": "Acme Ltd" }, "fields": { "Industry->Industry": "B" } }
```

The value chosen for A is converted to B with the forward transform; each side is written where the direction allows,
with the expected version; the snapshot records the agreed values and the link is `LINKED` again. A pick of B on a
mapping without an inverse (`static`, `conditional`) is refused with a violation on `fields.<key>`, never guessed: give
an explicit value in `values` instead. A field with neither a value nor a pick is a violation on `values.<aField>`.

`DELETED` and `ORPHANED_CREATE` conflicts ignore the body: resolving forgets the pair (`resolution: {"choice":
"UNLINK"}`), and the conflict stays as history with `linkId` null. Records are not deleted or created by it; the next run
treats a remaining unlinked record as new.

Errors: `400` invalid choice or violations, `404` the conflict or its link is gone, `409` already resolved, or a record
gone or changed while resolving (look again and resolve again).

## Create modes and `externalIdField`

Before a create, the run commits a `PENDING_CREATE` link with a random create token, so a crash between the provider's
answer and the link's update never creates a duplicate. The target side's `externalIdField` says how the record is found
again ([#32](https://github.com/wasichai/integrations/issues/32)):

| Mode | When | Create | A create left pending |
|---|---|---|---|
| `UPSERT` | the field is set and the connector upserts (Salesforce) | upsert keyed by the token | upserted again, same token |
| `MARKER` | the field is set, the connector cannot upsert (Notion) | create, token in the field | linked to the record holding the token, or created anew |
| `NONE` | no `externalIdField` | plain create | `ORPHANED` with an `ORPHANED_CREATE` conflict |

Rules for `externalIdField` (1–200 characters): only on a side the sync creates records in, never a mapped field (a
mapping would overwrite the token). At validation, in `UPSERT` mode it must be an external id field of the schema
(Salesforce: a custom field marked External ID); in `MARKER` mode a writable `TEXT` field. A recovery the provider refuses
is treated as `NONE`: the first attempt may have succeeded, so a person decides. Every run starts by recovering the links
left `PENDING_CREATE`.

## Deletes

A deletion reaches the sync from a change feed that reports it (Salesforce's deleted records), from a `read` that
answers `null` (Notion's trashed pages), or from reconciliation. What happens depends on `deletePolicy`:

| Policy | Effect |
|---|---|
| `FLAG` (default) | the link becomes `ORPHANED` and a `DELETED` conflict is opened for a person |
| `PROPAGATE` | the other record is deleted (Notion: to the trash) and the link removed, when the deleted side may flow; else as `FLAG`, with a warning in the log |
| `IGNORE` | the link becomes `ORPHANED`; the other record stays |

A `PROPAGATE` deletion that may not flow (in an `A_TO_B` definition, a record deleted in B) is not dropped: the user
asked for deletions to travel and this one cannot, so a person decides, exactly as under `FLAG`. Resolving the
`DELETED` conflict unlinks the pair; the surviving record is created again on the next change or reconcile.

## Reconciliation

A reconcile run (`kind: RECONCILE`, or the scheduler every `reconcileSeconds`) reads both sides whole, including a side
that does not flow, and leaves the cursors where they are. It feeds every record through the same pipeline and then
checks each `LINKED` pair it did not just settle:

- a linked record absent from its side is **missing**: handled as a delete under the policy;
- a record whose mapped values moved without a change the feeds saw is **diverged**; on a side that does not flow, the
  other side's values are written back.

A checkpoint the provider refuses makes the run read that side whole from the start once (**cursor reset**), and what it
sees feeds the same checks. The run counts `missing`, `diverged` and `cursorResets`.

`kind: INITIAL` clears both checkpoints first and reads from the start; links and hashes keep it from duplicating.

## Running and scheduling

- `POST /sync-definitions/{id}/run` with `{"kind": "INCREMENTAL" | "INITIAL" | "RECONCILE"}` (default `INCREMENTAL`,
  recorded as trigger `MANUAL`) answers `202 {runId}`. `409` when the definition is not `ACTIVE` or a run of it is
  already queued or running: one run per definition at a time.
- The scheduler (when `wasichai.integrations.sync.scheduler-enabled`) ticks every `scheduler-tick` (30 s) on one replica
  at a time and starts, per `ACTIVE` definition, at most one run per tick: a reconcile when `reconcileSeconds` elapsed
  since the last reconcile, else an incremental run (trigger `SCHEDULE`) when `scheduleSeconds` elapsed since the last
  run that read the feeds. A definition that never ran is due at once.
- Without `scheduleSeconds`, a definition runs on events and by hand only.

Runs execute as durable jobs of kind `integrations.sync.run` ([operations](operations.md#jobs)). A run's `trigger` is
`MANUAL`, `SCHEDULE`, `EVENT`, `INITIAL` or `RECONCILE`; its `status` `QUEUED`, `RUNNING`, `SUCCEEDED`, `PARTIAL` or
`FAILED`, with `errorKind` `transient` (the job retries) or `permanent` (a person must act, e.g. the connection needs
reconnecting). `GET /sync-definitions/{id}/runs` lists them newest first with counts `read`, `created`, `updated`,
`skipped`, `conflicts`, `failed`, `missing`, `diverged`, `cursorResets`.

## Status

`GET /sync-definitions/{id}/status` summarises a definition:

| Field | Meaning |
|---|---|
| `status` | `DRAFT`, `ACTIVE` or `PAUSED` |
| `lastRun`, `lastSuccessAt` | the newest run, and when the last `SUCCEEDED` run finished (`PARTIAL` does not count) |
| `lastError`, `errorKind` | set only when the latest finished run `FAILED` |
| `pendingConflicts` | open conflicts |
| `links` | `{LINKED, PENDING_CREATE, CONFLICT, ORPHANED: count}` |
| `cursors` | per side: the checkpoint and when it was saved |
| `nextScheduledAt`, `nextReconcileAt` | for an `ACTIVE` definition with a schedule |
| `queuedOrRunning` | whether a run is waiting or running |

`ORPHANED` links that are not tied to an open conflict (policy `IGNORE`) are visible only in these counts.
