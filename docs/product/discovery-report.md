# Phase 0 discovery report

**Date:** 2026-11-04. **Inspected:** [wasichai](https://github.com/wasichai/wasichai) 1.10.0 (`main`),
[wasichai-ui](https://github.com/wasichai/wasichai-ui) 1.7.0 (`main`), [full-sample](https://github.com/wasichai/full-sample)
(README only, no code yet), [wasichai-infrastructure](https://github.com/wasichai/wasichai-infrastructure) (Compose:
PostgreSQL 18 + PostGIS + pgvector). Paths below are relative to those repositories.

**Neither wasichai nor wasichai-ui is modified.** Everything lands in two new repositories:
[wasichai/integrations](https://github.com/wasichai/integrations) (backend modules + docs) and
[wasichai/integrations-ui](https://github.com/wasichai/integrations-ui) (React packages), consuming Wasichai's published
artifacts from GitHub Packages.

## What Wasichai is today

- Libraries, not an app: starters + BOM + explicit auto-configuration, no component scanning of library code
  ([ADR-024](https://github.com/wasichai/wasichai/blob/main/docs/adr/0024-libraries-and-starters.md)); the core is
  extended only through SPIs ([ADR-025](https://github.com/wasichai/wasichai/blob/main/docs/adr/0025-extension-spis.md));
  each module owns its Flyway history ([ADR-026](https://github.com/wasichai/wasichai/blob/main/docs/adr/0026-per-module-migrations.md)).
- Stack: Kotlin 2.4.21, Spring Boot 4.1.2 WebFlux, Flyway 13.11.1, Testcontainers 2.0.5, Jackson 3, JDK 25
  (`gradle/libs.versions.toml`). SQL only through `DatabaseClient`; JPA and Spring Data are forbidden (`CLAUDE.md`).
- Tenancy by `organization_id` from the JWT: `wasichai-core/src/main/kotlin/wasichai/core/identity/CurrentUser.kt`.
- Module recipe: `wasichai-notifications/` (`autoconfigure/WasichaiNotificationsAutoConfiguration.kt`,
  `NotificationsProperties.kt`, `META-INF/spring/…AutoConfiguration.imports`, `db/wasichai/notifications/V*.sql`).

## 1. What can Wasichai reuse directly?

| Building block | Source | Use in integrations |
|---|---|---|
| `CurrentUser`, `AuthenticatedUser` | `core/identity/CurrentUser.kt` | tenant + permission on every request |
| `RecordService.asPlatform`, `TenantDirectory` | `core/data/PlatformCaller.kt`, `core/platform/TenantDirectory.kt` | background work per tenant |
| `ClusterLock` (advisory locks) | `core/platform/ClusterLock.kt` | one replica per schedule/definition |
| `ChangeOrigin.within(source, correlationId)` | `core/platform/ChangeOrigin.kt` | source `integration:<…>` on audit rows, MDC |
| `WasichaiException` family, ProblemDetail handler | `core/common/Errors.kt`, `GlobalExceptionHandler.kt` | error contract |
| `PageResponse`, cursor conventions | `core/common/PageResponse.kt`, `docs/api/rest.md` | list endpoints |
| `ModuleMigration`, `WasichaiSchemas`, `SqlIdentifier` | `core/platform/` | migrations, schema names |
| `AuditService` | `core/audit/AuditService.kt` | admin trail for connection/definition changes |
| SKIP LOCKED claim pattern | `automation/AutomationRunRepository.kt` | durable job queue |
| SSRF guard logic | `automation/WebhookSender.kt` | outbound provider HTTP |
| Delivery rows with backoff | notifications `DeliveryWork` | retry pattern for jobs |
| `RecordChangeListener` | `core/data/RecordChanges.kt` | future: Wasichai records as a sync side |
| Test base | `wasichai-test/…/WasichaiIntegrationTest.kt`, `WasichaiTestDatabase.kt`, `WasichaiContextRunner.kt` | all integration tests |

## 2. Which existing modules should be extended?

None in this phase. Wasichai has no encryption at rest, OAuth client, inbound webhook receiver, generic job queue with
leases, metrics or MCP (keyword survey: no `connector`, `credential` store or `mcp` code). Each is built inside the
integrations modules first; moving a piece into Wasichai later needs a second user there and a Wasichai ADR.
`wasichai-automation` (record triggers, `WEBHOOK` action) and `wasichai-workflow` (a state machine on the record,
"no BPM engine" in `workflow/Workflow.kt`) stay as they are; an `automation` action that calls a connector is a later,
separate decision.

## 3. Which new backend modules are necessary?

In `wasichai/integrations` (Gradle, same conventions as Wasichai):

- `integrations-connector-sdk`: contracts only, no Spring.
- `integrations-connector-sdk-test`: contract-test kit and stub provider server.
- `wasichai-integrations`: registry, connections, credential vault, OAuth, HTTP runtime, jobs, webhooks, events, REST.
- `wasichai-integrations-sync`: definitions, mappings, links, snapshots, engine, conflicts, reconciliation.
- `integrations-connector-notion`, `integrations-connector-salesforce`.
- `starters/wasichai-spring-boot-starter-integrations`, `…-integrations-sync`.
- `integrations-integration-tests` (never published): full-app suites incl. the Notion ↔ Salesforce scenario.

## 4. Which new frontend packages are necessary?

In `wasichai/integrations-ui` (yarn workspace, same tooling as wasichai-ui): `@wasichai/integrations` (catalog,
connections, connect flow, health) and `@wasichai/integration-sync` (definitions, mapping, runs, conflicts). Workflow,
admin and AI packages are later. Template: `wasichai-ui/packages/automation/src/` (`module.tsx`, `api.ts`, `types.ts`,
`i18n.ts`, `boundaries.test.ts`, `i18n.test.ts`, `module.test.tsx`). A new nav group needs its own `navGroups` entry
(`packages/core/src/app/coreModule.ts` only declares `data`, `builder`, `automation`, `administration`).

## 5. Which existing APIs and SPIs are relevant?

- Backend: `CurrentUser`, `RecordService.asPlatform`, `TenantDirectory`, `ClusterLock`, `ChangeOrigin`,
  `ModuleMigration`, `AuditService`, `RecordChangeListener`, the error types; REST conventions in `docs/api/rest.md`
  (problem+json, `X-Correlation-Id`, `page/size` and `after` cursors, `If-Match`, `Idempotency-Key`); security chain
  `autoconfigure/WasichaiSecurityAutoConfiguration.kt` (`@Order(0)`; a module adds a `securityMatcher` chain below 0 for
  its public routes, `docs/architecture/overview.md`).
- Frontend: `WasichaiModule` (`packages/core/src/registry/contract.ts`), `createRegistry` validation, `api<T>()` and
  `ApiError` (`packages/core/src/api/client.ts`), `useWasichaiLinks`, `useAuth`/`CallerPermissions`, `QueryState`,
  `EmptyState`, `ErrorState`, `PageHeader`, `@wasichai/ui` primitives, `renderWithProviders` and `mockFetch`
  (`packages/testing/src/`).

## 6. What connector SDK contract should be adopted?

A manifest plus optional capability interfaces, checked against each other at registration ([ADR-0002](../adr/0002-connector-sdk-contracts.md),
[ADR-0004](../adr/0004-connector-manifest-versioning-and-maturity.md)): `Authenticator`, `ResourceDiscovery`,
`ResourceReader` (pages, cursors, incremental `since`), `ResourceWriter` (create/update/patch/delete/upsert by external
id, optional expected version), `WebhookHandler` (verify raw body, parse native events), `ActionExecutor`. A sealed
`ConnectorError` tells the runtime what is retryable. Connectors receive an authenticated `ProviderHttp` and never see
other tenants' credentials. Provider-native schemas are kept; canonical models are optional helpers.

## 7. How should the event and synchronization models work?

Inbound changes (webhooks, polling) are stored as an Event Envelope that keeps the provider's native payload and
distinguishes provider event id, internal id and logical record identity ([event model](../architecture/event-model.md)).
Webhook events are signals: they enqueue an incremental read, so ordering and completeness are never trusted to
delivery. The sync engine keeps durable record links and the last agreed snapshot per pair, diffs per field, detects
echoes of its own writes by hash, resolves concurrent edits per field by ownership or strategy, and stores a conflict
instead of overwriting ([sync semantics](../architecture/sync-semantics.md)).

## 8. How can the platform support self-hosting?

It is libraries inside the app: one Spring Boot deployable and PostgreSQL 18, nothing else. Jobs and schedules run on
PostgreSQL (SKIP LOCKED, advisory locks), no broker and no Temporal ([ADR-0008](../adr/0008-durable-jobs-in-postgresql.md)).
Credential keys come from configuration or the environment through a `CredentialKeyProvider` SPI, with a seam for a
KMS ([ADR-0005](../adr/0005-secret-management.md)). Compose, images, Pulumi and k3s come in Phase 8.

## 9. How should connector extensions be distributed and versioned?

As Maven artifacts on the app's classpath, registered as beans by their own auto-configuration; semantic versions; an
`sdkVersion` (`major.minor`) checked at startup: same major, minor not newer than the runtime
([ADR-0012](../adr/0012-connector-distribution-and-versioning.md)). They are trusted code; untrusted connectors need a
remote execution protocol, deferred ([ADR-0003](../adr/0003-in-process-trusted-connectors.md)).

## 10. What should be included in the first MVP?

Build + CI; SDK + contract kit; vault, registry, catalog; connections with OAuth/PKCE, API key, basic, client
credentials, refresh; HTTP runtime, durable jobs, discovery, health; webhooks + events; Notion and Salesforce
connectors; sync definitions, mappings, engine, conflicts, reconciliation; the Notion ↔ Salesforce end-to-end suite; UI
packages for connections and sync. See the [backlog](backlog.md).

## 11. What should explicitly be excluded from the MVP?

Jira, GitHub, Slack and Generic OpenAPI connectors; integration workflows (branches, delays, approvals); MCP tools;
Salesforce Pub/Sub CDC (polling first, [ADR-0014](../adr/0014-salesforce-change-capture.md)); bulk APIs; attachments
and Notion block content; currency conversion; arbitrary expressions or user code; untrusted connector isolation;
connector marketplace and billing; Pulumi/k3s.

## 12. What are the most important architectural risks?

1. **Over-abstract data model**: a universal entity would lose provider semantics. Mitigation: native schemas, mapping
   per field, no canonical model in the MVP.
2. **Silent data loss in bidirectional sync**: concurrent edits, echoes, missed webhooks. Mitigation: snapshots,
   per-field conflicts, hash echo detection, reconciliation.
3. **Duplicate creates** after a timeout on a successful write. Mitigation: pending links with idempotency tokens,
   external-id upsert where the provider has it.
4. **Secrets**: no encryption in Wasichai today. Mitigation: AES-256-GCM vault with key ids, masked everywhere.
5. **Provider drift**: API versions change (Notion data sources in `2025-09-03`, Salesforce External Client Apps since
   Spring '26). Mitigation: pinned versions, contract tests, runtime version discovery for Salesforce.
6. **Rate limits** (Notion ~3 req/s). Mitigation: per-connection token bucket, `Retry-After`, backoff in jobs.
7. **Dependency on GitHub Packages access** for both repositories' CI.
8. **Public endpoints** (OAuth callback, webhooks) as attack surface. Mitigation: unguessable endpoint ids, signature
   check before parsing, single-use state.

## 13. How should implementation be divided into reviewable pull requests?

One issue, one pull request, merged only when CI unit and integration jobs are green; backend before the UI that uses
it. Order and acceptance criteria are in the [backlog](backlog.md): wasichai/integrations#1 → #13 (#14 stretch), then
wasichai/integrations-ui#1 → #3 as their backend lands.
