# Product vision

Wasichai Integrations lets an organization running a [Wasichai](https://github.com/wasichai/wasichai) app connect the
external systems it already uses, keep their data in agreement and act on them, without writing a pairwise integration
for every pair of systems.

## What it is

- **A platform, shipped as libraries.** Spring Boot modules an app opts into, exactly like Wasichai's own modules
  ([ADR-024](https://github.com/wasichai/wasichai/blob/main/docs/adr/0024-libraries-and-starters.md)), plus React
  packages that plug into the Wasichai UI registry. One deployable per app; self-hosted or SaaS alike.
- **A connector SDK.** A connector is a Kotlin library that declares what it can do (authentication, discovery, reads,
  writes, webhooks, incremental reads) and implements only that. The core never names a provider.
- **Managed connections.** OAuth 2.0 (authorization code + PKCE), API keys, basic and client credentials, encrypted at
  rest, refreshed and revoked by the platform, per tenant.
- **A synchronization engine that does not lose data.** One-way and bidirectional sync with field-level ownership,
  conflict detection that never overwrites silently, loop prevention, idempotent writes and reconciliation.
- **Operable.** Every event, job, run and conflict is stored, observable and, where safe, replayable.

## For whom

- **Operators** (non-technical): find a connector, connect an account, pick objects, map fields, activate, see what
  failed and fix it. Progressive disclosure: the default path needs no API knowledge.
- **Developers**: write a connector against the SDK, test it against the contract kit, ship it as a Maven artifact.
- **Platform owners**: run it on their own infrastructure under their own keys.

## Where it differs

The [competitive analysis](competitive-analysis.md) finds managed OAuth and MCP tool exposure commoditized, and
bidirectional conflict handling documented only by end-user sync tools (Unito, Exalate), not by developer platforms.
Wasichai Integrations aims at that gap: a developer platform with first-class bidirectional sync semantics, self-hostable
as libraries inside the app that owns the data.

## First milestone

Not a number of connectors: a reliable, extensible connector architecture and one fully working bidirectional
**Notion ↔ Salesforce** synchronization, proven by an end-to-end suite in CI.
