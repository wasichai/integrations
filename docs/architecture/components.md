# Components

## integrations-connector-sdk

- `Connector`: `manifest` plus optional capability interfaces, discovered by type.
- `ConnectorManifest`: id, provider name, semver version, `sdkVersion`, auth methods, OAuth scopes and permissions,
  resources, actions, triggers, sync directions, pagination and filtering support, rate limits, webhook support, and a
  maturity per capability (`DECLARED`, `IMPLEMENTED`, `TESTED`).
- `ManifestValidator`: manifest ⇔ implemented interfaces, id and version syntax, `sdkVersion` compatibility.
- Capabilities: `Authenticator`, `ResourceDiscovery`, `ResourceReader`, `ResourceWriter`, `WebhookHandler`,
  `ActionExecutor`.
- `ConnectorContext`: an authenticated `ProviderHttp`, a clock, connection metadata; no access to other connections.
- `ConnectorError` (sealed): `Transient`, `RateLimited(retryAfter)`, `AuthExpired`, `PermissionDenied`, `NotFound`,
  `Conflict(currentVersion)`, `Permanent`. The runtime retries only what the type says is retryable.

## integrations-connector-sdk-test

A stub HTTP provider server (reactor-netty, already on the WebFlux classpath) and an abstract `ConnectorContractTest`
each connector extends: manifest valid, discovery returns described resources, reads page and resume from cursors,
writes round-trip, error responses map to the right `ConnectorError`.

## wasichai-integrations

| Component | Responsibility |
|---|---|
| `ConnectorRegistry` | collects `Connector` beans, validates manifests at startup, refuses duplicates |
| `CredentialVault` + `CredentialKeyProvider` | AES-256-GCM encryption with key ids; rotation |
| `ProviderConfigService` | per-tenant OAuth clients (secret write-only) |
| `ConnectionService` | lifecycle, test, disconnect, revoke, status and health |
| `OAuthFlow` | authorization URL, PKCE, state rows, callback, token exchange, refresh |
| `ProviderHttp` (runtime) | WebClient, SSRF guard, token bucket, `Retry-After`, error mapping, redaction, metrics |
| `JobQueue` + `JobDrain` | durable jobs with dedupe keys, leases, backoff, dead letter |
| `WebhookIngress` | public endpoint, verification, Event Envelope store, dedupe, job enqueue |
| REST controllers | catalog, provider configs, connections, discovery, events, jobs, health |

## wasichai-integrations-sync

| Component | Responsibility |
|---|---|
| `SyncDefinitionService` | definitions, field mappings, validation against discovered schemas, activation |
| `Transforms` | the closed transform set, both directions |
| `SyncEngine` | per-change pipeline: diff, echo check, ownership/conflict, write, persist |
| `LinkStore` | record links, snapshots, pending creates |
| `SyncScheduler` | cluster-locked incremental and reconciliation schedules |
| `Reconciler` | full comparison, deletes and divergence |
| `ConflictService` | list and resolve conflicts |

## wasichai-integrations-workflows

Integration workflows ([ADR-0022](../adr/0022-integration-workflows.md)): definitions, immutable versions and
validation. Nothing executes yet ([#42](https://github.com/wasichai/integrations/issues/42)).

| Component | Responsibility |
|---|---|
| `WorkflowGraph` | the graph model (trigger, steps, edges, operands) parsed from JSON with field-located violations |
| `Condition` | the closed condition set (`equals`, `notEquals`, `exists`, `in`, `gt`, `lt`, `and`, `or`, `not`) |
| `WorkflowValidator` | shape, edges, reachability, no cycles, references upstream, tenant connections, manifests, resource schemas |
| `WorkflowService` / `WorkflowController` | drafts with optimistic locking, validate, publish to the next version, version reads |
| `WorkflowRepository` | definitions, versions, the published trigger; `ConnectionUsage` for connections a workflow uses |

## Connectors

- `integrations-connector-notion`: OAuth, data sources, page properties, webhooks (HMAC), rate limits.
- `integrations-connector-salesforce`: OAuth + PKCE, describe, SOQL, CRUD, external-id upsert, incremental polling.
- `integrations-connector-jira`: Jira Cloud by OAuth 2.0 (3LO, through the API gateway) or API token; projects and
  issue fields (custom ones included), enhanced JQL search, incremental polling on `updated`, issue CRUD with rich text
  as plain-text ADF, transitions and dynamic webhooks as actions, webhooks verified by HMAC or the app's JWT
  ([ADR-0016](../adr/0016-jira-cloud-connector.md)).
- `integrations-connector-github`: github.com or GitHub Enterprise Server by OAuth app or GitHub App user token, or a
  personal access token; repositories and their issues (pull requests left out), `Link` paging, a fresh-query feed on
  `updated_at`, issue create and edit (no delete in the REST API), webhooks verified by `X-Hub-Signature-256`
  ([ADR-0019](../adr/0019-github-connector.md)).
- `integrations-connector-slack`: Slack by OAuth v2 (bot token, token rotation) or a bot token; channels the bot is in
  and their messages, cursor paging, a feed of new messages by `oldest` (edits and deletions through events), post, edit
  and delete as text, Events API deliveries verified by `X-Slack-Signature` ([ADR-0020](../adr/0020-slack-connector.md)).
- `integrations-connector-openapi`: any REST API described by an OpenAPI 3 document (JSON or YAML) plus an explicit
  mapping written in code: list/get/create/update/delete operations per resource, fields derived from the response
  schema, page, cursor or next-url paging, an incremental feed on an updated-at field; capabilities only for what the
  mapping provides, no webhooks, no actions ([ADR-0021](../adr/0021-openapi-connector.md)).

## Frontend (wasichai/integrations-ui)

- `@wasichai/integrations`: catalog, connector detail, connections, connect flow, provider configs, health.
- `@wasichai/integration-sync`: definitions wizard, mapping editor, runs, conflicts.

Both register through `WasichaiModule` with their own nav group and es/en bundles.
