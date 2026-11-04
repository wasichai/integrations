# ADR-0004: The manifest declares capabilities with a maturity, and is checked against the code

**Status**: accepted · 2026-11-04

## Context

Integration products often list providers as "supported" when only basic requests work. Users need to know what is real.

## Decision

- The manifest declares id (`^[a-z][a-z0-9-]{1,39}$`), provider name, semver version, `sdkVersion`, auth methods,
  scopes and permissions, resources, actions, sync directions, pagination and filter support, rate limits and webhook
  support.
- Each capability carries a maturity: `DECLARED` (planned, not callable), `IMPLEMENTED` (code exists), `TESTED`
  (covered by the contract kit and stub tests). The UI shows it; sync refuses resources whose read/write are only
  `DECLARED`.
- `ManifestValidator` runs at registration: an implemented capability must be declared and vice versa, versions must
  parse, the `sdkVersion` must be supported. A failing connector stops startup.
- Lifecycle in the platform: registered → validated → (per tenant) configured → connected → active → degraded /
  needs-reauth → disconnected → removed.

## Consequences

- "Certified" means `TESTED` on every capability the connector offers; there is no manual certification in the MVP.
