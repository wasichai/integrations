# ADR-0006: OAuth 2.0 authorization code with PKCE, single-use state rows and a public callback

**Status**: accepted · 2026-11-04

## Context

Most providers use the authorization code flow; Salesforce now steers new apps to External Client Apps and supports
PKCE. The callback cannot carry Wasichai's bearer token.

## Decision

- `POST /connections/oauth/start` (authenticated) creates a state row: 256-bit random state stored hashed, PKCE S256
  verifier encrypted, bound to org, user, connector and redirect URI, 10-minute TTL.
- `GET /oauth/callback` is public, in its own security chain. It consumes the state once, exchanges the code with the
  per-tenant provider config, stores tokens and granted scopes, and redirects to the UI.
- Refresh happens before expiry and once on `AuthExpired`; rotated refresh tokens replace the old one atomically; a
  failed refresh marks the connection `NEEDS_REAUTH`. Disconnect revokes where the provider supports it.
- API key, basic and client credentials are stored through the same vault.

## Consequences

- OAuth clients are per tenant (`provider_configs`); a platform-wide default client may be configured by properties.
