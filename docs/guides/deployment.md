# Deployment guide

How to add Wasichai Integrations to a Wasichai app and configure it: dependencies, every property, encryption keys,
the public url, OAuth, provider apps, webhooks and scaling. Operating it day to day is in [operations](operations.md).

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
| `wasichai-integrations`, `wasichai-integrations-sync` | the modules alone, for an app that assembles Wasichai itself |
| `integrations-connector-notion`, `-salesforce`, `-jira`, `-github`, `-slack` | the shipped connectors (no Spring) |
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

The modules need what the Wasichai starter provides: PostgreSQL through R2DBC, Wasichai's JWT security and
`CurrentUser`, `WasichaiSchemas`, and the cluster lock. Each module runs its own Flyway history: `integrations`
(`db/wasichai/integrations`, after every Wasichai module) and `integrations_sync` (`db/wasichai/integrations-sync`), in
the Wasichai metadata schema. Nothing has to be run by hand.

## Properties

### `wasichai.integrations.*`

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` turns the module (and the sync module with it) off; its routes answer `404` |
| `public-base-url` | none | how providers reach this app, e.g. `https://app.example.com`; needed for OAuth and webhooks |
| `encryption.keys.<id>` | none | base64 of 32 random bytes (AES-256) per key id; **required** |
| `encryption.active-key` | none | the key id new secrets are sealed with; **required**, must be one of `keys` |
| `oauth.return-url` | `/` | where the browser lands after the OAuth callback (the UI's connections page) |
| `oauth.state-ttl` | `10m` | how long an OAuth start stays valid; must be positive |
| `oauth.refresh-skew` | `60s` | refresh an access token this long before it expires |
| `http.allow-private-addresses` | `false` | allow provider calls to private, loopback or link-local addresses (tests, labs only) |
| `http.timeout` | `30s` | per provider call; a timeout is a transient failure |
| `http.max-response-bytes` | `16777216` (16 MiB) | the largest provider response read |
| `jobs.worker-enabled` | `true` | whether this replica runs jobs |
| `jobs.poll-interval` | `1s` | how often the worker claims due jobs |
| `jobs.batch` | `20` | jobs claimed per poll, run concurrently |
| `jobs.lease` | `5m` | a job still `RUNNING` after this is taken to have lost its worker and is claimed again |
| `jobs.backoff` | `30s` | first retry delay, doubled per attempt |
| `jobs.max-backoff` | `1h` | ceiling of the retry delay |
| `webhooks.max-body-bytes` | `1048576` (1 MiB) | a larger delivery is refused with `413` before parsing |

`public-base-url` must start with `http://` or `https://` when set. Use `https` in production: providers call it and
OAuth providers usually require it.

### `wasichai.integrations.sync.*`

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` turns the sync module off |
| `page-size` | `100` | records per change-feed or list page, 1–1000 |
| `scheduler-enabled` | `true` | start scheduled and reconcile runs |
| `scheduler-tick` | `30s` | how often the scheduler looks for due definitions; must be positive |

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
  like any other; a bot token (`xoxb-`) can be used instead of OAuth (`authType: API_KEY`), for a single workspace.

## Webhooks

Webhook endpoints need `public-base-url` too. A tenant administrator creates one per connection
(`POST /api/integrations/connections/{id}/webhook-endpoints`); its `url` is
`<public-base-url>/api/integrations/webhooks/{connectorId}/{endpointId}` and is registered at the provider. Notion
verifies an endpoint by posting a token, which an administrator reads (`GET /webhook-endpoints/{id}/verification-token`)
and pastes back at Notion; providers that show a signing secret at registration take it in the create body
(`secret`). Deliveries larger than `webhooks.max-body-bytes` are refused. Salesforce has no webhook support in the
connector: its changes are polled ([ADR-0014](../adr/0014-salesforce-change-capture.md)). For Slack, create the endpoint
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
- Rate limits are kept in memory per replica: n replicas may together reach n times a connector's declared rate. A
  provider's `429` still slows every replica through retries.
- Provider calls are made with the app's own HTTP client; outbound access to the providers' hosts (and DNS) must be
  allowed.

## Checklist

- [ ] Dependencies on `integrations-bom`, a starter and the connectors; connector beans declared.
- [ ] `encryption.keys` and `active-key` from a secret store, backed up with the database.
- [ ] `public-base-url` (https) and `oauth.return-url` set; the callback and webhook routes reachable from the internet.
- [ ] Each tenant's provider app registered with the redirect uri and stored as a provider config.
- [ ] At least one replica runs the job worker; `jobs.lease` fits the longest sync run.
