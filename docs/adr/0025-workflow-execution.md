# ADR-0025: Workflow runs are rows, steps are jobs, joins count arrivals under the run's lock

**Status**: accepted · 2026-11-05 · builds on [ADR-0008](0008-durable-jobs-in-postgresql.md),
[ADR-0022](0022-integration-workflows.md)

## Context

[#42](https://github.com/wasichai/integrations/issues/42) makes published workflows run. ADR-0022 decided the
direction (the integrations job queue, a run pinned to its version, a job per step, joins that count); this ADR fixes
the details that tests and operators depend on: what makes a run survive a restart, how a join goes on exactly once,
what an approval and a timeout do, how a schedule fires once across replicas, and what is deliberately not done.

## Decision

- **A run is a row, a step is a row and a job.** `integration_workflow_executions` holds the run (status `QUEUED`,
  `RUNNING`, `WAITING`, `SUCCEEDED`, `FAILED`, `CANCELLED`; the pinned `version_number`; the trigger object refs read
  as `trigger.…`; a correlation id). `integration_workflow_step_executions` holds one row per step the run reached,
  keyed `(execution_id, step_id)`: ADR-0022 forbids loops, so a step runs at most once per run. Each step runs as a job
  of kind `integrations.workflow.step` with dedupe key `exec:<run>:step:<step>`, `max_attempts` from
  `wasichai.integrations.workflows.step-max-attempts` (default 5) and the queue's backoff.
- **A step's result and what follows are one transaction.** The job handler reads the step row first: a finished row
  (`SUCCEEDED`, `FAILED`, `SKIPPED`) means the job is a redelivery and does nothing. Otherwise it marks the row
  `RUNNING` (attempt + 1), does the work outside any transaction (a provider call never holds a lock), then, under
  `SELECT … FOR UPDATE` of the run row, records the result (only if the row is not finished yet) and inserts the next
  rows and jobs. A crash before that commit leaves the row `RUNNING` and its job's lease runs out: the step runs again
  and nothing after it has happened yet. A crash after it leaves everything consistent. So a finished step is never
  repeated; the step that was in flight may be (at least once).
- **Idempotency of writes.** The SDK's `ResourceWriter` and `ActionExecutor` take no idempotency key, and this ADR does
  not add one. Each step row carries a stable key `wf:<run>:<step>` (the same for every attempt: a key per attempt
  would make a retried attempt look like new work) for when the SDK grows one. Until then: `upsert` is idempotent by
  its external id; `update` writes the same values again; a `delete` that finds the record gone on a retry counts as
  done (`alreadyGone`); a `create` or a connector action retried after its answer was lost may happen twice. Workflows
  that must not duplicate should write with `upsert`.
- **Failures.** A step failure the queue may retry (`JobRunner.classify`: transient, rate limited, unexpected) is
  thrown back to the queue while attempts remain; the row goes back to `PENDING` with the error. A failure that is
  permanent or out of attempts marks the row `FAILED` and follows `onError` in the same transaction; without `onError`
  the run is `FAILED` with the step's error. Either way the job itself succeeds: the failure lives in the run, which
  is where people look, never in a silently dead job. A failure of the engine's own bookkeeping is a job failure like
  any other and ends `DEAD` on the jobs page, from where it can be retried.
- **Joins count arrivals in the join's own row.** A parallel step enqueues each branch with a frame
  `{parallel, branch}` stored in the step row's `scope`. When a step's next is the join of a frame it is in, the
  branch *arrives*: an upsert of the join's row appends the branch index to `arrivals` only if it is not there and
  the row is still `PENDING`, and returns the list. The arrival that completes `0..n-1` enqueues the join; the row lock
  (and the run row lock around it) makes concurrent arrivals take turns, and a repeated arrival changes nothing, so the
  join is scheduled exactly once. The validator now refuses a step reachable from two branches of one parallel when
  that step still leads to the join: branches meet only at the join. Shared paths that lead away from the join (a
  common error handler and its end) stay allowed.
- **Ends.** An `end` step, or a `condition` that does not hold, ends the whole run `SUCCEEDED`, even with other
  branches still going: the run is over and their pending jobs find it so. Any end (success, failure, cancel) marks
  the steps that never ran `SKIPPED` with the reason; a step that was mid-call still records its result.
- **Delays** are a step row `WAITING` with `wait_until` and a job due then; the job finishes the step.
- **Approvals** park the step `WAITING` (and the run `WAITING`) with no job, or with a job due at the `timeout`.
  `POST …/executions/{id}/steps/{step}/approve|reject` (`MANAGE_ORGANIZATION`, the run's tenant only) decides under the
  run's lock, if the step still waits: approve follows `next`; reject follows `rejected`, or ends the run `SUCCEEDED`
  when there is none (ADR-0022: "the run ends"). The step's output is `{approved, decidedBy, decidedAt, comment?}`, so
  later steps can read `steps.<id>.output.approved`. A timeout that finds the step still waiting follows `rejected`
  with `{approved: false, timedOut: true}`, or fails the step (then `onError`, else the run fails); a timeout that
  finds it decided does nothing.
- **Starts are keyed.** Every trigger starts a run through one path that inserts the run row with a `trigger_key`
  unique per workflow: `event:<event id>` (an event delivered or replayed again starts nothing new),
  `schedule:<firing instant>`, `webhook:<Idempotency-Key>` when the caller sends one; manual runs have none.
- **Triggers.** *Event*: an `IntegrationEventListener` matches the tenant's published, unpaused event triggers by
  connection and event type. *Schedule*: a `WorkflowScheduler` like sync's, under the cluster lock
  `integrations.workflows.scheduler`; per trigger it computes the latest due firing of the cron in its zone since the
  last firing (or the publish), claims it with a compare-and-set of `last_fired_at`, and starts the run in the same
  transaction. Missed firings (downtime) collapse into one. Lock, compare-and-set and key each alone prevent a double
  firing. *Webhook*: `POST /{id}/webhook` issues a url `/api/integrations/webhooks/workflows/{id}/{secret}` under the
  already public webhook path; only the secret's SHA-256 is stored, the url is shown once and issuing again replaces
  it. *Manual*: `POST /{id}/run` with an optional payload runs the published version whatever its trigger type.
- **Pause.** A `paused` flag on the definition: no trigger (manual included) starts a run while it is set; runs already
  going finish. It is not a draft change: the version (`ETag`) stays.
- **Status while branches run in parallel** reflects the latest change (`WAITING` when a step parked, `RUNNING` when
  one started), not an aggregate. The step rows are the truth.
- **Observability.** `integrations.workflows.executions` (counter: `trigger`, `outcome` = `started`, `succeeded`,
  `failed`, `cancelled`) and `integrations.workflows.steps` (timer: `type`, `outcome` = `succeeded`, `failed`,
  `retry`). Every step runs inside `ChangeOrigin.within("integrations.workflow", correlationId)`, so the MDC carries
  the run's correlation id (the event's, the request's, or a new one), and every log line names it.

## Not supported

Loops and iteration (ADR-0022); compensation or rollback of steps that already ran; editing a running run or moving
it to a newer version; an idempotency key passed to providers; a per-step retry policy other than the module-wide
attempt count; a run that waits for a join a branch skipped past (the run ends at the `end` that branch reaches);
delays shorter than the queue's poll interval are as late as the next poll.

## Consequences

- Published workflows run. A step's provider work is at least once, everything else exactly once.
- Joins, approvals, delays and cancellation are rows and jobs: they survive restarts and need no in-memory state.
- The validator refuses graphs it accepted before when two parallel branches share a step that leads to the join; such
  a graph could not have run correctly, and none ran before this ADR.
