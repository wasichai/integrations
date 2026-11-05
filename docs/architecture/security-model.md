# Security model

## Tenancy

- The tenant is the JWT's `org` claim, read through `CurrentUser`
  (`wasichai-core/src/main/kotlin/wasichai/core/identity/CurrentUser.kt`); a body never names it.
- Every query filters by `organization_id = :org`. Another tenant's id answers `404`, never `403`.
- Background work runs per tenant as the platform and carries the tenant explicitly; a job's org is a column, checked
  on every row it touches.
- Each module's integration suite provisions a second organization and asserts it sees nothing of the first.

## Permissions ([ADR-0011](../adr/0011-multi-tenancy-and-permissions.md))

| Action | Who |
|---|---|
| Read catalog, connections, definitions, runs, events, health | any authenticated member of the org |
| Manage provider configs, connections, sync definitions; resolve conflicts; retry jobs; replay events | `ADMIN` or `MANAGE_ORGANIZATION` |

Service accounts follow Wasichai's rules (never `ADMIN`).

## Secrets ([ADR-0005](../adr/0005-secret-management.md))

- AES-256-GCM, a fresh 96-bit nonce per encryption, additional authenticated data = organization id, table, row id and
  column (`CredentialCipher`), so a ciphertext copied to another row or tenant does not decrypt.
- The sealed value names the key it was sealed with (`v1.<keyId>.<nonce>.<ciphertext>`). Keys come from a
  `CredentialKeyProvider`; the default reads a key ring from configuration (`wasichai.integrations.encryption.keys.<id>`,
  base64 of 32 random bytes, and `wasichai.integrations.encryption.active-key`), so a self-hosted install keeps keys in
  its own secret store or environment. The module does not start without a key and an active key.
- Rotation: add a key, make it active; new writes use it, old rows still decrypt. There is no re-encrypt job: a secret
  is re-sealed with the active key only when it is written again (connection credentials on a token refresh or a
  reconnect, a provider app's client secret on its next `PUT`); webhook signing secrets are never re-sealed. The old key
  is removed only when no row names it ([deployment guide](../guides/deployment.md#encryption-keys-and-rotation)).
- Recovery: losing every key makes credentials unreadable by design. Today an unreadable secret surfaces as an
  unexpected error on the call that needs it, not as `NEEDS_REAUTH`
  ([#60](https://github.com/wasichai/integrations/issues/60)); the connection is reconnected (or the provider app or
  webhook endpoint saved again). Back keys up with the database.
- Secrets are write-only over REST and never returned: views only say whether one is set (`clientSecretSet`,
  `secretSet`). Logs and metrics never include them; the platform does not log provider requests, headers or bodies.

## OAuth ([ADR-0006](../adr/0006-oauth-flows.md))

Authorization code with PKCE S256; state is 256 random bits, stored hashed, bound to org, user, connector and redirect
URI, valid 10 minutes, consumed once. The callback compares the redirect URI exactly with the provider config, rejects
reused, expired or unknown state, and never reflects provider error text into HTML.

## Public routes

Only `GET /api/integrations/oauth/callback` and `POST /api/integrations/webhooks/{connectorId}/{endpointId}`. They run
in their own security chain ordered before Wasichai's. A webhook endpoint id is random and unguessable, the body size is
capped, the signature is verified over the raw body before parsing, and an unknown endpoint answers `404` without
saying whether a tenant or connection exists.

## Replay protection

Webhook deliveries are deduped by provider event id or body hash (unique constraint). Signed timestamps are checked when
the provider signs one. OAuth states are single use.

## Outbound calls (SSRF)

Every provider call leaves through `PlatformHttp`, behind each connection's `ProviderHttp`. It accepts only absolute
`http` or `https` urls without user info. Its guarded DNS resolver (`GuardedResolverGroup`) checks every address the
host resolves to and refuses loopback, private (site-local and IPv6 unique local), link-local, wildcard and multicast
ones (`PermissionDenied`), unless `wasichai.integrations.http.allow-private-addresses` is on (tests, labs). The address
netty connects to is the one checked, so a DNS rebinding cannot answer differently on a second lookup. Base URLs come
from connector code, connector configuration or provider metadata (Salesforce's `instance_url` from the token response),
never from free user input; there is no host allowlist for `instance_url` beyond the private-address check.

## Retention and data protection

Event payloads and snapshots contain provider data. There is no retention setting and no purge of events, runs or
snapshots yet ([#46](https://github.com/wasichai/integrations/issues/46)); only expired OAuth states are purged.
Deleting a connection deletes its credentials, webhook endpoints and events (foreign keys cascade); deleting an
organization cascades to every integrations table of that tenant. Export for GDPR requests is out of the MVP and
planned for Phase 8.
