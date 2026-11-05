# Change history

Newest first. Architectural reasoning lives in `docs/adr/`; this file records what shipped.

## 2026-11-05 — Shared rate limits and Kubernetes deployment

Rate limits are shared across replicas ([#47](https://github.com/wasichai/integrations/issues/47), ADR-0030). A new
core migration `V5` adds `integration_rate_buckets` (bucket key, tokens, `updated_at`, `paused_until`). `RateBuckets`
takes or reserves a token with one atomic upsert per provider call. The upsert refills from `clock_timestamp()`, and
tokens may go below zero as a reservation the caller waits out with a coroutine delay. A provider's `Retry-After` is
stored in `paused_until`, so every replica honours it and health's `rateLimitedUntil` shows it. `RateLimits` uses the
shared bucket under `http:<connection id>`, and `ConnectionHttp` is unchanged. AI tool limits (`ToolRateLimits`)
share the table under `tool:<org>:<tool id>` with a refusing variant that only takes a token that is there; updating
or deleting a tool resets its row. A database call that fails or takes more than 1 s falls back to the replica's
in-memory bucket: `integrations.rate_limit.fallbacks{operation,reason}` counts it, with one warning per outage, and a
call is never held up by it. Waits are timed as `integrations.rate_limit.waits{source}`. The switch is
`wasichai.integrations.http.rate-limit.shared`, default `true`; `false` keeps the per-replica buckets.

`deploy/pulumi` is a Pulumi program in TypeScript using only the Kubernetes provider, for k3s or any cluster. It
deploys a namespace, a Secret from Pulumi config secrets (database password, JWT secret, encryption keys) and a
ConfigMap; both are auto-named, so a change rolls the pods. It also deploys `integrations-api` (profile `api`,
configurable replicas) with a Service and an Ingress (the host of `publicBaseUrl`, optional TLS secret), and
`integrations-worker` (profile `worker`, no Service). The pods run with:

- startup, liveness and readiness probes on the actuator health groups;
- a 45 s grace period: a 5 s `preStop` sleep and the app's 30 s drain;
- uid 10001, a read-only root filesystem and no capabilities.

The database is external by config, or an optional in-cluster PostgreSQL 18 StatefulSet for trials. The package also
holds `Pulumi.ci.yaml` (placeholders) and a README. The new CI job "Pulumi program (typecheck + mocked preview tests)"
runs `tsc --noEmit` and `node --test` against `pulumi.runtime.setMocks`, with no cluster and no credentials. This
stands in for a real `pulumi preview` (ADR-0030). The deployment guide gains a Kubernetes section and the
`http.rate-limit.shared` property; operations, data model, API and components are updated. No REST change. New
tests: `SharedRateLimitsTest` (integration: two replicas share one burst and one rate; a `429` seen by one pauses
the other; the refusing variant; the fallback), an auto-configuration test of the switch, and the Pulumi program
tests (12).

## 2026-11-05 — Retention, GDPR export and erasure, usage metering

Phase 8 operations across every module ([#46](https://github.com/wasichai/integrations/issues/46), ADR-0029). Three core
SPIs, filled by each module for its own tables: `RetentionTask`, `ExportContributor`, `ErasureContributor`; plus a core
`UsageMeter` the modules call. **Retention**: one cluster-locked `RetentionScheduler` (`integrations.retention`, every
`wasichai.integrations.retention.interval`, 1 h) runs every task in `DELETE … LIMIT` batches (sub-select with `SKIP
LOCKED`); ages by task name under `retention.max-age.*` (`events`, `jobs` 30d, `usage` 400d, `sync.runs` 90d,
`sync.conflicts` 180d, `workflows.executions` 90d, `ai.executions` 180d; `0` keeps forever). Unprocessed events,
pending or running jobs, open conflicts, links, running or waiting runs and pending approvals are never purged; the
latest sync run per definition and trigger stays. Meters `integrations.retention.purged{task,table}` and
`integrations.retention.failures`. **GDPR**: `GET /connections/{id}/export` (paged per section, stored rows as JSON,
credentials and webhook secrets only as "set" flags), `POST /connections/{id}/erase` (the connection and every derived
row in every module, in one transaction, even while used; then the token is revoked; audited) and
`POST /connections/{id}/erase-record` (events keyed by or carrying the id deleted, its side of sync links cleared and
the pair kept `ORPHANED`, finished workflow runs and tool executions carrying it deleted); all `MANAGE_ORGANIZATION`.
A plain `DELETE` of a used connection still answers `409`. **Usage**: `integration_usage_daily` (tenant, UTC day,
metric, count), counted in memory and flushed as upsert increments every `usage.flush-interval` (30 s) and at shutdown:
`provider.calls` (`ConnectionHttp`), `sync.records` (`SyncEngine`), `workflow.steps` (`WorkflowEngine`),
`ai.tool_calls` (`ToolExecutions`); `GET /usage?from&to&metric` (member). Migrations: core V5 (usage table, purge
indexes), sync V5, workflows V3, AI V2 (purge indexes). Revocation moved into `ConnectionCredentials.revoke`, shared by
delete and erase. REST change (additive): export, erase, erase-record, usage. New tests: `RetentionTest`,
`UsageMeterTest`, `ConnectionDataApiTest`, `SyncDataLifecycleTest`, `WorkflowDataLifecycleTest`,
`ToolDataLifecycleTest`, `ConnectionErasureTest` (every module booted together).

## 2026-11-05 — Example application, container image and Compose

New Gradle project `integrations-app` ([#45](https://github.com/wasichai/integrations/issues/45), ADR-0028), never
published (left out of `integrations-bom`; the release guard fails if it appears): a runnable Spring Boot app of the
Wasichai starter, the integrations, sync, workflows and AI starters, actuator health and the Notion, Salesforce,
Jira, GitHub and Slack connectors. Each connector bean is switched by `app.connectors.<id>.enabled` (default on) and
configured by `app.connectors.*` (Salesforce login url, API version and CDC channels, which select the Pub/Sub
connector; Jira site and cloud id; GitHub host); OpenAPI is left out (its mapping is code). Configured by environment
variables only (relaxed binding, `WASICHAI_DB_*`, `WASICHAI_JWT_SECRET`); sign-in limits and token revocation on.
Profiles `api` (worker and both schedulers off) and `worker` (on): one image, two roles. `integrations-app/Dockerfile`:
Gradle on `eclipse-temurin:25-jdk` with the packages token as a BuildKit secret, `eclipse-temurin:25-jre` runtime as
uid 10001, `HEALTHCHECK` on `/actuator/health`, graceful shutdown (30 s), heap at 75% of the container.
`deploy/compose/`: PostgreSQL 18 with a volume and healthcheck, `api` and `worker`, `.env.example` documenting every
variable. CI job "Container image (build + smoke)" builds the image and runs `.github/scripts/smoke.sh` (compose up,
health, sign-in as the development seed's admin, the catalog lists the five connectors, compose down). Deployment guide:
image, compose, environment variables, roles, scaling, health, shutdown. No REST change. New tests:
`ConnectorsConfigurationTest`, `RolesTest`, `IntegrationsAppTest` (integration).

## 2026-11-05 — MCP endpoint for exposed tools

`POST /api/integrations/mcp` serves the exposed AI tools over the Model Context Protocol, revision 2025-06-18,
streamable HTTP ([#44](https://github.com/wasichai/integrations/issues/44), ADR-0027), in `wasichai-integrations-ai`
behind `wasichai.integrations.ai.mcp.enabled` (default on). Wasichai's Bearer JWT (a person's, or a service account's
from `/api/auth/token`); stateless (no `Mcp-Session-Id`, no event stream: `GET`/`DELETE` `405`), no batches.
`initialize` (version negotiation), `ping`, `tools/list` (the enabled tools the caller may call, paged by cursor) and
`tools/call`, which is `ToolExecutions.execute` only: the same policy, execution rows and audit as REST. A provider
failure is a result with `isError`; an approval-required call is a pending result with its execution id, read over
REST. JSON-RPC errors `-32700`/`-32600` (HTTP `400`), `-32601`, `-32602` (unknown tool, arguments with field paths),
`-32001` forbidden, `-32002` conflict, `-32003` rate limited (`retryAfter`). `Origin` is checked against
`wasichai.integrations.ai.mcp.allowed-origins` (`403`), `MCP-Protocol-Version` (`400` when unsupported). No
wasichai-agent registration: 1.10.0 has no tool registration API. REST change (additive): the MCP route. New tests:
`McpServerTest`, `McpApiTest`, `McpDisabledTest`; `WasichaiIntegrationsAiAutoConfigurationTest` covers the switch.

## 2026-11-05 — AI tool catalog and execution policy

New module `wasichai-integrations-ai` and starter `wasichai-spring-boot-starter-integrations-ai`
([#43](https://github.com/wasichai/integrations/issues/43), ADR-0026). A tenant administrator exposes a connector
action, or one record operation (`read`, `list`, `create`, `update`, `upsert`, `delete`) on one resource, as a named
tool; nothing is exposed by default. Exposing checks the tenant's connection, the connector's declared and implemented
capability, action or resource, the tenant's roles and the input schema: a closed JSON Schema subset (supplied for
actions, derived from the resource schema for records; no credential-like properties). `ToolExecutions` is the only
execution path (MCP, #44, will call it): tenant (`404`), allowed role or `MANAGE_ORGANIZATION` (`403`), enabled and an
active connection (`409`), a per-tool, per-replica rate limit (`429`), the input (`400` with field paths), then the run
through the SDK. Actions and writes require approval by default (`202 PENDING_APPROVAL`, approve runs once, a second is
`409`; reject). Every execution is a row (input, status, masked output summary ≤ 16 KiB, mapped error, decision,
correlation id) and Wasichai `audit_log` entries; refusals are counted. A connection a tool uses is not deleted under
it (`409`). Flyway history `integrations_ai` (order 230). REST change (additive): `/api/integrations/ai/tools` (CRUD,
`execute`) and `/api/integrations/ai/executions` (paged list, get, approve, reject). New tests: `ToolSchemaTest`,
`ToolRateLimitsTest`, `WasichaiIntegrationsAiAutoConfigurationTest`, `ToolApiTest`, `AiDisabledTest`; `AppBootsTest`
reads the AI tool routes too.

## 2026-11-05 — Workflow execution, triggers and approvals

Published workflows now run ([#42](https://github.com/wasichai/integrations/issues/42), ADR-0025). A run is a row
pinned to its version; each step is a row and a job of kind `integrations.workflow.step` (dedupe key
`exec:<run>:step:<step>`). A step's result and the next steps are written in one transaction under the run's row lock,
so a finished step is never repeated and a crash resumes at the unfinished step; provider calls are at least once
(`upsert` is idempotent; the SDK has no idempotency key, a stable per-step key is stored for later). Conditions are
evaluated (the closed set, total: no value compares false); branch, parallel fan-out with a join that runs exactly
once (arrivals counted in the join's row), delay (a job due later), approval (approve or reject with
`MANAGE_ORGANIZATION`, optional timeout to `rejected` or failure), end, `onError` after the queue's retries
(`wasichai.integrations.workflows.step-max-attempts`, default 5), cancel. Triggers: integration events (an
`IntegrationEventListener`, once per event), schedules (cluster-locked `WorkflowScheduler`, a firing claimed once),
a per-workflow webhook url with a hashed secret and `Idempotency-Key`, manual runs. A `paused` flag stops new runs.
The validator now refuses a step shared by two parallel branches on the way to their join. Migration
`V2__workflow_executions` (executions, step executions, `paused`, `webhook_token_hash`, `last_fired_at`). Metrics
`integrations.workflows.executions` and `integrations.workflows.steps`; steps run with the run's correlation id.
REST change (additive): `/workflows/{id}/run|pause|resume|webhook|executions`,
`/workflows/executions/{id}` (+ `/cancel`, `/steps/{step}/approve|reject`), public
`/webhooks/workflows/{id}/{secret}`; `WorkflowView.paused`. New tests: `ConditionEvaluationTest`, `FlowTest`,
`WorkflowSchedulesTest`, `WorkflowExecutionTest`; `WorkflowValidatorTest` and the auto-configuration test extended.

## 2026-11-05 — Salesforce Change Data Capture via Pub/Sub

New module `integrations-connector-salesforce-pubsub` ([#14](https://github.com/wasichai/integrations/issues/14)):
`SalesforcePubSubConnector` is the Salesforce connector (same id, everything delegated to `SalesforceConnector`) whose
change feed reads Change Data Capture events over the Pub/Sub API for the objects given a channel in
`SalesforcePubSubConfig`; other objects keep polling, and polling stays the default. Each `changes()` call is a bounded
pull: one gRPC `Subscribe` from the stored replay id (kept as received, base64), up to 100 events within a short drain,
schemas by `GetSchema`, Avro decoded; only the `ChangeEventHeader` is trusted, records are fetched by id with one SOQL
query (`SalesforceConnector.readMany`, new). A feed starts with a full read, then subscribes from `EARLIEST` skipping
what that read covered. An expired replay id, a `GAP_*` event or a changed channel is a `Permanent` with a `reason`,
which the sync engine already turns into a cursor reset, a full read and the reconcile checks. SDK 1.1 adds
`ProviderHttp.credentialHeaders(refresh)` so a connector can authenticate a transport the platform cannot carry;
`ConnectionHttp` implements it with the same refresh-once rule (ADR-0024, superseding ADR-0014's "later"). New
dependencies, this module only: gRPC (okhttp transport), grpc-kotlin, protobuf at the Spring Boot BOM's versions, Avro
1.12.2, the protobuf Gradle plugin 0.10.0. REST change: none. New tests: `SalesforcePubSubConnectorTest` (in-process
gRPC stub), `SalesforcePubSubConfigTest`, `SalesforcePubSubContractTest`, `readMany` in `SalesforceConnectorTest`,
credential lending in `ConnectionApiTest`.

## 2026-11-05 — Workflow definitions and versions

New module `wasichai-integrations-workflows` and starter `wasichai-spring-boot-starter-integrations-workflows`
([#41](https://github.com/wasichai/integrations/issues/41), ADR-0022). A workflow is a JSON graph: one trigger
(`event`, `webhook`, `schedule`, `manual`) and steps (`action` as a connector action or a record write, `condition`,
`branch`, `parallel` with a join, `delay`, `approval`, `end`) with named edges and `onError`; conditions are a closed
set (`equals`, `notEquals`, `exists`, `in`, `gt`, `lt`, `and`, `or`, `not`). Drafts save freely; `validate` reports
field-located violations (one trigger, edges, reachability, no cycles, upstream references, the tenant's connections,
manifest capabilities, actions, events and resources, input against the discovered schema); `publish` stores the
draft as an immutable numbered version and derives its trigger row. Separate from `wasichai-workflow` and
`wasichai-automation`; execution on the job queue is #42, so published workflows do not run yet. A connection a
workflow uses is not deleted under it (`409`). Flyway history `integrations_workflows` (order 220). REST change
(additive): `/api/integrations/workflows` (list, create, get, update, delete, validate, publish, versions). New tests:
`ConditionTest`, `WorkflowValidatorTest`, `WasichaiIntegrationsWorkflowsAutoConfigurationTest`, `WorkflowApiTest`,
`WorkflowsDisabledTest`; `AppBootsTest` reads the workflow routes beside sync's.

## 2026-11-05 — Token refusals inside a 200 answer

The token client now reads a `2xx` token answer without `access_token` that carries an RFC 6749 `error` as a refused
grant, exactly like a `400 invalid_grant` ([#66](https://github.com/wasichai/integrations/issues/66)). A refused refresh
of a provider that refuses inside a `200` (Slack's `{"ok":false,"error":"invalid_refresh_token"}`) now marks the
connection `NEEDS_REAUTH`/`FAILING` instead of failing every call as `Permanent`, and a refused code exchange sends the
browser back with `error=token_refused`. A `2xx` with neither a token nor an `error` stays a malformed answer
(`Permanent`); only a non-blank string `error` counts. No manifest hint (ADR-0023). REST change: none (outcomes
documented in `api.md`). New tests: `TokenClientTest`; `ConnectionApiTest` covers both paths against the stub provider.

## 2026-11-05 — OpenAPI connector

`integrations-connector-openapi` turns an OpenAPI 3.x document (JSON or YAML) plus an explicit mapping written in code
into a connector ([#40](https://github.com/wasichai/integrations/issues/40)): per resource the list and get
`operationId`s, optional create, update and delete, the id field, page, cursor or next-url (body field or `Link`)
paging, and an updated-at field with an updated-since parameter for the change feed. Fields are derived from the
response schema with local `$ref`s and `allOf` resolved; auth comes from the document's security scheme (API key in a
header, HTTP bearer, OAuth authorization code). The manifest declares `DISCOVERY` and `READ`, plus `CHANGE_FEED` and
`WRITE` only when the mapping provides them, at `IMPLEMENTED` unless the app raises them; no webhooks, no actions. A
wrong mapping fails at bean creation naming every problem. Errors map by status, the provider's body in details only.
New dependency: `jackson-dataformat-yaml` (Jackson 3 BOM) for this module (ADR-0021). REST change: none. New tests:
`OpenApiConnectorTest`, `OpenApiConfigTest`, `OpenApiContractTest`, `OpenApiReadOnlyContractTest`.

## 2026-11-05 — Slack connector

`integrations-connector-slack` connects a Slack workspace through the Web API by OAuth v2 (bot token, comma-separated
scopes, token rotation through the platform's refresh) or a pasted bot token; the account is the team id, prefixed by
the enterprise id on a grid ([#39](https://github.com/wasichai/integrations/issues/39)). Channels the bot is in are the
resources and their messages the records (ts as id, `edited.ts` as version; thread replies left out); reads page by
`next_cursor`, the change feed reads the history from ten seconds before the newest ts seen (at least once; tombstones
are deletions, other edits and deletions come through events), writes post, edit (read-compare-write, read back for the
version) and delete messages as text. `ok:false` codes and 429 map to the taxonomy. Events API deliveries are verified
by `X-Slack-Signature` within five minutes, `url_verification` is answered, `event_id` keys retries; `WEBHOOKS` is
`TESTED` against Slack's published example (ADR-0020). REST change: none. New tests: `SlackConnectorTest`,
`SlackContractTest`, `SlackWebhooksTest`, `SlackValuesTest`.

## 2026-11-05 — GitHub connector

`integrations-connector-github` connects github.com or GitHub Enterprise Server through REST API v3 by OAuth app or
GitHub App user token (web flow with PKCE) or personal access token; installation tokens are not supported
([#38](https://github.com/wasichai/integrations/issues/38)). Repositories are the resources and their issues the
records, pull requests left out; reads page by `Link` (followed only under the configured api), the change feed runs
a fresh query from a second before the newest `updated_at` (at least once), writes create and edit issues (title,
body, state, labels, assignees, milestone; read-compare-write on `updated_at`) and refuse deletes, which the REST API
lacks. Webhooks are verified by `X-Hub-Signature-256`, keyed by `X-GitHub-Delivery`, and `TESTED` against GitHub's
published sample; 403/429 with `retry-after`, `x-ratelimit-remaining: 0` or a secondary-limit message map to
`RateLimited` (ADR-0019). REST change: none. New tests: `GitHubConnectorTest`, `GitHubContractTest`,
`GitHubWebhooksTest`, `GitHubValuesTest`.

## 2026-11-05 — Silent paths made visible: deletions against the direction, unchecked accounts, unreadable keys

Three paths that gave up without a trace now say so (issue #60, ADR-0018). A `PROPAGATE` deletion the direction
does not let flow opens a `DELETED` conflict and logs a warning, as `FLAG` does, instead of a silent `ORPHANED`
link. A connection whose connector has no `Authenticator` shows `accountChecked: false`, and a test of it reports
`accountChecked: false` and only reads its credentials back. Credentials no configured key opens mark the
connection `NEEDS_REAUTH`/`FAILING` with `lastError` "credentials cannot be decrypted with the configured keys;
reconnect" and act as `AuthExpired` (test `ok: false`, `409` on API calls, a permanent job failure) instead of a
`500`. REST change (additive): `accountChecked` on `ConnectionView` and `ConnectionTestView`. Documented: provider
config `options` are not read by any connector; a failed sync run does not change connection health. New tests in
`SyncEngineTest` and `ConnectionApiTest`.

## 2026-11-05 — Docs: api, security model and sync semantics aligned with the code

The docs now say what the code does ([#59](https://github.com/wasichai/integrations/issues/59)). `api.md` no longer
lists the `409` on deleting a used connection or provider app, nor per-field resolution, as missing. The security
model names `wasichai.integrations.encryption.keys.<id>`, drops the re-encrypt job (secrets are re-sealed only when
written again, webhook secrets never), says losing every key is an unexpected error today (#60), that `PlatformHttp`
takes http and https and blocks private addresses in its resolver, that `instance_url` has no host allowlist and that
retention is not built (#46). Sync semantics use the code's strategies (`SOURCE_WINS`, `A_WINS`, `B_WINS`,
`LAST_WRITE_WINS`, `MANUAL`) and delete policies. ADR-0017 supersedes ADR-0012 on registration: connectors are plain
SDK libraries and the app declares their beans. The deployment guide lists the Jira connector. REST change: none.

## 2026-11-04 — Jira Cloud connector

`integrations-connector-jira` connects Jira Cloud by OAuth 2.0 (3LO) through `api.atlassian.com`, the site found
from accessible-resources, or by API token against a configured site
([#37](https://github.com/wasichai/integrations/issues/37)). Projects are the resources and issue fields, custom ones
included, the fields; reads use the enhanced JQL search with `nextPageToken`, the change feed polls `updated` a
minute back in the user's zone (at least once), writes create, edit (read-compare-write against `updated`) and
delete issues, with rich text as plain-text ADF paragraphs. Transitions and dynamic webhooks are actions; deliveries
are verified by `X-Hub-Signature` or the OAuth app's HS256 JWT, `WEBHOOKS` stays `IMPLEMENTED` (ADR-0016). REST
change: none. New tests: `JiraConnectorTest`, `JiraContractTest`, `JiraWebhooksTest`, `JiraValuesTest`.

## 2026-11-04 — Docs: guides for users, connector authors, sync, deployment and operations

`docs/guides/` adds a user guide, a connector SDK reference, a step-by-step connector guide, a sync guide, a
deployment guide (every `wasichai.integrations.*` property, key rotation, OAuth, webhooks, scaling) and an
operations guide (health, jobs, events, rate limits, metrics, logs), indexed from `docs/README.md` and the root
README (issue #31). The connector guide's example, `TasksConnector` with its contract test, is compiled and run in
`integrations-connector-sdk-test`'s tests so it cannot drift from the SDK.

## 2026-11-04 — Sync: resolve a conflict field by field, through the inverse transform

A `CUSTOM` resolution can pick a side per mapping (`fields: {"tier->tier": "B"}`) instead of typing every
value. A value taken from B goes back to A through the mapping's inverse transform, so an enum map or a
date conversion lands as A stores it; a transform with no inverse is refused with a violation, never
guessed (issue #33).

## 2026-11-04 — Integrations: nothing in use is deleted from under its users

Deleting a connection that a sync definition stands on answers `409` naming the definitions, instead of
taking their links, runs and open conflicts with it (a `ConnectionUsage` SPI in the core, implemented
by the sync module). Deleting a provider app while connections to its connector exist answers `409`.
Testing a connection is now a manager's action, and a webhook endpoint lists with a `null` url when the
public base url is no longer configured instead of failing with a 500 (issue #51).

## 2026-11-04 — Sync: a write that keeps losing the race is a resolvable conflict

When the target moved on twice between read and write, the pair was held with conflict fields keyed by
provider field name instead of the mapping key, so resolving it did nothing. The held conflict now
carries the mapping key, both field names and the values last read, like any other FIELDS conflict
(issue #50).

## 2026-11-04 — Sync: creates cut short are recovered, not duplicated

A sync side may name an `externalIdField` ([#32](https://github.com/wasichai/integrations/issues/32)): creates there
upsert by the link's create token when the connector can, or write the token into that field; a run finds a create left
`PENDING_CREATE` again by the same token, and without the field marks it `ORPHANED` with an `ORPHANED_CREATE` conflict.
Migration `V4__sync_create_recovery`. REST change: `externalIdField` on definition sides, conflict kind
`ORPHANED_CREATE`. New tests: `CreateModeTest`, `CreateRecoveryTest`, cases in `SyncDefinitionApiTest`, `SyncEngineTest`.

## 2026-11-04 — Releases: release-please, Maven publishing behind a guard, integrations-bom

One version for every module through release-please on `gradle.properties`; a published release runs `publish.yml`,
which first publishes to a throwaway local repository and checks the exact artifact set, then publishes to GitHub
Packages ([#36](https://github.com/wasichai/integrations/issues/36),
[#49](https://github.com/wasichai/integrations/pull/49)). `integrations-bom` aligns the modules on top of
`wasichai-bom`. Every pull request runs the same guard as "Publish dry run (Maven)". See
`docs/development/releasing.md`. REST change: none. New tests: the publication guard in CI.

## 2026-11-04 — Checked provider addresses are pinned; rate limit test on virtual time

Provider calls now resolve names through a guarded Netty resolver: lookups run off the event loop, every returned
address must be public unless `http.allow-private-addresses` is on, and the connection goes to the address that was
checked, so a DNS rebinding gets no second lookup ([#34](https://github.com/wasichai/integrations/issues/34),
[#48](https://github.com/wasichai/integrations/pull/48)). A blocked address surfaces as
`ConnectorException.PermissionDenied`. `RateLimitsTest` runs on virtual time instead of the wall clock. REST change:
none (discovery answers `502` for a refused address). New tests: `GuardedResolverTest`.

## 2026-11-04 — Notion and Salesforce synchronized end to end

The integration-test suite boots Wasichai, integrations, sync and both connectors on PostgreSQL 18 against two stateful
stub providers built from documented payloads ([#13](https://github.com/wasichai/integrations/issues/13),
[#29](https://github.com/wasichai/integrations/pull/29)). One ordered scenario connects both through the real OAuth
endpoints, discovers schemas, runs a bidirectional definition both ways without echoes, takes a signed Notion webhook
once, raises and resolves a MANUAL conflict, refreshes an expired token, waits out 429s, survives a worker lease
takeover, reconciles a silent Salesforce deletion and checks a second tenant sees nothing. `SalesforceConfig` accepts an
`http://127.0.0.1` login URL, as `NotionConfig` does. REST change: none. New tests: `NotionSalesforceSyncTest`,
`SalesforceConfigTest`.

## 2026-11-04 — Sync reconciliation, scheduling and monitoring

`SyncScheduler` starts incremental and reconcile runs of every tenant's ACTIVE definitions under a cluster lock,
reconcile first ([#12](https://github.com/wasichai/integrations/issues/12),
[#28](https://github.com/wasichai/integrations/pull/28)). RECONCILE runs read both sides whole without moving cursors
and count records gone without a feed entry (`missing`) and drift nobody saw (`diverged`); a checkpoint the provider
refuses is reset once (`cursorResets`). Runs record the error kind (`transient` or `permanent`); meters
`integrations.sync.runs` and `integrations.sync.reconcile`. REST change: `GET /sync-definitions/{id}/status`; runs gain
`missing`, `diverged`, `cursorResets`, `errorKind`. New tests: `SyncMonitoringTest`, `SyncScheduleTest`.

## 2026-11-04 — integrations-ui: lockfile committed, frozen installs in CI

`yarn.lock` is committed (recovered from the CI log of the previous run) and CI installs with `--frozen-lockfile` and a
yarn cache ([integrations-ui#7](https://github.com/wasichai/integrations-ui/pull/7)). REST change: none. New tests:
none.

## 2026-11-04 — integrations-ui: sync definitions wizard, runs and conflict resolution

`@wasichai/integration-sync` (`integrationSyncModule()`) adds the definitions list, a wizard for both sides, direction,
delete policy, schedule, field mappings with transforms, owners and strategies, validation shown per field, activation
and run now; a definition page with runs and open conflicts; and a conflicts page that resolves with A, B or custom
values ([integrations-ui#3](https://github.com/wasichai/integrations-ui/issues/3),
[integrations-ui#6](https://github.com/wasichai/integrations-ui/pull/6)). Reads of the integrations module are copied
`queryOptions` factories. REST change: none (consumes #26 and #27). New tests: `DefinitionWizardPage`, `DefinitionPage`,
`ConflictsPage`, `api`, `draft`, `boundaries`, `i18n`, `module`.

## 2026-11-04 — integrations-ui: catalog, connections, webhooks, events and health

`@wasichai/integrations` (`integrationsModule()`) adds the connector catalog with maturity badges, the connector page
with the provider-app form and connect flows, connections with test and disconnect, the OAuth return message, resources
and schemas, webhook endpoints with the admin-only verification token, events with cursor paging and replay, and a
health page with dead-job retry; admin-only actions are hidden from members
([integrations-ui#2](https://github.com/wasichai/integrations-ui/issues/2),
[integrations-ui#5](https://github.com/wasichai/integrations-ui/pull/5)). REST change: none (consumes #20 to #23). New
tests: `CatalogPage`, `ConnectorPage`, `ConnectionsPage`, `ConnectionPage`, `HealthPage`, `boundaries`, `i18n`,
`module`.

## 2026-11-04 — Sync engine with links, snapshots and conflicts

The sync engine runs as a durable job, one run per definition at a time
([#11](https://github.com/wasichai/integrations/issues/11), [#27](https://github.com/wasichai/integrations/pull/27)).
Record links pair ids per definition, snapshots keep the last agreed pair per mapping, cursors are saved per page;
changes are compared by hash (echoes skipped) and merged per field by owner and strategy, a MANUAL clash holding the
link with one open conflict (`FIELDS` or `DELETED`). Creates commit a PENDING_CREATE link first; webhook events enqueue
runs; delete policies apply. Decisions refined in [ADR-0015](adr/0015-sync-implementation-decisions.md). REST change:
`POST /sync-definitions/{id}/run`, `GET /sync-definitions/{id}/runs`, `GET /conflicts`, `GET /conflicts/{id}`, `POST
/conflicts/{id}/resolve`. New tests: `SyncEngineTest`, `MergePlannerTest`,
`WasichaiIntegrationsSyncAutoConfigurationTest`.

## 2026-11-04 — integrations-ui: workspace, tooling and CI

The integrations-ui repository gets a yarn 1 workspace on wasichai-ui's conventions, commitlint and husky, release and
ordering tools, CI on Node 26 and contributor docs pointing here
([integrations-ui#1](https://github.com/wasichai/integrations-ui/issues/1),
[integrations-ui#4](https://github.com/wasichai/integrations-ui/pull/4)). REST change: none. New tests:
`check-release.test.mjs`, `run-ordered.test.mjs`.

## 2026-11-04 — Sync definitions, field mappings and transforms

New module `wasichai-integrations-sync` with its starter and switch `wasichai.integrations.sync.enabled`
([#10](https://github.com/wasichai/integrations/issues/10), [#26](https://github.com/wasichai/integrations/pull/26)). A
definition pairs a resource on two connections with a direction, delete policy, schedules, optional `matchOn` and field
mappings (owner, conflict strategy, one of seven transforms; those without an inverse need owner A). Validation checks
the shape on save and both providers' schemas on validate and activate. REST change: `/sync-definitions` create, list,
get, replace (`If-Match`), delete, `validate`, `activate` (`422`), `deactivate`. New tests: `SyncDefinitionApiTest`,
`TransformsTest`, `FieldValuesTest`, `CompatibilityTest`.

## 2026-11-04 — Salesforce connector

`integrations-connector-salesforce`: OAuth web server flow with PKCE and revocation, API version discovery, describe
mapped to typed fields, SOQL reads with `nextRecordsUrl` paging, a change feed on `SystemModstamp` plus the deleted
endpoint, writes with `If-Unmodified-Since`, upsert by external id, and Salesforce error codes mapped to the SDK
taxonomy; no Change Data Capture yet ([ADR-0014](adr/0014-salesforce-change-capture.md),
[#9](https://github.com/wasichai/integrations/issues/9), [#25](https://github.com/wasichai/integrations/pull/25)). REST
change: none. New tests: `SalesforceConnectorTest`, `SalesforceContractTest`, `SoqlTest`.

## 2026-11-04 — Notion connector

`integrations-connector-notion` on `Notion-Version: 2025-09-03`: OAuth (basic client auth, no PKCE) and internal tokens,
discovery of data sources, typed page properties both ways, a change feed on `last_edited_time`, writes with a
read-and-compare version check, trash on delete, the webhook verification handshake and HMAC-SHA256 signatures
([#8](https://github.com/wasichai/integrations/issues/8), [#24](https://github.com/wasichai/integrations/pull/24)). REST
change: none. New tests: `NotionConnectorTest`, `NotionWebhooksTest`.

## 2026-11-04 — Inbound webhooks, Event Envelope and events API

Webhook endpoints per connection and a public delivery route that verifies the raw body before parsing, refuses
oversized bodies, unknown endpoints and bad signatures without storing anything, and answers handshakes
([#7](https://github.com/wasichai/integrations/issues/7), [#23](https://github.com/wasichai/integrations/pull/23)).
Accepted events are stored as Event Envelopes with their processing job in one transaction, deduplicated, and handed to
`IntegrationEventListener`s; unhandled events are `IGNORED`, never dropped. REST change: webhook endpoints, verification
token, `POST /webhooks/{connectorId}/{endpointId}`, `GET /events` (keyset), `GET /events/{id}`, `POST
/events/{id}/replay`. New tests: `WebhookApiTest`.

## 2026-11-04 — Provider rate limits, durable jobs, discovery and health

A per-connection token bucket from the connector's declared limit plus a pause on `Retry-After` (per replica),
Micrometer meters, and the PostgreSQL job queue: one active job per dedupe key, `SKIP LOCKED` claims, leases, backoff
and `DEAD` after the last attempt ([ADR-0008](adr/0008-durable-jobs-in-postgresql.md),
[#6](https://github.com/wasichai/integrations/issues/6), [#22](https://github.com/wasichai/integrations/pull/22)). REST
change: `GET /connections/{id}/resources`, `.../resources/{resource}/schema` (provider failures as `429`, `409`, `502`),
`GET /jobs`, `POST /jobs/{id}/retry`, `GET /health`. New tests: `JobQueueTest`, `JobRunnerTest`, `RateLimitsTest`.

## 2026-11-04 — Connections, OAuth with PKCE and token refresh

Connections with sealed credentials, OAuth 2.0 authorization code with PKCE and single-use state rows, a public callback
that redirects only to the configured return url with a status code, API key, basic and client-credentials connections
checked before storing, one connection per provider account, token refresh serialised per connection, best-effort
revocation and an SSRF-guarded HTTP client ([ADR-0006](adr/0006-oauth-flows.md),
[#5](https://github.com/wasichai/integrations/issues/5), [#21](https://github.com/wasichai/integrations/pull/21)). REST
change: `/connections` list, create, get, delete, `test`, `oauth/start`, `GET /oauth/callback`. New tests:
`ConnectionApiTest`.

## 2026-11-04 — Integrations module, credential vault, connector registry and catalog

New module `wasichai-integrations` with its starter and switch `wasichai.integrations.enabled`
([#4](https://github.com/wasichai/integrations/issues/4), [#20](https://github.com/wasichai/integrations/pull/20)).
`CredentialCipher` seals secrets with AES-256-GCM bound to tenant, table, row and column under a key ring
([ADR-0005](adr/0005-secret-management.md)); the connector registry validates every manifest at start. REST change: `GET
/connectors`, `/connectors/{id}`, `/connectors/{id}/capabilities`, `/provider-configs` (PUT with `If-Match`, secret
write-only). New tests: `CredentialCipherTest`, `ConnectorRegistryTest`, `ProviderConfigApiTest`,
`WasichaiIntegrationsAutoConfigurationTest`.

## 2026-11-04 — Connector SDK, manifest validator and test kit

`integrations-connector-sdk`: the `Connector` with optional capability interfaces, typed field values, `ProviderHttp`,
the `ConnectorException` taxonomy and the manifest with per-capability maturity checked by `ManifestValidator`;
`integrations-connector-sdk-test`: a deterministic stub provider server and an abstract contract test
([ADR-0002](adr/0002-connector-sdk-contracts.md), [ADR-0004](adr/0004-connector-manifest-versioning-and-maturity.md),
[#3](https://github.com/wasichai/integrations/issues/3), [#19](https://github.com/wasichai/integrations/pull/19)). REST
change: none. New tests: `ManifestValidatorTest`, `HttpErrorsTest`, `SampleConnectorContractTest`.

## 2026-11-04 — Phase 0: discovery, competitive analysis, architecture and the first fourteen decisions

Wasichai 1.10.0 and wasichai-ui 1.7.0 were inspected and eleven integration products researched
([#2](https://github.com/wasichai/integrations/issues/2), [#18](https://github.com/wasichai/integrations/pull/18)). The
[discovery report](product/discovery-report.md) answers the thirteen Phase 0 questions; the [competitive
analysis](product/competitive-analysis.md) finds managed OAuth and MCP commoditized and bidirectional conflict handling
the gap. The architecture (control and data plane inside a modular monolith, connector SDK, vault, PostgreSQL job queue,
Event Envelope, sync semantics) is in `docs/architecture/`, the decisions in
[ADR-0001](adr/0001-repositories-and-module-boundaries.md)–[ADR-0014](adr/0014-salesforce-change-capture.md), and the
work in the [backlog](product/backlog.md). REST change: none (the API is specified, not built). New tests: none.

## 2026-11-04 — Build skeleton and CI

Gradle build on Wasichai's conventions, the connector SDK's first type and a boot smoke test against PostgreSQL 18
([#1](https://github.com/wasichai/integrations/issues/1), [#17](https://github.com/wasichai/integrations/pull/17)). REST
change: none. New tests: `SdkVersionTest`, `AppBootsTest`.
