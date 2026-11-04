# ADR-0002: A connector is a manifest plus optional, typed capability interfaces

**Status**: accepted · 2026-11-04 · see [components](../architecture/components.md)

## Context

Connectors differ widely: some only receive webhooks, some cannot write, some have incremental reads and some do not.
One fat interface would force fake implementations; a dynamic map of operations would lose types.

## Decision

- `Connector` exposes a `ConnectorManifest`. Capabilities are separate interfaces a connector implements when it has
  them: `Authenticator`, `ResourceDiscovery`, `ResourceReader`, `ResourceWriter`, `WebhookHandler`, `ActionExecutor`.
  The runtime asks with a type check, never assumes.
- Values are provider-native: records are a JSON object tree plus id and version; schemas are described field by field
  with type, required, read-only, external-id and options. No universal entity.
- Connectors are suspend functions over an injected `ConnectorContext` (authenticated `ProviderHttp`, clock). They hold
  no state and never see credentials of another connection.
- Failures are a sealed `ConnectorError` (`Transient`, `RateLimited`, `AuthExpired`, `PermissionDenied`, `NotFound`,
  `Conflict`, `Permanent`); retry, refresh and backoff are the runtime's job, decided by the type.
- `BulkOperations`, `TriggerProvider` and `IncrementalSync` as separate interfaces are not added until a connector needs
  them: incremental reads are a `since` cursor on `ResourceReader`, triggers are webhooks plus polling.

## Consequences

- A new provider is a new module; the core does not change.
- The capability set grows by adding interfaces, a minor SDK version ([ADR-0012](0012-connector-distribution-and-versioning.md)).
