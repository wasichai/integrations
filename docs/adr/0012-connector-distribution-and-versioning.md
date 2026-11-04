# ADR-0012: Connectors are Maven artifacts with semver and an SDK compatibility version

**Status**: accepted · 2026-11-04

## Context

Connectors are written by us and by third parties and evolve at their own pace; the runtime must know whether a
connector fits.

## Decision

- A connector is a Maven artifact with its own auto-configuration that registers a `Connector` bean. The app picks
  connectors by adding dependencies; there is no runtime download.
- Connector versions follow semver. `sdkVersion` is `major.minor`: a connector runs when the major equals the runtime's
  and its minor is not newer.
- The SDK changes compatibly within a major (new optional interfaces, new optional manifest fields).

## Consequences

- A marketplace, when it comes, is a catalog of artifacts, not a code loader.
