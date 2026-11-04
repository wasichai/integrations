# Wasichai Integrations platform: design

**Status:** approved 2026-11-04. **Scope:** wasichai/integrations (backend modules, docs) and wasichai/integrations-ui
(packages), Phases 0–4 of the master plan. **ADRs:** [ADR-0001](../../adr/0001-repositories-and-module-boundaries.md) to
[ADR-0014](../../adr/0014-salesforce-change-capture.md).

## Question

Can a Wasichai app connect heterogeneous external systems, keep their records in agreement both ways and act on them,
through connectors written once against a stable SDK, without changing Wasichai and without losing or duplicating data?

Decisions taken with the user:

- **Two new repositories**: backend and docs in `wasichai/integrations`, UI in `wasichai/integrations-ui`. Wasichai and
  wasichai-ui are consumed from GitHub Packages and not modified.
- **Scope of this run**: Phase 0 to Phase 4, ending with a bidirectional Notion ↔ Salesforce sync proven end to end
  against deterministic stubs.
- **Process**: one GitHub issue with acceptance criteria per pull request; merged only when CI unit and integration
  jobs are green.

## What there is today

- Wasichai offers tenancy (`CurrentUser`), background work as the platform, cluster locks, audit, problem+json errors,
  per-module migrations, an automation queue without retries, an SSRF-guarded outbound webhook, and a test base on
  Testcontainers. It has no encryption at rest, OAuth client, inbound webhooks, generic job queue, metrics or MCP.
- wasichai-ui offers a module registry, an API client, query conventions, i18n per module and a test kit with
  `mockFetch`.

## Design

See [architecture overview](../../architecture/overview.md), [components](../../architecture/components.md),
[data model](../../architecture/data-model.md), [event model](../../architecture/event-model.md),
[sync semantics](../../architecture/sync-semantics.md), [security model](../../architecture/security-model.md) and the
[REST API](../../architecture/api.md).

## Delivery

The [backlog](../../product/backlog.md) orders the work: build and CI, docs, SDK, vault and catalog, connections and
OAuth, runtime and jobs, webhooks and events, Notion, Salesforce, sync definitions, engine, reconciliation, the
end-to-end suite; then the UI packages.

## Out of scope

Jira, GitHub, Slack and Generic OpenAPI connectors; integration workflows; MCP; Salesforce CDC (stretch); bulk APIs;
untrusted connector isolation; marketplace and billing; Compose, Pulumi and k3s.
