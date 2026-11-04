# Requirements

MVP scope is marked **[MVP]**; the rest is direction for later phases. Each MVP item maps to an issue in the
[backlog](backlog.md).

## Functional

### Connectors and catalog
- F1 **[MVP]** Connectors are registered from the classpath; each declares a manifest and its real capability maturity.
- F2 **[MVP]** The catalog lists connectors, their auth methods, resources, capabilities and maturity.
- F3 Connector versions, upgrade and deactivation; marketplace (later).

### Connections and authentication
- F4 **[MVP]** A tenant has any number of connections per connector, each with status and health.
- F5 **[MVP]** OAuth 2.0 authorization code with PKCE (S256), single-use state, redirect validation.
- F6 **[MVP]** API key, basic and client-credentials connections.
- F7 **[MVP]** Token refresh and refresh-token rotation; failed refresh marks the connection `NEEDS_REAUTH`.
- F8 **[MVP]** Test, disconnect and revoke a connection.
- F9 **[MVP]** Per-tenant OAuth client configuration (client id and secret) managed by tenant admins.

### Discovery
- F10 **[MVP]** List a connection's resources and describe a resource's fields (type, required, read-only, external id,
  enum options, relationships).

### Events and webhooks
- F11 **[MVP]** Inbound webhooks per connection on an unguessable endpoint, verified before parsing.
- F12 **[MVP]** Every inbound change stored as an Event Envelope; duplicates detected; events listable and replayable.

### Synchronization
- F13 **[MVP]** Sync definitions between two connections' resources: one-way either way or bidirectional.
- F14 **[MVP]** Field mappings with a closed transform set (copy, static, default, enum map, date/time, number,
  conditional on equality); owner per field (A, B or both) and a conflict strategy.
- F15 **[MVP]** Initial, incremental, manual and scheduled runs; webhook-triggered incremental runs.
- F16 **[MVP]** Durable record links and snapshots; idempotent creates; echo prevention.
- F17 **[MVP]** Conflicts stored, listed and resolved by a person; never silently overwritten.
- F18 **[MVP]** Reconciliation detecting missing, deleted and divergent records, with a delete policy per definition.
- F19 Relationships, attachments, currency conversion, bulk APIs (later).

### Workflows and AI (later)
- F20 Integration workflows: triggers, conditions, branches, delays, approvals, error paths.
- F21 Explicitly authorized connector actions exposed as MCP tools, with approvals and audit.

### Operations
- F22 **[MVP]** Jobs listable with status, attempts and last error; retry of failed and dead jobs.
- F23 **[MVP]** Health per connection and per sync definition: last success, pending work, last error and whether it is
  transient or permanent.

## Non-functional

- N1 **Tenant isolation**: tenant from the JWT only; every query filters by `organization_id`; another tenant's id is a
  `404`. Tested per module.
- N2 **Secrets**: AES-256-GCM at rest with key ids; never in responses or logs; key rotation without downtime.
- N3 **Reactive**: no blocking call on HTTP or persistence paths; SQL through `DatabaseClient` with bound values.
- N4 **Durability**: jobs survive restarts and worker crashes (leases); at-least-once with idempotent effects.
- N5 **No silent loss**: no event, conflict or failed job is dropped without a stored, visible state.
- N6 **Provider limits**: per-connection rate limiting; `Retry-After` honoured; exponential backoff.
- N7 **Observability**: Micrometer meters for HTTP calls, jobs and sync; correlation ids on logs, events and jobs.
- N8 **Security of public routes**: only the OAuth callback and webhooks are unauthenticated; signature or state checked
  first; body size limits; SSRF guard on outbound calls.
- N9 **Extensibility**: a new connector needs no change to the core modules.
- N10 **Portability**: runs on one Spring Boot app and PostgreSQL 18; no broker; cloud-agnostic.
- N11 **Compatibility**: builds on published Wasichai 1.10.0 without modifying it.
- N12 **Quality gates**: ktlint, unit and integration tests green in CI before merge; contract tests per connector.
- N13 **i18n and a11y** (UI): Spanish and English; keyboard navigation; empty, loading, error and degraded states.
