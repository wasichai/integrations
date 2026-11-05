# Architecture decision records

Decisions of [wasichai/integrations](https://github.com/wasichai/integrations) and
[wasichai/integrations-ui](https://github.com/wasichai/integrations-ui), numbered in order. Wasichai's own decisions live in
[its repository](https://github.com/wasichai/wasichai/blob/main/docs/adr) and are cited by URL. A decision is never edited to change it: a later ADR amends or
supersedes it and says so in its status line.

- [ADR-0001: Integrations ships as Wasichai modules in two repositories of its own](0001-repositories-and-module-boundaries.md)
- [ADR-0002: A connector is a manifest plus optional, typed capability interfaces](0002-connector-sdk-contracts.md)
- [ADR-0003: Connectors run in-process as trusted libraries; untrusted code waits for a remote protocol](0003-in-process-trusted-connectors.md)
- [ADR-0004: The manifest declares capabilities with a maturity, and is checked against the code](0004-connector-manifest-versioning-and-maturity.md)
- [ADR-0005: Credentials are encrypted with AES-256-GCM under named keys from a key provider](0005-secret-management.md)
- [ADR-0006: OAuth 2.0 authorization code with PKCE, single-use state rows and a public callback](0006-oauth-flows.md)
- [ADR-0007: Inbound changes are stored as Event Envelopes; webhooks are verified signals](0007-event-envelope-and-webhooks.md)
- [ADR-0008: Durable work runs on a PostgreSQL job queue with leases, not on Temporal or wasichai-automation](0008-durable-jobs-in-postgresql.md)
- [ADR-0009: Synchronization keeps links and snapshots, diffs per field, and never overwrites a conflict silently](0009-sync-semantics-and-conflicts.md)
- [ADR-0010: Echoes are detected by content hash and causation, not by who made the change](0010-loop-prevention.md)
- [ADR-0011: Tenant from the token, manage rights for tenant administrators](0011-multi-tenancy-and-permissions.md)
- [ADR-0012: Connectors are Maven artifacts with semver and an SDK compatibility version](0012-connector-distribution-and-versioning.md)
  (registration superseded by ADR-0017)
- [ADR-0013: Connector actions become MCP tools later, and only when explicitly exposed](0013-mcp-exposure-later-and-opt-in.md)
- [ADR-0014: Salesforce changes are polled first; Pub/Sub Change Data Capture comes later](0014-salesforce-change-capture.md)
- [ADR-0015: The sync engine's implementation refines links, runs, conflicts and reconciliation](0015-sync-implementation-decisions.md)
- [ADR-0016: Jira finds its site on use, writes rich text as plain paragraphs, and claims webhooks only as implemented](0016-jira-cloud-connector.md)
- [ADR-0017: Connectors are plain SDK libraries; the app declares their beans](0017-connectors-as-plain-libraries.md)
- [ADR-0018: Deletions that cannot propagate, unchecked accounts and unreadable credentials are made visible](0018-silent-paths-become-visible.md)
- [ADR-0019: GitHub connects by user token only, polls issues by fresh queries, and refuses deletes](0019-github-connector.md)
- [ADR-0020: Slack connects as the workspace's bot, polls new messages only, and maps `ok:false` codes](0020-slack-connector.md)
- [ADR-0021: The OpenAPI connector maps a document in code, declares only what the mapping provides, and reads YAML](0021-openapi-connector.md)
- [ADR-0023: A token answer without a token that names an error is a refused grant](0023-token-refusals-in-ok-responses.md)
