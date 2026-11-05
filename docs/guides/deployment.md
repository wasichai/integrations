# Deployment guide

How to add Wasichai Integrations to a Wasichai app and configure it: dependencies, every property, encryption keys,
the public url, OAuth, provider apps, webhooks and scaling; and how to run the example app's container image with
Docker Compose ([below](#the-example-app-and-its-container-image)). Operating it day to day is in
[operations](operations.md).

## Dependencies

The modules are published to GitHub Packages (`https://maven.pkg.github.com/wasichai/integrations`, group `wasichai`),
all on one version aligned by `integrations-bom`, which itself imports the `wasichai-bom` line they are built against
(Wasichai 1.10.0). See [releasing](../development/releasing.md).

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/wasichai/wasichai")
        credentials { username = "token"; password = System.getenv("WASICHAI_PACKAGES_TOKEN") }
    }
    maven {
        url = uri("https://maven.pkg.github.com/wasichai/integrations")
        credentials { username = "token"; password = System.getenv("WASICHAI_PACKAGES_TOKEN") }
    }
}

dependencies {
    implementation(platform("wasichai:integrations-bom:<version>"))
    implementation("wasichai:wasichai-spring-boot-starter-integrations-sync")   // or ...-starter-integrations without sync
    implementation("wasichai:integrations-connector-notion")
    implementation("wasichai:integrations-connector-salesforce")
}
```

The token needs `read:packages`. What each artifact brings:

| Artifact | Contents |
|---|---|
| `wasichai-spring-boot-starter-integrations` | the Wasichai starter plus `wasichai-integrations` (connections, OAuth, webhooks, events, jobs, health) |
| `wasichai-spring-boot-starter-integrations-sync` | the above plus `wasichai-integrations-sync` (definitions, engine, scheduler, conflicts) |
| `wasichai-spring-boot-starter-integrations-workflows` | the integrations starter plus `wasichai-integrations-workflows` (definitions, versions, validation, execution, triggers, approvals) |
| `wasichai-spring-boot-starter-integrations-ai` | the integrations starter plus `wasichai-integrations-ai` (AI tool catalog, execution policy, approvals) |
| `wasichai-integrations`, `wasichai-integrations-sync`, `wasichai-integrations-workflows`, `wasichai-integrations-ai` | the modules alone, for an app that assembles Wasichai itself |
| `integrations-connector-notion`, `-salesforce`, `-jira`, `-github`, `-slack` | the shipped connectors (no Spring) |
| `integrations-connector-salesforce-pubsub` | the Salesforce connector with Change Data Capture over the Pub/Sub API (brings gRPC, protobuf, Avro) |
| `integrations-connector-openapi` | a connector for any REST API with an OpenAPI 3 document, configured in code (no Spring) |
| `integrations-connector-sdk`, `integrations-connector-sdk-test` | for writing and testing connectors |

Then declare the connectors you want as beans; adding a connector dependency registers nothing by itself
([ADR-0017](../adr/0017-connectors-as-plain-libraries.md)):

```kotlin
@Configuration
class ConnectorsConfiguration {
    @Bean
    fun notionConnector(): NotionConnector = NotionConnector()

    @Bean
    fun salesforceConnector(): SalesforceConnector = SalesforceConnector()
}
```

`SalesforceConfig(loginUrl = "https://test.salesforce.com")` targets sandboxes (or a My Domain url), and `apiVersion`
pins a REST API version (`"66.0"`; by default the instance's newest). `NotionConfig` pins the `Notion-Version` header
(`2025-09-03`). `JiraConfig(siteUrl = "https://acme.atlassian.net")` enables API token connections against that site,
and `cloudId` pins one site when an OAuth grant reaches several ([ADR-0016](../adr/0016-jira-cloud-connector.md)). Each
connector bean is validated at start-up; an invalid manifest or a duplicate id stops the app.

### Salesforce Change Data Capture

Polling is the default and needs nothing in Salesforce. To read changes of some objects through Change Data Capture
instead ([ADR-0024](../adr/0024-salesforce-cdc-pub-sub.md)), depend on `integrations-connector-salesforce-pubsub` and
declare its connector **instead of** `SalesforceConnector` (both have the id `salesforce`, so existing connections keep
working):

```kotlin
@Bean
fun salesforceConnector(): SalesforcePubSubConnector =
    SalesforcePubSubConnector(
        SalesforcePubSubConfig(channels = mapOf("Account" to "/data/AccountChangeEvent", "Contact" to "/data/ContactChangeEvent")),
        SalesforceConfig()
    )
```

In each tenant's org:

1. Change Data Capture must be available to the org (Enterprise, Unlimited and Developer editions include it for a
   limited number of objects; more objects need the add-on license).
2. In Setup → Integrations → Change Data Capture, select the objects to synchronize. They publish on
   `/data/<Object>ChangeEvent` (custom objects: `/data/<Object>__ChangeEvent`); a custom channel (`/data/<Name>__chn`)
   or `/data/ChangeEvents` (all selected objects) works too, and is what `channels` names per object.
3. The connected user needs `API Enabled` and read access to those objects ("View All" on an object for its change
   events, as Salesforce documents); the OAuth scope `api` covers the Pub/Sub API.

Objects missing from `channels` keep polling. The connector reaches `api.pubsub.salesforce.com:7443` over TLS (open the
egress, `SalesforcePubSubConfig.endpoint` changes it). Each sync run opens one short gRPC subscription per CDC object
and waits up to `drain` (5 s) for events. A replay id Salesforce no longer keeps (72-hour retention), a gap event or a
changed channel starts that side over with a full read and the reconcile checks (`cursorResets` on the run, the
`integrations.sync.reconcile{finding=cursor_reset}` counter). A missing entitlement or an unknown channel fails the run
with `PermissionDenied`.

The modules need what the Wasichai starter provides: PostgreSQL through R2DBC, Wasichai's JWT security and
`CurrentUser`, `WasichaiSchemas`, and the cluster lock. Each module runs its own Flyway history: `integrations`
(`db/wasichai/integrations`, after every Wasichai module), `integrations_sync` (`db/wasichai/integrations-sync`),
`integrations_workflows` (`db/wasichai/integrations-workflows`) and `integrations_ai` (`db/wasichai/integrations-ai`), in
the Wasichai metadata schema. Nothing has to be run by hand.

## Properties

### `wasichai.integrations.*`

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` turns the module (and the sync, workflows and AI modules with it) off; its routes answer `404` |
| `public-base-url` | none | how providers reach this app, e.g. `https://app.example.com`; needed for OAuth and webhooks |
| `encryption.keys.<id>` | none | base64 of 32 random bytes (AES-256) per key id; **required** |
| `encryption.active-key` | none | the key id new secrets are sealed with; **required**, must be one of `keys` |
| `oauth.return-url` | `/` | where the browser lands after the OAuth callback (the UI's connections page) |
| `oauth.state-ttl` | `10m` | how long an OAuth start stays valid; must be positive |
| `oauth.refresh-skew` | `60s` | refresh an access token this long before it expires |
| `http.allow-private-addresses` | `false` | allow provider calls to private, loopback or link-local addresses (tests, labs only) |
| `http.timeout` | `30s` | per provider call; a timeout is a transient failure |
| `http.max-response-bytes` | `16777216` (16 MiB) | the largest provider response read |
| `http.rate-limit.shared` | `true` | one token bucket per connection and per AI tool in PostgreSQL for every replica, and provider pauses (`Retry-After`) they all honour ([ADR-0030](../adr/0030-shared-rate-limits-and-kubernetes.md)); `false`: each replica limits on its own |
| `jobs.worker-enabled` | `true` | whether this replica runs jobs |
| `jobs.poll-interval` | `1s` | how often the worker claims due jobs |
| `jobs.batch` | `20` | jobs claimed per poll, run concurrently |
| `jobs.lease` | `5m` | a job still `RUNNING` after this is taken to have lost its worker and is claimed again |
| `jobs.backoff` | `30s` | first retry delay, doubled per attempt |
| `jobs.max-backoff` | `1h` | ceiling of the retry delay |
| `webhooks.max-body-bytes` | `1048576` (1 MiB) | a larger delivery is refused with `413` before parsing |
| `retention.enabled` | `true` | run the retention purge on this replica (cluster-locked: one replica purges per tick) |
| `retention.interval` | `1h` | how often the purge runs; must be positive |
| `retention.initial-delay` | `5m` | the first purge after start |
| `retention.batch-size` | `1000` | rows per `DELETE`, 1–100000: short statements, short locks |
| `retention.max-batches` | `100` | batches per task and tick; what is left waits for the next tick |
| `retention.max-age.<task>` | per task | how long finished rows are kept, by task: `events` (30d), `jobs` (30d), `usage` (400d), `sync.runs` (90d), `sync.conflicts` (180d), `workflows.executions` (90d), `ai.executions` (180d); `0` keeps forever |
| `usage.flush-interval` | `30s` | how often usage counters are written; a crash loses at most this much counting |
| `export.page-size` | `200` | rows per section in one page of a connection's GDPR export |
| `export.max-page-size` | `1000` | the largest `size` a caller may ask for |

`public-base-url` must start with `http://` or `https://` when set. Use `https` in production: providers call it and
OAuth providers usually require it.

### `wasichai.integrations.sync.*`

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` turns the sync module off |
| `page-size` | `100` | records per change-feed or list page, 1–1000 |
| `scheduler-enabled` | `true` | start scheduled and reconcile runs |
| `scheduler-tick` | `30s` | how often the scheduler looks for due definitions; must be positive |

### `wasichai.integrations.workflows.*`

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` turns the workflows module off; its routes answer `404` |
| `step-max-attempts` | `5` | attempts of one step on the job queue (with its backoff) before it fails, 1–20 |
| `scheduler-enabled` | `true` | fire schedule triggers |
| `scheduler-tick` | `15s` | how often the scheduler looks for due schedules; must be positive. A firing starts at most a tick late |

### `wasichai.integrations.ai.*`

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` turns the AI tools module off; its routes answer `404`. On, it exposes nothing until a tenant administrator exposes a tool ([guide](ai-tools.md)) |
| `mcp.enabled` | `true` | `false` removes the MCP endpoint `POST /api/integrations/mcp` (`404`); the REST tool routes stay |
| `mcp.allowed-origins` | empty | browser origins that may call the MCP endpoint; any other `Origin` is `403`. Empty: no browser page may |
| `mcp.page-size` | `100` | tools per `tools/list` page, 1–1000 |

Retention ages take Spring durations (`30d`, `12h`). The task names hold dots: in YAML write
`max-age: {"[sync.runs]": 30d}` or nest them (`max-age: {sync: {runs: 30d}}`); as environment variables
`WASICHAI_INTEGRATIONS_RETENTION_MAXAGE_SYNC_RUNS=30d`. What each task deletes and never deletes is in the
[operations guide](operations.md#retention) and [ADR-0029](../adr/0029-retention-gdpr-usage.md).

An origin in `mcp.allowed-origins` (`https://agent.example.com`) must pass `wasichai.web.cors-allowed-origin-patterns`
too; MCP clients outside a browser send no `Origin`.

AI tool rate limits are one budget per tool across the cluster while `wasichai.integrations.http.rate-limit.shared` is
on (the default): n replicas together admit a tool's `rateLimitPerMinute` ([ADR-0030](../adr/0030-shared-rate-limits-and-kubernetes.md)).

A minimal `application.yml`:

```yaml
wasichai:
  integrations:
    public-base-url: https://app.example.com
    encryption:
      keys:
        k2026a: ${INTEGRATIONS_KEY_K2026A}
      active-key: k2026a
    oauth:
      return-url: https://app.example.com/integrations/connections
```

## Encryption keys and rotation

Every secret the module stores (connection credentials, provider app client secrets, webhook signing secrets, PKCE
verifiers of pending OAuth starts) is sealed with AES-256-GCM. The sealed form `v1.<keyId>.<nonce>.<ciphertext>` names
its key, and the authenticated data binds it to the tenant, table, row and column, so a value copied elsewhere does not
open ([ADR-0005](../adr/0005-secret-management.md)). The app does not start without at least one key and an active key.

Generate a key: `openssl rand -base64 32`. Key ids match `^[A-Za-z0-9_-]{1,32}$`. Keep keys in the platform's secret
store (environment variables, a Kubernetes secret) and back them up with the database: losing a key makes every secret
sealed with it unreadable for good.

An app on a KMS or vault can declare its own `CredentialKeyProvider` bean (`activeKeyId()`, `key(id)`), which replaces the
configuration-based one.

To rotate:

1. Add the new key under `encryption.keys` next to the old one and make it `active-key`; deploy everywhere.
2. New secrets are sealed with the new key; old ones still open with the old key. A secret is re-sealed when it is
   written again: connection credentials on every token refresh and reconnect, a provider app's client secret on its next
   `PUT`. Webhook signing secrets are not re-sealed (recreate the endpoint to move one).
3. There is no background re-encryption job. Keep the old key listed until no row names it; check with SQL over the
   sealed columns, e.g. `... WHERE sealed LIKE 'v1.<oldId>.%'` on `integration_connection_credentials`, and likewise
   `client_secret_sealed` on `integration_provider_configs` and `secret_sealed` on `integration_webhook_endpoints`.
4. Remove the old key. A secret still sealed with a removed key cannot be opened: calls on that connection fail until it
   is reconnected (or the provider app or webhook endpoint is saved again). Such a connection turns `NEEDS_REAUTH`
   with `lastError` "credentials cannot be decrypted with the configured keys; reconnect"
   ([api.md](../architecture/api.md#connections)).

## Public base url, OAuth and the return url

- The OAuth redirect uri is `<public-base-url>/api/integrations/oauth/callback`. Register exactly that in each provider
  app. Without `public-base-url`, starting OAuth answers `409`.
- After the provider, the callback always redirects to `oauth.return-url` with `?status=connected&connectionId=<id>` or
  `?status=error&error=<code>` ([api.md](../architecture/api.md#oauth)). It comes from configuration, never from the
  request. With [integrations-ui](https://github.com/wasichai/integrations-ui), point it at the app's connections page
  (`/integrations/connections` with the default base path).
- The callback and the webhook route are the only routes without a Wasichai token; they run on their own security chain
  (`IntegrationsSecurityConfiguration`). Make sure a reverse proxy forwards them.

## Provider apps per tenant

A connector with an OAuth method (Notion, Salesforce) needs the tenant's own app at the provider before anyone connects:
each tenant registers an OAuth app (a Notion public integration, a Salesforce connected app) with the redirect uri
above, then a tenant administrator stores it with `PUT /api/integrations/provider-configs/{connectorId}`
(`clientId`, write-only `clientSecret`, extra `scopes`, `options`). The catalog shows `needsProviderConfig` and
`providerConfigured` per connector. There is no app-wide default provider app; the stored `options` are kept and
returned for the app's own use: neither the platform nor a shipped connector reads them, and they do not reach a
connector's context. A provider app cannot be deleted while connections to its connector
exist (`409`).

Connector specifics:

- Notion: the app's client secret is required (no PKCE); the authorize url carries `owner=user`. An internal integration
  token can be used instead of OAuth (`authType: API_KEY`), for a single workspace.
- Salesforce: PKCE; default scopes `api refresh_token`; the token response's `instance_url` is kept with the connection.
- Jira: OAuth 2.0 (3LO) with the app's client secret (no PKCE, no revocation); default scopes include
  `offline_access` for a refresh token. The site is found from the grant's accessible resources on use.
- Slack: OAuth v2 with the app's client secret (no PKCE, no revocation); default bot scopes `channels:read`,
  `groups:read`, `channels:history`, `groups:history`, `chat:write`. With token rotation on, the refresh token is used
  like any other, and a refused one (`ok:false`, `invalid_refresh_token`) turns the connection `NEEDS_REAUTH`
  ([ADR-0023](../adr/0023-token-refusals-in-ok-responses.md)); a bot token (`xoxb-`) can be used instead of OAuth
  (`authType: API_KEY`), for a single workspace.

## Webhooks

Webhook endpoints need `public-base-url` too. A tenant administrator creates one per connection
(`POST /api/integrations/connections/{id}/webhook-endpoints`); its `url` is
`<public-base-url>/api/integrations/webhooks/{connectorId}/{endpointId}` and is registered at the provider. Notion
verifies an endpoint by posting a token, which an administrator reads (`GET /webhook-endpoints/{id}/verification-token`)
and pastes back at Notion; providers that show a signing secret at registration take it in the create body
(`secret`). Deliveries larger than `webhooks.max-body-bytes` are refused. Salesforce has no webhook support in the
connector: its changes are polled, or read by Change Data Capture
([ADR-0024](../adr/0024-salesforce-cdc-pub-sub.md)). For Slack, create the endpoint
with the app's signing secret first, then paste its `url` as the Events API request url: Slack's `url_verification`
challenge is answered by the endpoint ([ADR-0020](../adr/0020-slack-connector.md)).

## Workers, scheduler and scaling

All replicas share the PostgreSQL job queue (`integration_jobs`):

- Every replica with `jobs.worker-enabled=true` polls the queue. Claims use `FOR UPDATE SKIP LOCKED`, so workers on
  several replicas never take the same job; a job whose worker died is claimed again after `jobs.lease`. Turn the worker
  off on replicas that must only serve the API. At least one replica must run it, or nothing (webhook events, sync runs)
  is processed.
- Size `jobs.lease` above the longest job: a sync run that reads a large resource whole (a reconcile) must finish within
  the lease, or another worker may start it again (handlers are idempotent, but the work is repeated).
- The sync scheduler may run on every replica: each tick takes a cluster lock, so one replica enqueues per tick, and a
  definition has at most one queued or running run. It only enqueues; workers execute.
- The workflow scheduler likewise: one replica per tick (cluster lock), and a firing is claimed once in the database,
  so a schedule never fires twice. Workflow steps are jobs like any other: a step running when its replica dies runs
  again after `jobs.lease`; finished steps are never repeated ([ADR-0025](../adr/0025-workflow-execution.md)).
- The retention purge may run on every replica too: one replica purges per tick (cluster lock
  `integrations.retention`). Each replica buffers its usage counts in memory and adds them to the table every
  `usage.flush-interval` and on a graceful shutdown; a killed replica loses what it had not written yet.
- Rate limits are shared (`http.rate-limit.shared`, default `true`): each connection has one token bucket in
  PostgreSQL (`integration_rate_buckets`) that every replica draws on with one atomic upsert per provider call, so n
  replicas together keep to the connector's declared rate, and a provider's `Retry-After` pauses the connection for
  all of them. If the database does not answer within 1 s, a replica falls back to its own in-memory bucket for that
  call (counted and logged, see [operations](operations.md#rate-limits)); it never waits on the database. With the
  switch off, buckets are per replica: n replicas may reach n times the rate
  ([ADR-0030](../adr/0030-shared-rate-limits-and-kubernetes.md)).
- Provider calls are made with the app's own HTTP client; outbound access to the providers' hosts (and DNS) must be
  allowed.

## The example app and its container image

`integrations-app` is a runnable Spring Boot app built from this repository: the Wasichai starter, the integrations,
sync, workflows and AI modules, and every shipped connector but OpenAPI. It is never published to Maven; it ships as a
container image built from [`integrations-app/Dockerfile`](../../integrations-app/Dockerfile), and
[`deploy/compose/`](../../deploy/compose/) runs it with PostgreSQL 18
([ADR-0028](../adr/0028-example-app-and-image.md)). Use it to try the platform, as the base of a deployment, or as the
reference for assembling your own app (its `ConnectorsConfiguration` is the bean declaration shown above, switched by
properties).

### Build the image

The build compiles the app inside the image (Gradle on `eclipse-temurin:25-jdk`) and needs a token with
`read:packages` for the `wasichai-*` libraries. It is a BuildKit secret: mounted for the one build step, never a build
argument, an environment variable of the image or part of a layer.

```bash
export WASICHAI_PACKAGES_TOKEN=...   # read:packages
docker build --secret id=packages_token,env=WASICHAI_PACKAGES_TOKEN \
  -f integrations-app/Dockerfile -t wasichai-integrations-app .
```

The runtime image is `eclipse-temurin:25-jre` with the executable jar at `/app/app.jar`, running as the non-root user
`app` (uid 10001), listening on `8080`. `JDK_JAVA_OPTIONS` sizes the heap to 75% of the container's memory limit
(`-XX:MaxRAMPercentage=75.0`) and ends the process on an `OutOfMemoryError` (`-XX:+ExitOnOutOfMemoryError`) so the
orchestrator restarts it; set `JDK_JAVA_OPTIONS` to replace those flags, `JAVA_TOOL_OPTIONS` to add some.

### Run it with Docker Compose

```bash
cd deploy/compose
cp .env.example .env     # gitignored; fill in every replace-me
docker compose build     # needs WASICHAI_PACKAGES_TOKEN in the environment (a build secret of compose.yaml)
docker compose up -d
curl -s localhost:8080/actuator/health
```

`compose.yaml` starts `postgres` (`postgres:18`, data in the `pgdata` volume, `pg_isready` healthcheck), then `api`
from the same image once the database is healthy, and `worker` once `api` is healthy (a first start migrates the
schema once). Only `api` publishes a port (`API_PORT`, default `8080`). Generate the secrets with
`openssl rand -base64 48` (JWT), `openssl rand -base64 32` (each encryption key) and `openssl rand -hex 24` (database
password). The app refuses to start on the placeholder JWT secret or encryption key. `.env` is gitignored; keep it
readable by the operator only (`chmod 600 .env`). To keep secrets out of environment variables, mount them as files
(compose or Kubernetes secrets) and point Spring at the folder with
`SPRING_CONFIG_IMPORT=configtree:/run/secrets/`, each file named after its property (`wasichai.security.jwt.secret`,
`wasichai.integrations.encryption.keys.k1`).

There is no first user until you make one. `WASICHAI_SEED_DEV=true` adds Wasichai's development seed (organization
`demo`, `admin@wasichai.local` / `admin`): for a local trial and the CI smoke test only, never on a reachable host.
Otherwise create the first organization and administrator as Wasichai's
[build-your-app guide](https://github.com/wasichai/wasichai/blob/main/docs/guides/build-your-app.md) describes.

### Environment variables

The app reads only its environment. Any property of this guide or Wasichai's is set by Spring's relaxed binding:
upper case, `.` becomes `_`, `-` is dropped (`wasichai.integrations.public-base-url` is
`WASICHAI_INTEGRATIONS_PUBLICBASEURL`). Wasichai's database variables are its own names (`WASICHAI_DB_*`).

| Variable | Default | Meaning |
|---|---|---|
| `WASICHAI_DB_HOST`, `WASICHAI_DB_PORT` | `localhost`, `5432` | PostgreSQL; compose sets `postgres` and `5432` |
| `WASICHAI_DB_NAME`, `WASICHAI_DB_USERNAME` | `wasichai`, `wasichai` | database and user; compose creates both |
| `WASICHAI_DB_PASSWORD` | `wasichai` | **set it**; compose refuses to start without it |
| `WASICHAI_JWT_SECRET` | none | **required**: HS256 key of Wasichai's tokens, at least 32 bytes |
| `WASICHAI_INTEGRATIONS_ENCRYPTION_KEYS_<ID>` | none | **required**: base64 of 32 bytes per key id. The id is the variable's suffix in lower case (`..._KEYS_K1` is `k1`), so only lower-case letters and digits |
| `WASICHAI_INTEGRATIONS_ENCRYPTION_ACTIVEKEY` | none | **required**: the id new secrets are sealed with |
| `WASICHAI_INTEGRATIONS_PUBLICBASEURL` | none | how providers reach the app; needed for OAuth and webhooks |
| `WASICHAI_INTEGRATIONS_OAUTH_RETURNURL` | `/` | the UI's connections page |
| `WASICHAI_WEB_CORSALLOWEDORIGINPATTERNS` | `http://localhost:*` | browser origins the API answers, comma separated |
| `WASICHAI_SEED_DEV` | `false` | `true`: the development seed above (never in production) |
| `SPRING_PROFILES_ACTIVE` | none | the role: `api` or `worker` (below); none runs everything in one replica |
| `APP_CONNECTORS_<ID>_ENABLED` | `true` | `NOTION`, `SALESFORCE`, `JIRA`, `GITHUB`, `SLACK`: `false` leaves that connector out |
| `APP_CONNECTORS_SALESFORCE_LOGINURL` | `https://login.salesforce.com` | sandboxes: `https://test.salesforce.com`, or a My Domain url |
| `APP_CONNECTORS_SALESFORCE_APIVERSION` | newest | a fixed REST API version (`66.0`) |
| `APP_CONNECTORS_SALESFORCE_CDCCHANNELS` | empty | `Account=/data/AccountChangeEvent,Contact=/data/ContactChangeEvent`: those objects read changes through Change Data Capture (the Pub/Sub connector, same id `salesforce`); empty polls every object |
| `APP_CONNECTORS_JIRA_SITEURL` | empty | the site of API token connections (`https://acme.atlassian.net`); empty allows OAuth only |
| `APP_CONNECTORS_JIRA_CLOUDID` | empty | pins one site when an OAuth grant reaches several |
| `APP_CONNECTORS_GITHUB_WEBURL`, `APP_CONNECTORS_GITHUB_APIURL` | `https://github.com`, `https://api.github.com` | GitHub Enterprise Server: `https://ghe.example.com` and `https://ghe.example.com/api/v3` |
| `SERVER_FORWARDHEADERSSTRATEGY` | `none` | `framework` behind a reverse proxy (client address for sign-in limits, scheme) |
| `SPRING_LIFECYCLE_TIMEOUTPERSHUTDOWNPHASE` | `30s` | how long a stopping replica lets in-flight requests finish |
| `JDK_JAVA_OPTIONS`, `JAVA_TOOL_OPTIONS` | see above | JVM flags |
| `INTEGRATIONS_IMAGE`, `API_PORT` | `wasichai-integrations-app:local`, `8080` | compose only: the image both services run, the api's host port |

An empty value counts as unset for the connector settings (compose passes `VAR=` as an empty string). Every other
property of the [tables above](#properties) (`WASICHAI_INTEGRATIONS_JOBS_LEASE`, `WASICHAI_INTEGRATIONS_HTTP_TIMEOUT`...)
and of Wasichai's core works the same way. The app turns on Wasichai's sign-in attempt limits and token revocation
(`wasichai.security.login.enabled`, `wasichai.security.jwt.revocation`), as Wasichai recommends before production.

The OpenAPI connector is not in the app: its mapping (resources, operations, security scheme) is code written per API
([ADR-0021](../adr/0021-openapi-connector.md)). To add one, build your own app on this one's model and declare an
`OpenApiConnector` bean there.

### Roles: one image, api and worker

The role is a Spring profile; the image is the same.

| Profile | `jobs.worker-enabled` | `sync.scheduler-enabled` | `workflows.scheduler-enabled` | Serves |
|---|---|---|---|---|
| `api` | `false` | `false` | `false` | the REST API, webhooks, the OAuth callback; scale with traffic behind a load balancer |
| `worker` | `true` | `true` | `true` | the job queue (webhook events, sync runs, workflow steps) and the schedulers; scale with the queue |
| none | `true` | `true` | `true` | everything in one replica (a small installation) |

A worker still answers HTTP (its health, and the API if a request reaches it); route traffic to `api` replicas only.
At least one worker must run, or nothing is processed. Scale workers out (`docker compose up -d --scale worker=3`, or
replicas in an orchestrator): job claims skip locked rows and the schedulers take a cluster lock per tick, so any
number of replicas is safe ([workers and scaling](#workers-scheduler-and-scaling)). The worker's `jobs.lease` must
cover the longest sync run. Every replica runs the Flyway migrations at start; Flyway holds a PostgreSQL advisory
lock while it migrates, so replicas starting together wait for one another, and a new version's first start is the
slow one.

### Health and shutdown

- `GET /actuator/health` is public (Wasichai's security chain) and reports `UP` once the database answers; details are
  never shown. `/actuator/health/liveness` and `/actuator/health/readiness` are there for orchestrators that probe
  them. No other actuator endpoint is exposed.
- The image's `HEALTHCHECK` calls `/actuator/health` every 15 s after a 120 s start period (the first start migrates
  every module). Compose's `depends_on` waits on PostgreSQL's healthcheck.
- On `SIGTERM` (java is the container's main process) the server stops accepting connections and lets in-flight
  requests finish for up to `spring.lifecycle.timeout-per-shutdown-phase` (30 s); compose's `stop_grace_period` is
  40 s, so give an orchestrator at least that. A job running when its worker stops is claimed again after
  `jobs.lease`; handlers are idempotent.

### Kubernetes and k3s (Pulumi)

[`deploy/pulumi/`](../../deploy/pulumi/) is a Pulumi program (TypeScript, the Kubernetes provider only) that runs the
image on any cluster from a kubeconfig, k3s, kind and managed clusters alike
([ADR-0030](../adr/0030-shared-rate-limits-and-kubernetes.md)). It creates a namespace and:

- a `Secret` with `WASICHAI_DB_PASSWORD`, `WASICHAI_JWT_SECRET` and `WASICHAI_INTEGRATIONS_ENCRYPTION_KEYS_<ID>`, from
  Pulumi config secrets (`dbPassword`, `jwtSecret`, `encryptionKeys`), encrypted in the stack and the state;
- a `ConfigMap` with the database address, `publicBaseUrl`, the active key id,
  `WASICHAI_INTEGRATIONS_HTTP_RATELIMIT_SHARED=true`, `SERVER_FORWARDHEADERSSTRATEGY=framework` and any non-secret
  variable of the table above (`env`);
- the Deployments `integrations-api` (`SPRING_PROFILES_ACTIVE=api`, `apiReplicas`, default 2) and
  `integrations-worker` (`worker`, `workerReplicas`, default 1), as uid 10001 with a read-only root filesystem.
  Probes: startup and liveness on `/actuator/health/liveness`, readiness on `/actuator/health/readiness`. The grace
  period is 45 s: a 5 s `preStop` pause, the 30 s drain and a margin. The `preStop` sleep needs Kubernetes 1.30+;
- a Service and an Ingress for the api only, with the host of `publicBaseUrl` and TLS from `tlsSecretName` when set;
- with `postgresInCluster=true`, a PostgreSQL 18 StatefulSet for trials; otherwise `dbHost` points at your database.

```bash
cd deploy/pulumi && npm ci
pulumi stack init prod
pulumi config set image ghcr.io/acme/wasichai-integrations-app:1.4.0
pulumi config set publicBaseUrl https://integrations.example.com
pulumi config set dbHost db.internal.example.com && pulumi config set activeKey k1
pulumi config set --secret dbPassword ... && pulumi config set --secret jwtSecret ...
pulumi config set --secret --path 'encryptionKeys.k1' "$(openssl rand -base64 32)"
pulumi up
```

Every config key is in [its README](../../deploy/pulumi/README.md). The CI job "Pulumi program (typecheck + mocked
preview tests)" typechecks the program and runs it against Pulumi's mocks, with no cluster and no credentials.

### CI smoke test

The CI job "Container image (build + smoke)" builds the image with `PACKAGES_TOKEN` as the BuildKit secret, then
[`.github/scripts/smoke.sh`](../../.github/scripts/smoke.sh) writes a `.env` from `.env.example` with generated
secrets and `WASICHAI_SEED_DEV=true`, starts the compose project and waits until every service is healthy, checks the
worker's health from inside its container, checks that the catalog refuses a call without a token, signs in as the
seeded admin (`POST /api/auth/login`), asserts that `GET /api/integrations/connectors` lists `github`, `jira`,
`notion`, `salesforce` and `slack`, and takes the project down with its volume. `smoke.sh --checks-only <base-url>`
runs the HTTP checks against an app already running with the seed, e.g. the jar on a local database.

## Checklist

- [ ] Dependencies on `integrations-bom`, a starter and the connectors; connector beans declared.
- [ ] `encryption.keys` and `active-key` from a secret store, backed up with the database.
- [ ] `public-base-url` (https) and `oauth.return-url` set; the callback and webhook routes reachable from the internet.
- [ ] Each tenant's provider app registered with the redirect uri and stored as a provider config.
- [ ] At least one replica runs the job worker; `jobs.lease` fits the longest sync run.
- [ ] With the example image: `WASICHAI_SEED_DEV` off, secrets out of the repository, `api` and `worker` roles set,
      a grace period of at least 40 s.
- [ ] Retention ages (`retention.max-age.*`) match the data protection policy; the defaults keep events and jobs 30 days.
- [ ] More than one replica: `http.rate-limit.shared` left on (the default), so providers see one rate per connection.
