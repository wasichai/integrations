# ADR-0008: Durable work runs on a PostgreSQL job queue with leases, not on Temporal or wasichai-automation

**Status**: accepted · 2026-11-04 · builds on [wasichai
ADR-016](https://github.com/wasichai/wasichai/blob/main/docs/adr/0016-automations-queue-and-system-context.md),
[ADR-039](https://github.com/wasichai/wasichai/blob/main/docs/adr/0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)

## Context

Sync runs, webhook processing, refreshes and reconciliation must survive restarts and crashes and retry with backoff.
Options compared:

| | wasichai-automation queue | Temporal | Own PostgreSQL queue |
|---|---|---|---|
| Purpose | record triggers → actions | general durable execution | integration jobs |
| Retries / backoff | none (FAILED on error) | yes | yes |
| Crash recovery | a RUNNING run stays stuck | yes | leases |
| Infrastructure | none | a Temporal cluster (+ its DB) | none |
| JVM support | Kotlin | Java SDK, no Kotlin SDK | Kotlin |
| Limits | — | 51,200 events / 50 MB history per workflow | table size |

Temporal facts: [limits](https://docs.temporal.io/workflow-execution/limits), [SDKs](https://docs.temporal.io/encyclopedia/architecture/temporal-sdks.md).

## Decision

- `integration_jobs` in the module: kind, per-org dedupe key (unique), payload, attempts, `next_attempt_at`,
  `lease_until`. Workers claim with `FOR UPDATE SKIP LOCKED`, extend the lease while working, and finish in the same
  transaction as the job's effects where possible. An expired lease makes the job claimable again. Exponential backoff;
  `DEAD` after the maximum attempts; retry over REST.
- Schedules use `ClusterLock` so one replica enqueues each due run.
- wasichai-automation stays what it is: record-level business automation. Integration workflows (Phase 6) are built
  on this queue; an automation action that calls a connector is a later, separate decision.
- Temporal is reconsidered if workflows need long-lived, versioned orchestration beyond what jobs express.

## Consequences

- At-least-once execution: every job's effect must be idempotent (links, hashes, unique constraints).
- Throughput is bounded by PostgreSQL; fine for the MVP; partitioning or a broker would be a later ADR.
