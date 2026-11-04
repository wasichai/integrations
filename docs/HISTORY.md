# Change history

Newest first. Architectural reasoning lives in `docs/adr/`; this file records what shipped.

## 2026-11-04 — Phase 0: discovery, competitive analysis, architecture and the first fourteen decisions

Wasichai 1.10.0 and wasichai-ui 1.7.0 were inspected and eleven integration products researched
([#2](https://github.com/wasichai/integrations/issues/2)). The [discovery report](product/discovery-report.md) answers the
thirteen Phase 0 questions; the [competitive analysis](product/competitive-analysis.md) finds managed OAuth and MCP
commoditized and bidirectional conflict handling the gap. The architecture (control and data plane inside a modular
monolith, connector SDK, vault, PostgreSQL job queue, Event Envelope, sync semantics) is in `docs/architecture/`, the
decisions in [ADR-0001](adr/0001-repositories-and-module-boundaries.md)–[ADR-0014](adr/0014-salesforce-change-capture.md),
and the work in the [backlog](product/backlog.md). REST change: none (the API is specified, not built). New tests: none.

## 2026-11-04 — Build skeleton and CI

Gradle build on Wasichai's conventions, the connector SDK's first type and a boot smoke test against PostgreSQL 18
([#1](https://github.com/wasichai/integrations/issues/1)). REST change: none. New tests: `SdkVersionTest`, `AppBootsTest`.
