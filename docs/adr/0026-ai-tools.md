# ADR-0026: AI tools are explicitly exposed, sensitive by default, and run through one audited execution path

**Status**: accepted · 2026-11-05 · builds on [ADR-0013](0013-mcp-exposure-later-and-opt-in.md),
[ADR-0011](0011-multi-tenancy-and-permissions.md), [ADR-0005](0005-secret-management.md)

## Context

Phase 7 ([#43](https://github.com/wasichai/integrations/issues/43)) turns connector capabilities into tools an AI
agent may call; the MCP endpoint that serves them to agents is [#44](https://github.com/wasichai/integrations/issues/44).
ADR-0013 set the frame: a tool exists only if a tenant administrator exposed it, runs with the caller's tenant and
permissions, validates its input, is audited, and asks for approval when it is sensitive. What remained open: what can
be exposed, who may call it, which input schemas are accepted (the SDK manifest declares no action input schema), what
an execution records, and where the policy lives so MCP cannot bypass it.

## Decision

- **A module of its own**, `wasichai-integrations-ai` (starter `wasichai-spring-boot-starter-integrations-ai`),
  depending only on `wasichai-integrations`. Switch `wasichai.integrations.ai.enabled`, Flyway history `integrations_ai`
  (order 230, after sync 210 and workflows 220). REST under `/api/integrations/ai`.
- **Explicit exposure, nothing by default.** A tool is a row in `integration_ai_tools`, made by `MANAGE_ORGANIZATION`:
  a name unique in the tenant (`^[a-z][a-z0-9_]{0,63}$`, what MCP clients accept), a description for the model, one
  connection of the tenant, and either a connector **action** (the connector declares and implements `ACTIONS` and the
  action) or a **record operation** `read | list | create | update | upsert | delete` on one resource (the connector
  declares and implements `READ`, `WRITE` or `UPSERT`, and declares or discovers the resource; a write other than delete
  needs discovery, since its fields come from the resource schema). The connection is then held by the tool
  (`ConnectionUsage`: deleting it answers `409 … tool 'x'`).
- **Who may call.** An authenticated member of the tenant holding one of the tool's `allowedRoles` (Wasichai role names
  of that tenant, checked when exposed), or anyone holding `MANAGE_ORGANIZATION`. An empty list therefore means
  managers only. Service accounts are Wasichai users with roles: they call a tool through an allowed role, never through
  `MANAGE_ORGANIZATION`, which Wasichai never grants them (its ADR-043). Members see only the enabled tools they may call;
  managers see all.
- **Sensitive by default.** `approvalRequired` defaults to `true` for actions (`ActionDeclaration.sensitive` already
  defaults to true and the platform cannot tell a harmless action from a harmful one) and for create, update, upsert
  and delete; to `false` for read and list. An approval-required call is stored `PENDING_APPROVAL` and answered `202`;
  a manager approves (it runs once: the move to `RUNNING` is a conditional update, a second approve is `409`) or rejects
  it. A manager's own call waits as well: the rule is about the tool, not the caller. An approval is refused (`409`)
  when the tool was deleted, disabled or changed (version) since the call; the execution can then only be rejected.
- **Input schemas are a closed JSON Schema subset.** `type` (`object`, `string`, `number`, `integer`, `boolean`,
  `array`), `properties`, `required`, `enum`, `minLength`, `maxLength`, `format` (`date`, `date-time`), `minimum`,
  `maximum`, `items`, `minItems`, `maxItems`, `title`, `description`; at most 8 deep and 32 KiB; the root is an object.
  Objects are closed: `additionalProperties` may only be `false` and is written when absent, so what a client reads is
  what is enforced. Anything else (`pattern`, `oneOf`, `$ref`, other formats) is refused when exposing, rather than
  silently ignored when validating. No property may be named like a credential (`password`, `secret`, `token`,
  `apiKey`, `authorization`, …): a tool never takes credentials, the connection holds them. An action's schema is
  supplied by whoever exposes it; a record tool's schema is derived from what the connector describes when exposed
  (`{id}`, `{cursor?, pageSize?}`, `{fields}`, `{id, fields}`, `{externalId, fields}`), leaving out read-only,
  reference and credential-like fields. It is re-derived on every update of the tool.
- **One execution path.** `ToolExecutions.execute(name, input)` is the only way a tool runs; the REST route calls it and
  #44's MCP endpoint will call it. Its order: the tool in the caller's tenant (`404`, also for another tenant's), the
  caller's roles (`403`), enabled and an `ACTIVE` connection (`409`), the rate limit (`429` with `Retry-After`), the
  input against the schema (`400` with paths such as `input.fields.name`). Then the execution row, and the run through
  the SDK only (`ConnectorContexts.forConnection`, the connector's capability interface). A refusal before the row
  exists is an HTTP error and a counted reason (`integrations.ai.tool.refusals`), not an execution.
- **Rate limits are per tool and per replica**, a token bucket of `rateLimitPerMinute` (default 60) that refuses
  instead of waiting. The integrations `RateLimits` paces calls to a provider and waits; it is not reused. A
  cluster-wide budget is [#47](https://github.com/wasichai/integrations/issues/47)'s.
- **What an execution records** (`integration_ai_tool_executions`): tool id, name and version (kept when the tool is
  deleted), connection, caller and service account name, the validated input (user data, kept whole to be run on
  approval; at most 64 KiB, larger is `400`; it cannot carry credential-like keys), status `PENDING_APPROVAL | RUNNING |
  SUCCEEDED | FAILED | REJECTED`, an output summary (credential-like keys masked as `***` at any depth, at most 16 KiB,
  else a truncated text marked `outputTruncated`), the mapped error and its kind (never a provider body), who decided
  and why, the request's correlation id, timestamps. The caller of `execute` and `approve` gets the provider's whole
  output; the stored row keeps the summary. A provider failure is a `FAILED` execution answered `200`, not an HTTP
  error. A run interrupted by a cancelled request is stored `FAILED` (`cancelled`), never left `RUNNING` silently.
  Each exposure change and each execution step is also written to Wasichai's `audit_log` through `AuditService`
  (`integration_ai_tool`, `integration_ai_tool_execution`), with states that hold no input or output.
- **What MCP (#44) reuses**: the catalog (`ToolCatalog`: names, descriptions, input schemas, the per-caller visibility
  rule), `ToolExecutions.execute` with its policy, statuses and audit, and the approval routes. It adds only the
  protocol: listing tools, mapping a call to `execute`, and a `202` to a pending result.

## Consequences

- An agent can do exactly what a tenant administrator chose, with the caller's own roles, and every attempt that
  passed the policy is in the tenant's history.
- Tools with secrets in their input cannot be expressed; a provider whose action needs a per-call secret is out of
  scope until the SDK grows an input schema of its own.
- An action schema can drift from what the connector really accepts; the connector still validates and its refusal is
  a `FAILED` execution.
- Approvals are not four-eyes: a manager may approve their own call. Notifications of pending approvals and a time-out
  for them are not built.
- Per-replica rate limits let n replicas admit n times the limit until #47.
