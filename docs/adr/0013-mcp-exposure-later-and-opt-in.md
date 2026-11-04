# ADR-0013: Connector actions become MCP tools later, and only when explicitly exposed

**Status**: accepted · 2026-11-04 · relates to [wasichai
ADR-014](https://github.com/wasichai/wasichai/blob/main/docs/adr/0014-agents-through-the-api.md),
[ADR-015](https://github.com/wasichai/wasichai/blob/main/docs/adr/0015-embabel-as-the-agent-runtime.md)

## Context

Every competitor exposes MCP tools; Wasichai's agent (Embabel) has read-only tools and no write tools until a
confirmation UI exists. Exposing every provider action automatically would let an agent act beyond what a tenant chose.

## Decision

- MCP is out of the MVP (Phase 7). When built, it is an interface over the same connector runtime: a tool exists only if
  a tenant administrator exposed that action, runs with the caller's tenant and permissions, validates input against
  the action schema, is audited, and requires approval when marked sensitive.

## Consequences

- Nothing in the MVP's contracts assumes MCP; `ActionExecutor` and action schemas are enough to build it.
