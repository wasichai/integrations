# ADR-0001: Integrations ships as Wasichai modules in two repositories of its own

**Status**: accepted · 2026-11-04 · builds on [wasichai
ADR-024](https://github.com/wasichai/wasichai/blob/main/docs/adr/0024-libraries-and-starters.md),
[ADR-025](https://github.com/wasichai/wasichai/blob/main/docs/adr/0025-extension-spis.md),
[ADR-032](https://github.com/wasichai/wasichai/blob/main/docs/adr/0032-rebrand-to-wasichai-and-split-repositories.md)

## Context

Wasichai is a set of libraries; apps opt into modules. An integration platform is a large, optional capability with its
own release pace, and Wasichai's rules forbid the core from knowing modules. The work must not change Wasichai.

## Decision

- Backend and docs in `wasichai/integrations`; React packages in `wasichai/integrations-ui`. Both consume Wasichai's
  published artifacts (`wasichai-*` 1.10.0, `@wasichai/*` 1.7.x) from GitHub Packages and follow its conventions
  (convention plugins, auto-configuration, per-module Flyway, `wasichai.<module>.enabled`).
- Modules: `integrations-connector-sdk` (no Spring), `integrations-connector-sdk-test`, `wasichai-integrations`
  (control plane + runtime), `wasichai-integrations-sync`, one `integrations-connector-<provider>` per provider,
  starters, and an unpublished `integrations-integration-tests`.
- Dependencies point inward: connectors → SDK; `wasichai-integrations` → SDK + `wasichai-core`; sync →
  `wasichai-integrations`. Nothing depends on a connector. Provider names live only in connector modules.
- Decisions of both repositories are recorded here, numbered from 0001, and cite Wasichai ADRs by URL.

## Consequences

- Wasichai stays untouched; anything that should move into it later (a vault, a job queue) needs a second user there
  and a Wasichai ADR.
- CI of both repositories needs read access to Wasichai's GitHub Packages.
- A modular monolith: one deployable; the module seams are where a service could be cut later.
