# Workflows

An integration workflow is a graph: one trigger, then steps that call connectors, test conditions, branch, run in
parallel, wait, or ask a person. It is designed as a draft, checked with `validate`, and published as an immutable,
numbered version. The REST contract is [api.md › Workflows](../architecture/api.md#workflows); the reasoning is
[ADR-0022](../adr/0022-integration-workflows.md).

> **Published workflows do not run yet.** This release stores, validates and versions them. Execution, on the
> integrations job queue with durable steps, is [#42](https://github.com/wasichai/integrations/issues/42).

## Not wasichai-workflow, not wasichai-automation

| | wasichai-workflow | wasichai-automation | integrations workflows |
|---|---|---|---|
| About | states and transitions of one object's records | trigger → conditions → actions on a record change | a graph of steps across external systems |
| Trigger | a person fires a transition | a record created, updated, deleted or transitioned | a provider event, a webhook, a schedule, by hand |
| Acts on | the record's state | the app's own records, a webhook, a document, a notification | connections: connector actions and record writes |

## Life of a workflow

1. `POST /api/integrations/workflows` with `{name, graph}`. A half-built graph is fine: a draft only needs a name and
   a JSON object.
2. `PUT` it as the editor changes it, with `If-Match: "<version>"`.
3. `POST …/{id}/validate` lists every problem with its path, e.g. `graph.steps[2].input.email`.
4. `POST …/{id}/publish` validates once more and stores the draft as the next version (`1`, `2`, …). A version never
   changes: editing the draft afterwards leaves version 1 as it was, and the workflow shows `unpublishedChanges: true`
   until it is published again.
5. `GET …/{id}/versions` and `…/versions/{n}` read the history.

Only tenant administrators (`MANAGE_ORGANIZATION`) create, change, validate, publish or delete; any member reads. A
connection a workflow uses (in its draft or its published version) cannot be deleted until the workflow lets go of it.

## A small example

When the messenger says a message was created, upsert a contact, then post its id back; if the upsert fails, stop.

```json
{
  "trigger": {"type": "event", "connectionId": "<messenger>", "eventType": "message.created", "next": "has-email"},
  "steps": [
    {"id": "has-email", "type": "condition", "condition": {"op": "exists", "ref": "trigger.payload.email"}, "next": "upsert"},
    {
      "id": "upsert", "type": "action", "connectionId": "<crm>",
      "write": {"resource": "contacts", "operation": "upsert", "externalIdField": "extId", "externalId": {"ref": "trigger.payload.id"}},
      "input": {"email": {"ref": "trigger.payload.email"}, "tier": {"value": "gold"}},
      "next": "notify", "onError": "done"
    },
    {"id": "notify", "type": "action", "connectionId": "<messenger>", "action": "send_message",
     "input": {"text": {"ref": "steps.upsert.output.id"}}, "next": "done"},
    {"id": "done", "type": "end"}
  ]
}
```

## Building blocks

- **Triggers**: `event` (a webhook event the connection's connector declares), `webhook` (the workflow's own url,
  issued with #42), `schedule` (six-field cron, at most once a minute, in a zone), `manual`.
- **Steps**: `action` (a connector action, or a record `create`, `update`, `upsert` or `delete` on a resource),
  `condition` (go on only when it holds), `branch` (`then` / `else`), `parallel` (`branches` that meet at a `join`),
  `delay` (an ISO-8601 duration), `approval` (`next` when approved, `rejected` otherwise), `end`.
- **Error edges**: any step but `end` may name `onError`; without one, a failure fails the run.
- **Values**: an input is `{"ref": "trigger.…"}`, `{"ref": "steps.<id>.…"}` or `{"value": …}`. A step reads only
  the trigger and `action` or `approval` steps upstream of it.
- **Conditions** are a closed set, like sync's transforms: `equals`, `notEquals`, `exists`, `in`, `gt`, `lt` (numbers,
  dates, date-times), `and`, `or`, `not`. There is no expression language and no code.

## What validation checks

One trigger; known step types and their keys; unique ids; edges that name existing steps; every step reachable from
the trigger; no loops; each parallel branch reaching its join; references to steps that run before; connections that
belong to the tenant and whose connector is installed and declares the event, the action, `WRITE` or `UPSERT`, and the
resource. For record writes on a connector that describes its resources it also asks the provider for the schema: the
resource and every input field exist, nothing read-only is written, literal values fit the field type and its
options, a create sets every required field, and an upsert keys on an external id field. A reference's type is only
known when the workflow runs, and a connector action's input has no declared schema, so neither is type-checked.
