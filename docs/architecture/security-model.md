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

- AES-256-GCM, a fresh 96-bit nonce per encryption, additional authenticated data = organization id + owner row id,
  so a ciphertext copied to another row or tenant does not decrypt.
- Each row stores the `key_id` it was encrypted with. Keys come from a `CredentialKeyProvider`; the default reads a key
  ring from configuration (`wasichai.integrations.vault.keys.<id>` base64 32-byte keys, `active-key`), so a self-hosted
  install keeps keys in its own secret store or environment.
- Rotation: add a key, make it active; new writes use it, old rows still decrypt; a re-encrypt job moves old rows; the
  old key is removed only when no row names it.
- Recovery: losing every key makes credentials unreadable by design; connections go to `NEEDS_REAUTH` and are
  reconnected. Back keys up with the database.
- Secrets are write-only over REST and masked (`"••••"` plus last 4 characters at most) everywhere; logs and metrics
  never include them; HTTP logging redacts `Authorization`, cookies and known token fields.

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

`ProviderHttp` allows only `https` (plain `http` only when explicitly allowed for tests), resolves the host and refuses
loopback, private, link-local and multicast addresses, as Wasichai's automation `WebhookSender` does. Base URLs come
from connector code or provider metadata (Salesforce `instance_url` must match `*.salesforce.com` / `*.force.com`
patterns declared by the connector), never from free user input.

## Retention and data protection

Event payloads and snapshots contain provider data: retention is configurable and purges run per tenant. Deleting a
connection deletes its credentials and webhook endpoints; deleting a tenant's integrations data is a per-org purge of
these tables. Export for GDPR requests is out of the MVP and planned for Phase 8.
