# Change history

Newest first. Architectural reasoning lives in `docs/adr/`; this file records what shipped.

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
