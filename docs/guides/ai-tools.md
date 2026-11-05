# AI tools

An AI tool is one thing an agent may do in a connected system: a connector action (send a Slack message) or one record
operation on one resource (read, list, create, update, upsert or delete a contact). A tenant administrator exposes each
tool on purpose; nothing is a tool by default. The REST contract is [api.md › AI tools](../architecture/api.md#ai-tools);
the reasoning is [ADR-0026](../adr/0026-ai-tools.md). Agents reach the same tools through the
[MCP endpoint](#connect-an-mcp-client) ([ADR-0027](../adr/0027-mcp-endpoint.md)), which uses the same catalog and the
same execution path.

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

## Connect an MCP client

`POST /api/integrations/mcp` speaks the Model Context Protocol, revision 2025-06-18, over streamable HTTP: stateless,
one JSON-RPC message per POST, JSON answers (no event stream, no sessions, no batches). It adds no right: a client
sees and calls exactly what its token may over REST, and its calls are executions like any other.

**Authenticate** with a Wasichai Bearer token. For an agent, create a service account with a role the tools allow
(`SUPPORT_AGENT`), then exchange its credentials:

```http
POST /api/auth/token
Content-Type: application/json

{"clientId": "…", "clientSecret": "…"}
```

and send the answer's `token` on every MCP request: `Authorization: Bearer <token>`. It is short-lived: fetch a new one
when the endpoint answers `401`. MCP's OAuth discovery is not implemented, so configure the client with the header,
for example:

```json
{
  "mcpServers": {
    "wasichai": {
      "type": "http",
      "url": "https://app.example.com/api/integrations/mcp",
      "headers": {"Authorization": "Bearer ${WASICHAI_TOKEN}"}
    }
  }
}
```

A browser-based client must also be allowed by origin (`wasichai.integrations.ai.mcp.allowed-origins`, see
[deployment](deployment.md)); other clients send no `Origin`.

**A session**, as the client sends it (headers `Content-Type: application/json`,
`Accept: application/json, text/event-stream`, and after `initialize` `MCP-Protocol-Version: 2025-06-18`):

```json
→ {"jsonrpc": "2.0", "id": 1, "method": "initialize",
   "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "my-agent", "version": "1.0"}}}
← {"jsonrpc": "2.0", "id": 1, "result": {"protocolVersion": "2025-06-18",
   "capabilities": {"tools": {"listChanged": false}},
   "serverInfo": {"name": "wasichai-integrations", "title": "Wasichai Integrations", "version": "…"}, "instructions": "…"}}

→ {"jsonrpc": "2.0", "method": "notifications/initialized"}
← 202, no body

→ {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
← {"jsonrpc": "2.0", "id": 2, "result": {"tools": [{"name": "find_contact", "description": "Read one CRM contact by its id.",
   "inputSchema": {"type": "object", "properties": {"id": {"type": "string", "minLength": 1}}, "required": ["id"],
   "additionalProperties": false},
   "annotations": {"readOnlyHint": true, "destructiveHint": false, "openWorldHint": true}}]}}

→ {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "find_contact", "arguments": {"id": "c-17"}}}
← {"jsonrpc": "2.0", "id": 3, "result": {"content": [{"type": "text", "text": "{\"record\":{\"id\":\"c-17\",…}}"}],
   "structuredContent": {"record": {"id": "c-17", "…": "…"}}, "isError": false,
   "_meta": {"dev.wasichai/executionId": "3f2c…"}}}
```

**A tool that needs approval** does not run when called. The result is not an error; it says the call waits:

```json
← {"jsonrpc": "2.0", "id": 4, "result": {"content": [{"type": "text",
   "text": "Execution 9b1e… of tool 'post_to_support' waits for a person's approval; it has not run. Its outcome: …"}],
   "structuredContent": {"executionId": "9b1e…", "tool": "post_to_support", "status": "PENDING_APPROVAL",
   "statusUrl": "/api/integrations/ai/executions/9b1e…"}, "isError": false}}
```

A manager approves or rejects it over REST (below); the agent reads the outcome with
`GET /api/integrations/ai/executions/{id}` and the same token. Calling the tool again makes a second execution.

**Failures and refusals.** A provider failure is a result with `isError: true` and the execution's `error` and
`errorKind`, so the model can react. A call the policy refuses is a JSON-RPC error: `-32602` for an unknown tool or
arguments that do not fit the schema (`data.errors[].field` such as `arguments.text`), `-32001` no allowed role,
`-32002` disabled or connection not active, `-32003` over the rate limit (`data.retryAfter` in seconds). The full table
is in [api.md › MCP endpoint](../architecture/api.md#mcp-endpoint).

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
