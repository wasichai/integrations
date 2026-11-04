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

## Connectors

- `integrations-connector-notion`: OAuth, data sources, page properties, webhooks (HMAC), rate limits.
- `integrations-connector-salesforce`: OAuth + PKCE, describe, SOQL, CRUD, external-id upsert, incremental polling.

## Frontend (wasichai/integrations-ui)

- `@wasichai/integrations`: catalog, connector detail, connections, connect flow, provider configs, health.
- `@wasichai/integration-sync`: definitions wizard, mapping editor, runs, conflicts.

Both register through `WasichaiModule` with their own nav group and es/en bundles.
