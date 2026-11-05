# ADR-0022: Integration workflows are a module of their own: a versioned graph with closed conditions, run on the job queue

**Status**: accepted · 2026-11-05 · builds on [ADR-0008](0008-durable-jobs-in-postgresql.md),
[ADR-0009](0009-sync-semantics-and-conflicts.md), [ADR-0011](0011-multi-tenancy-and-permissions.md)

## Context

Phase 6 ([#41](https://github.com/wasichai/integrations/issues/41)) asks for integration workflows: when something
happens in a connected system (or on a schedule, or by hand), run a series of steps across connections — call a
connector action, write a record, test a condition, branch, fan out, wait, ask a person. This issue defines, validates
and versions them; running them is [#42](https://github.com/wasichai/integrations/issues/42).

Wasichai already has two things with similar names, and neither fits:

- **`wasichai-workflow`** is a record state machine: per object, states (`INITIAL`, `INTERMEDIATE`, `FINAL`) and
  named transitions a person fires, with role checks. It says itself "no BPM engine, no tokens, no parallel branches".
  It holds no steps, calls nothing outside the app, and its subject is one record's state.
- **`wasichai-automation`** runs trigger → conditions → actions on record changes of a Wasichai object
  (`RECORD_CREATED`, `TRANSITION_APPLIED`, …): a flat list of AND-ed conditions and a fixed set of actions (update a
  field, create a record, call a webhook, issue a document, notify). Its runs are not retried, and it knows no
  connection or connector. ADR-0008 already kept it as record-level business automation.

An integration workflow is triggered by provider events, carries data from one system to another through the tenant's
connections, branches and fans out, and must survive restarts mid-way. Rule 2 also forbids the Wasichai modules from
knowing connectors, and Wasichai is never changed from here.

## Decision

- **A module of its own**, `wasichai-integrations-workflows`, beside `wasichai-integrations-sync` and depending only on
  `wasichai-integrations` (and through it the SDK). Its own `wasichai.integrations.workflows.enabled` switch, Flyway
  history `integrations_workflows` (order 220, after sync's 210), starter
  `wasichai-spring-boot-starter-integrations-workflows`. It does not extend or call `wasichai-workflow` or
  `wasichai-automation`; an automation action that starts an integration workflow would be a later, separate decision.
- **The graph is JSON, parsed into a closed Kotlin model.** One `trigger` (`event`, `webhook`, `schedule`, `manual`)
  with `next`, and `steps` with ids and named edges: `action` (a declared connector action through `ActionExecutor`, or
  a record `create` / `update` / `upsert` / `delete` on a connection's resource), `condition` (a gate), `branch`
  (`then` / `else`), `parallel` (`branches` that meet at `join`), `delay`, `approval`, `end`; `onError` on any step but
  `end`. Values are operands: `{"ref": path}` into the trigger or an upstream step's output, or `{"value": literal}`.
  The JSON is stored as the editor sent it, so a half-built draft round-trips; the model is what validation (and the
  runner) reads.
- **Conditions are a closed set**, as sync's transforms are (ADR-0009): `equals`, `notEquals`, `exists`, `in`, `gt`,
  `lt` (numbers, dates, date-times), `and`, `or`, `not`, nested at most 8 deep, always a reference against literals.
  No expression language, templates or scripts: nothing a tenant writes is evaluated as code, and every condition can
  be checked and shown in an editor.
- **No loops in the MVP.** The graph must be acyclic, every step reachable from the trigger, every edge pointing at an
  existing step, every parallel branch reaching its join, and a reference must name an `action` or `approval` step
  upstream of its reader. Iteration over a list is a later decision.
- **Validation is field-located** (`graph.steps[2].input.email`) with Wasichai's `FieldViolation`, like sync's
  `DefinitionValidator`: the tenant owns every connection (another tenant's reads as unknown); the connector is
  installed and its manifest declares what the step uses (`WEBHOOKS` and the event type, `ACTIONS` and the action,
  `WRITE` or `UPSERT` and the resource); and, for writes on a connector with discovery, the input against the schema
  it describes (fields exist and are writable, literals fit, required fields on create, an upsert key that is an
  external id). The provider is called only when everything else holds.
- **Drafts are free, versions are immutable.** Saving a draft checks only its name and that the graph is a JSON object
  (≤ 256 KiB): an editor saves work in progress. `publish` validates the draft, then in one transaction (definition
  row locked, version unchanged since validation) stores it as the next numbered version, derives the trigger row
  (`integration_workflow_triggers`, one per workflow, for the runner to find by type, connection and event type) and
  points the definition at it. A database trigger refuses any change to a version but clearing its publisher. Draft
  edits use the definition's `version` with `If-Match`, like sync definitions.
- **Execution will run on the integrations job queue** (ADR-0008), not on Temporal: a run is a row, each step a job
  with a dedupe key per run and step, so a crash resumes from the last finished step and a retried step is
  idempotent; delays are jobs with a future `next_attempt_at`; approvals park the run until a decision arrives;
  parallel branches are jobs that a join counts. A run is pinned to the version it started on. ADR-0008's reasons
  stand: no extra cluster, no Kotlin SDK gap, no history limits to design around; Temporal is reconsidered only if
  workflows need long-lived orchestration that jobs cannot express.

## Consequences

- Published workflows do not run until #42; the docs say so. `webhook` triggers get their url with #42.
- A connection used by a workflow's draft or published version cannot be deleted (`ConnectionUsage`, `409`), as with
  sync definitions.
- A reference's type and a connector action's input are not type-checked: the first is known only at run time, and
  the manifest declares no action input schema. A ref to a step on an untaken branch resolves to nothing at run time.
- New step kinds, loops or an expression language need a new ADR; the closed sets are deliberate.
