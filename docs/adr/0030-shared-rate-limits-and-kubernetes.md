# ADR-0030: Rate limits are token buckets in PostgreSQL shared by every replica; Kubernetes is a Pulumi program

**Status**: accepted · 2026-11-05 · builds on [ADR-0008](0008-durable-jobs-in-postgresql.md),
[ADR-0026](0026-ai-tools.md), [ADR-0028](0028-example-app-and-image.md)

## Context

[#47](https://github.com/wasichai/integrations/issues/47) has two parts.

**Rate limits.** `RateLimits` paced each connection with an in-memory token bucket built from the connector's declared
`rateLimit` (requests per second and burst), and a provider's `429` with `Retry-After` paused the connection on the
replica that saw it. ADR-0028 split the app into `api` and `worker` roles meant to scale out, and every replica kept
its own buckets: n replicas could together send n times a provider's rate, and only the replica that was told to wait
waited, while the others found out with `429`s of their own. AI tool limits (ADR-0026) had the same gap, a per-tool
bucket per replica, and left the cluster-wide budget to this issue.

**Kubernetes.** The example image ran under Docker Compose only. #47 asks for a deployment to any Kubernetes cluster,
k3s included, without a cloud provider, as a Pulumi program, checked in CI without cloud credentials.

## Decision

### Shared token buckets

- **One table, `integration_rate_buckets`** (core migration `V5`): `bucket_key` text primary key, `tokens` double,
  `updated_at`, `paused_until`. The key is generic: `http:<connection id>` for a connection, `tool:<org>:<tool id>`
  for an AI tool. There is no tenant column: a key names a connection or a tool the caller has already
  tenant-checked, and no route reads the table by anything but that key. The tool key carries the tenant anyway, so a
  row says whose budget it is. The tool id is used over the tool name because the id survives a rename.
- **One statement per call, no read-then-write race.** A provider call does a single upsert that refills from the
  elapsed time and takes a token:

  ```sql
  WITH t AS (SELECT clock_timestamp() AS now)
  INSERT INTO integration_rate_buckets AS b (bucket_key, tokens, updated_at)
  SELECT :key, :burst - 1, t.now FROM t
  ON CONFLICT (bucket_key) DO UPDATE SET
    tokens = CASE WHEN b.paused_until > EXCLUDED.updated_at THEN b.tokens
             ELSE LEAST(:burst, b.tokens + elapsed(EXCLUDED.updated_at - b.updated_at) * :rate) - 1 END,
    updated_at = CASE WHEN b.paused_until > EXCLUDED.updated_at THEN b.updated_at
                 ELSE GREATEST(b.updated_at, EXCLUDED.updated_at) END
  RETURNING b.tokens, b.paused_until, (SELECT now FROM t) AS now
  ```

  The conflicting row is locked for the statement, so concurrent replicas are serialized by PostgreSQL. Tokens may go
  below zero: that is a **reservation**. The caller waits `-tokens / rate` and then calls without asking again, so a
  waiting call costs one round trip, not a poll. Calls are spaced exactly at the rate across replicas, and the burst
  is shared. A key that is paused takes nothing; the caller waits until `paused_until` and asks again.
- **Time is the database's** (`clock_timestamp()`, not the transaction's `now()` and not the replica's clock):
  replicas with skewed clocks agree. `clock_timestamp()` also keeps refilling inside a caller's transaction.
- **Provider pauses are shared.** A `429` with `Retry-After` sets `paused_until` to the later of the stored pause and
  `clock_timestamp() + Retry-After`. Every replica's next call to that connection waits it out. Operations health
  (`rateLimitedUntil`) reads the stored pause, so it shows a pause seen by any replica.
- **AI tools use the same table** with a refusing variant: the token is taken only when one is there (`refill >= 1`).
  A refusal leaves the row as it was, so callers refused again and again do not drain it. The returned `updated_at`
  equals the statement's time only when this call took the token, which tells a grant from a refusal in the same
  round trip, and a refusal still says how long to wait (`429` with `Retry-After`). Updating or deleting a tool
  deletes its row, so a changed limit starts with a full bucket, as before.
- **Fallback, never a stall.** Every database call has a 1 s timeout. On a timeout or an error the replica uses its
  in-memory bucket, as before this ADR. It counts `integrations.rate_limit.fallbacks{operation,reason}` and logs one
  warning per outage plus one line when the database answers again. A pause is always kept in memory too, so the
  replica that saw the `429` honours it without the database. Waiting is a coroutine `delay`, as before: no thread
  blocks.
- **The switch is `wasichai.integrations.http.rate-limit.shared`, default `true`.** Sharing is the safe default for
  more than one replica, and it costs a single replica one indexed upsert per call. `false` keeps the per-replica
  buckets and does no database work for limits. It covers AI tool limits too.
- **Load.** One upsert per provider call (and per tool call) on a one-row-per-key table, a primary-key hit with no
  index to update. `fillfactor = 50` leaves room for HOT updates. This is small next to the HTTP call it paces, which
  takes tens to hundreds of milliseconds. No local pre-check or batching: either would hand out tokens the other
  replicas cannot see, and the problem it solves has not been measured. A row whose connection or tool was deleted
  stays, a few dozen bytes that nothing reads.
- Waits are timed as `integrations.rate_limit.waits{source}`, where `source` is `shared`, `paused` or `local`.

### Kubernetes with Pulumi

- **`deploy/pulumi`**: TypeScript with the Kubernetes provider only, so it is provider-agnostic. It runs on k3s,
  kind, k3d or a managed cluster from a kubeconfig, with no cloud SDK or account. Versions are pinned exactly, with a
  committed `package-lock.json`.
- It creates a namespace, an auto-named `Secret` and an auto-named `ConfigMap`. Auto-naming means a changed value
  makes a new object and the Deployments roll onto it. It also creates the `integrations-api` Deployment
  (`SPRING_PROFILES_ACTIVE=api`, configurable replicas), the `integrations-worker` Deployment (`worker`, no
  Service), a Service and an Ingress. The Ingress host is the host of `publicBaseUrl`, with TLS when `tlsSecretName`
  is set.
- **Secrets come from Pulumi config secrets only**: the database password, the JWT secret and the encryption keys.
  They stay Pulumi secrets into the `Secret`'s `stringData`, encrypted in the stack file and the state. The plain
  `env` map refuses names that look secret. The ConfigMap sets `WASICHAI_INTEGRATIONS_HTTP_RATELIMIT_SHARED=true`
  explicitly, and `SERVER_FORWARDHEADERSSTRATEGY=framework` because the app sits behind the Ingress.
- **Pods**: uid 10001 (the image's user), `runAsNonRoot`, a read-only root filesystem with an `emptyDir` on `/tmp`,
  no privilege escalation, every capability dropped, `RuntimeDefault` seccomp and no service account token. A startup
  probe on `/actuator/health/liveness` allows up to 5 minutes, because the first start migrates every module. Then
  the liveness and readiness probes take over. The grace period is 45 s: a 5 s `preStop` sleep so the Service drops
  the pod first, the app's 30 s drain, and a margin. The `preStop` sleep action needs Kubernetes 1.30 or later.
  Migrations need no init job: Flyway's advisory lock already serializes replicas that start together (ADR-0028).
- **The database is external by config** (`dbHost`, `dbPort`, `dbName`, `dbUsername`, and the secret `dbPassword`).
  `postgresInCluster=true` adds one PostgreSQL 18 StatefulSet with a volume, for development and trials only. There
  are no backups, no replicas and no tuning; production points at a managed or operator-run database.
- **No Helm chart, no PodDisruptionBudget, no HPA.** One deployable needs one program. Each of those waits for a
  concrete need (rule 8).

### CI

The issue asks for `pulumi preview` in CI without cloud credentials. With the Kubernetes provider, preview asks a
cluster: without one it has nothing to diff against. A real preview would need a kind cluster and the Pulumi CLI in
every run. We chose **Pulumi's mocks**: the job "Pulumi program (typecheck + mocked preview tests)" runs `npm ci`,
`tsc --noEmit` and `node --test` over the program with `pulumi.runtime.setMocks`. That is the program's
whole evaluation, the same resource graph a preview computes, with no engine and no cluster. The tests assert:

- the two roles, replicas, profiles, probes, grace period and security context;
- the Service and the Ingress;
- that every secret is a Pulumi secret and appears in no resource but the `Secret`;
- the in-cluster database;
- that `Pulumi.ci.yaml` (placeholders only) holds no secret.

The acceptance criterion "pulumi preview in CI" is therefore met by mocked program tests, not by a preview against a
cluster. A preview against k3s, k3d or kind is documented in `deploy/pulumi/README.md`. It was not run while writing
this ADR: the Pulumi CLI was not reachable here.

## Consequences

- n replicas send at most one connection's declared rate together. A provider's `Retry-After` stops all of them, not
  only the one that was told. An AI tool admits `rateLimitPerMinute` across the cluster.
- Every provider call and tool call does one more database statement. A slow or failing database degrades limits to
  per replica: a counted, logged fallback that never fails or stalls the call.
- A reservation is not handed back. A call cancelled while it waits for its slot leaves the slot unused. The cluster
  then runs slightly below the rate, never above it.
- The table grows by one row per connection or tool ever called. Nothing removes rows of deleted connections; if that
  ever matters, the retention tasks can.
- Deploying to Kubernetes is `pulumi up` against any cluster. CI proves the program's output, not that a given
  cluster accepts it.
