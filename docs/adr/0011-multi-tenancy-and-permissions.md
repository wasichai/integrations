# ADR-0011: Tenant from the token, manage rights for tenant administrators

**Status**: accepted · 2026-11-04 · builds on [wasichai
ADR-053](https://github.com/wasichai/wasichai/blob/main/docs/adr/0053-the-caller-is-told-their-tenant-wide-capabilities.md),
[ADR-055](https://github.com/wasichai/wasichai/blob/main/docs/adr/0055-tenant-provisioning-apart-from-tenant-administration.md)

## Context

Wasichai's permissions are per object (CRUD and declared actions) plus tenant-wide `MANAGE_ORGANIZATION`. Integrations
are tenant-level configuration, not records of a Custom Object.

## Decision

- Tenant always from `CurrentUser`; every table has `organization_id`; every query filters by it; foreign ids are `404`.
- Read routes need an authenticated member of the organization; managing provider configs, connections, definitions,
  conflicts, retries and replays needs `ADMIN` or `MANAGE_ORGANIZATION`.
- Background work carries the job's org and runs as the platform; it never reads another org's rows.
- Per-connection permissions and finer roles wait for a user who needs them.

## Consequences

- Each module's integration suite provisions a second organization and proves isolation.
