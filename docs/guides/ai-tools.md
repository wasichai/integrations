# AI tools

An AI tool is one thing an agent may do in a connected system: a connector action (send a Slack message) or one record
operation on one resource (read, list, create, update, upsert or delete a contact). A tenant administrator exposes each
tool on purpose; nothing is a tool by default. The REST contract is [api.md › AI tools](../architecture/api.md#ai-tools);
the reasoning is [ADR-0026](../adr/0026-ai-tools.md). The MCP endpoint that serves these tools to agents is
[#44](https://github.com/wasichai/integrations/issues/44); it uses the same catalog and the same execution path.

## Install

Add `wasichai:wasichai-spring-boot-starter-integrations-ai` (or the module `wasichai-integrations-ai` beside an
assembled Wasichai). It migrates its own tables (`integrations_ai`) and is on unless
`wasichai.integrations.ai.enabled=false`.

## Expose a tool

`POST /api/integrations/ai/tools` as a tenant administrator (`MANAGE_ORGANIZATION`).

A connector action. The SDK manifest declares actions without an input schema, so you write it:

```json
{
  "name": "post_to_support",
  "description": "Post a message to a support channel. Use for customer-facing status updates only.",
  "connectionId": "…",
  "action": "send_message",
  "inputSchema": {
    "type": "object",
    "properties": {
      "channel": {"type": "string", "enum": ["support", "support-escalations"]},
      "text": {"type": "string", "minLength": 1, "maxLength": 2000}
    },
    "required": ["channel", "text"]
  },
  "allowedRoles": ["SUPPORT_AGENT"],
  "rateLimitPerMinute": 10
}
```

A record operation. Its input schema is derived from the resource as the connector describes it now:

```json
{
  "name": "find_contact",
  "description": "Read one CRM contact by its id.",
  "connectionId": "…",
  "resource": "contacts",
  "operation": "read",
  "allowedRoles": ["SUPPORT_AGENT"]
}
```

What exposing checks: the connection is the tenant's and its connector installed; the connector declares and implements
the action or the operation (`ACTIONS`, `READ`, `WRITE`, `UPSERT`); the resource is declared or discovered and exists;
an upsert's `externalIdField` is an external id field; the roles exist in the tenant; the name is free; the schema is
in the supported subset. Every problem comes back at once, each with its field.

### Writing an input schema

Supported: `type` (`object`, `string`, `number`, `integer`, `boolean`, `array`), `properties`, `required`, `enum`,
`minLength`, `maxLength`, `format` (`date`, `date-time`), `minimum`, `maximum`, `items`, `minItems`, `maxItems`,
`title`, `description`. The root is an object; nesting at most 8 deep. Objects are closed: unknown properties are
refused, and the stored schema says so (`"additionalProperties": false`). Anything else, `pattern`, `oneOf`, `$ref`,
`format: email`, is refused when you expose the tool rather than quietly ignored later. Keep schemas tight: an `enum`
of channels is a better guard than a free string.

No property may look like a credential (`password`, `secret`, `token`, `apiKey`, `authorization`, `credential`,
`cookie`): the connection holds the credentials, and a tool never takes them from a model.

### Choosing roles and approval

| Setting | Default | Advice |
|---|---|---|
| `allowedRoles` | none: only `MANAGE_ORGANIZATION` | make a role per kind of agent (`SUPPORT_AGENT`) and give it to the agent's service account or users |
| `approvalRequired` | `true` for actions and writes, `false` for read and list | turn it off only for actions that are harmless and easy to undo |
| `rateLimitPerMinute` | 60 | per tool, per replica: three replicas admit up to three times the limit |
| `enabled` | `true` | switch a tool off without losing its definition |

Members see only the enabled tools a role of theirs may call. Tool names and descriptions are what a model reads to
choose a tool: say what it does and when not to use it.

## Execute a tool

`POST /api/integrations/ai/tools/{name}/execute` with the input as the body:

- `200`: it ran. `status` `SUCCEEDED` with `output`, or `FAILED` with `error` and `errorKind` when the provider refused
  (a provider failure is an outcome, not an HTTP error).
- `202`: it waits for approval (`PENDING_APPROVAL`). Poll `GET /executions/{id}`; a caller reads their own executions.
- `400` the input does not fit the schema (each `errors[].field` is a path such as `input.fields.name`), `403` no
  allowed role, `404` no such tool in the tenant, `409` disabled or the connection is not active, `429` over the rate
  limit (`Retry-After` says when).

## Approve or reject

A manager lists what waits: `GET /api/integrations/ai/executions?status=PENDING_APPROVAL`, reads the `input`, then
`POST /executions/{id}/approve` (it runs once and answers with the result; a second approve is `409`) or
`POST /executions/{id}/reject` with an optional `{"reason": "…"}`. If the tool was changed, disabled or deleted after the
call, approving is refused: reject it and let the agent ask again.

## What is recorded

Every execution that passed the policy is a row: tool, caller, input, status, decision, output summary, error,
correlation id and times; and each step is written to Wasichai's audit log. The input is stored as sent (it is user
data, at most 64 KiB, and cannot carry credential-like keys). The stored output masks credential-like keys as `"***"`
and is capped at 16 KiB; only the immediate answer carries the provider's whole output. Refused calls (`400`, `403`,
`404`, `409`, `429`) are not executions: they are counted in `integrations.ai.tool.refusals{reason}`. Executions are
counted in `integrations.ai.tool.executions{status}`.

## Deleting

Deleting a tool keeps its executions (with its name). A connection used by a tool cannot be deleted (`409 … tool
'x'`): delete the tools first.
