# ADR-0027: The MCP endpoint is a stateless JSON-RPC interface over the tool catalog and its one execution path

**Status**: accepted · 2026-11-05 · builds on [ADR-0026](0026-ai-tools.md), [ADR-0013](0013-mcp-exposure-later-and-opt-in.md)

## Context

[#44](https://github.com/wasichai/integrations/issues/44) serves the tools a tenant administrator exposed (ADR-0026) to
agents over the Model Context Protocol. MCP's streamable HTTP transport allows sessions, server-sent event streams,
batches (in some revisions) and its own OAuth discovery. ADR-0013 and ADR-0026 fixed what MCP may not do: run anything
outside `ToolExecutions.execute`, see another tenant, or skip roles, approvals, rate limits and audit.

## Decision

- **Revision 2025-06-18**, the only one served. `initialize` answers the client's `protocolVersion` when it is
  2025-06-18, else 2025-06-18 (the client disconnects if it cannot speak it). Capabilities: `tools` with
  `listChanged: false` only (no resources, prompts, logging, completions, sampling). Later revisions (2025-11-25) are
  left for when a client needs them: they mostly add features, and one changes where input validation errors go.
- **One route, `POST /api/integrations/mcp`**, in `wasichai-integrations-ai`, switch
  `wasichai.integrations.ai.mcp.enabled` (default `true` when the AI module is on). No new module: the endpoint is ~300
  lines over beans the module already has.
- **Authentication is the platform's.** The route sits on Wasichai's security chain like every `/api` route: a Bearer
  JWT, from `/api/auth/login` for a person or `/api/auth/token` (client credentials) for a service account (Wasichai
  ADR-043). No token or a bad one is `401` before the endpoint runs. MCP's OAuth authorization discovery (protected
  resource metadata) is not implemented: a client sends a token it obtained out of band, as an `Authorization` header.
  Tenant, roles and the service account come from the token (`CurrentUser`), never from the message.
- **Stateless.** No `Mcp-Session-Id` is issued and one sent by a client is ignored; any replica answers any message.
  `initialize` keeps nothing, `notifications/initialized` (like every notification) is `202` with no body, and so is a
  JSON-RPC response from a client (the server never sends requests). `GET` (the server-sent stream) and `DELETE`
  (session end) answer `405` with `Allow: POST`. Every answer is `application/json`, never `text/event-stream`:
  nothing here streams, and a tool call answers once.
- **No batches.** 2025-06-18 removed JSON-RPC batching; an array body is `400` with `-32600`.
- **`MCP-Protocol-Version`**: absent is accepted (an older client; nothing differs for a stateless server), present and
  not `2025-06-18` is `400` with `-32600`.
- **`Origin`**: a request that carries one must name an origin in `wasichai.integrations.ai.mcp.allowed-origins`
  (default empty: no browser page may call), else `403` with `-32001`. This guards against DNS rebinding as the spec
  asks; a browser origin must also pass Wasichai's CORS (`wasichai.web.cors-allowed-origin-patterns`), which runs
  first. Clients outside a browser send no `Origin` and are not affected.
- **Methods**: `initialize`, `ping` (`{}`), `tools/list`, `tools/call`; anything else with an id is `-32601`.
- **`tools/list` is `ToolCatalog.list()` restricted to enabled tools**: exactly the tools the caller may call now
  (an allowed role or `MANAGE_ORGANIZATION`, ADR-0026's visibility), sorted by name, `wasichai.integrations.ai.mcp.page-size`
  (default 100) per page with an opaque `cursor`/`nextCursor` (`-32602` for a cursor it did not issue). Each tool is
  `{name, description, inputSchema, annotations}`; the input schema is the stored one; the annotations
  (`readOnlyHint` for read and list, `destructiveHint` for actions, update, upsert and delete, `openWorldHint`) are
  hints, not policy.
- **`tools/call` is `ToolExecutions.execute(name, arguments)`** and nothing else, so the policy, its order, the
  execution row and the audit entries are the REST route's, with the same caller. The result:
  - ran and `SUCCEEDED`: `content` one text item, the output as JSON; `structuredContent` the output when it is an
    object; `isError: false`.
  - ran and `FAILED` (the provider or the tool refused): `isError: true`, the text `{executionId, status, error,
    errorKind}`: an outcome the model may act on, per MCP's tool-error convention, not a protocol error.
  - waits for approval: `isError: false`, a text saying it has not run and where its outcome is, and
    `structuredContent {executionId, tool, status: PENDING_APPROVAL, statusUrl}`. The client learns the outcome over
    REST, `GET /api/integrations/ai/executions/{id}` with the same token (a caller reads their own executions). No
    built-in MCP tool for it: it would be a second, unexposed tool, and REST already has it.
  - `_meta["dev.wasichai/executionId"]` on every result.
- **Errors**: input that cannot be taken as a request (parse error `-32700`; not a JSON-RPC 2.0 object, a batch, a null
  or non-scalar id, a non-string method: `-32600`) is an HTTP `400` whose body is a JSON-RPC error with `id: null`.
  A well-formed request always gets HTTP `200` with a JSON-RPC response. The REST policy's answers map to:

  | REST | JSON-RPC error |
  |---|---|
  | `404` no such tool in the caller's tenant | `-32602` `Unknown tool: <name>` |
  | `400` input does not fit the schema | `-32602`, `data.errors[{field, message}]`, paths `arguments.fields.name` |
  | `403` no allowed role | `-32001` |
  | `409` disabled, or connection not active | `-32002` |
  | `429` over the rate limit | `-32003`, `data.retryAfter` in seconds |
  | anything unexpected | `-32603`, no detail |

  `-32001..-32003` are in JSON-RPC's implementation-defined range. A `403` and a `404` stay distinct, as in REST:
  the tool's name is no secret to a member of the tenant, and the list shows only what may be called.
- **Agent registration (wasichai-agent, Embabel): not now.** wasichai-agent 1.10.0 has a closed tool set
  (`AgentToolCatalog`, `AgentToolbox` built from Kotlin signatures) and deliberately no write tools; its extension
  points are result and answer filters, an access policy and a run listener, not a registration API. Registering
  exposed tools there would mean changing Wasichai, which this repository never does. When wasichai-agent can call an
  MCP server or registers tools, it can use this endpoint as is.

## Consequences

- An agent's MCP client can do exactly what the same token can do over REST, and every call that passed the policy is
  an execution and an audit entry; MCP adds no right, not even to approve.
- No push: a pending call's outcome is polled over REST; a changed catalog is seen on the next `tools/list`
  (`listChanged: false`). A server-sent stream, sessions and change notifications wait for a client that needs them.
- A client that requires OAuth discovery, batches or a newer revision cannot connect until one is implemented.
- Input validation errors are protocol errors (`-32602`), as 2025-06-18 says; a model sees them only if its client
  passes protocol errors on.
