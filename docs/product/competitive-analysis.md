# Competitive analysis

**Date:** 2026-11-04. Sources are linked inline. Claims resting only on secondary sources or marketing are marked
*(unverified)*. Some vendor docs (developers.notion.com, docs.exalate.com, pipedream.com, prismatic.io) could not be
fetched directly from the research environment; their facts come from search summaries of the cited pages.

## Products

**Nango.** Code-first platform of "functions": syncs (scheduled or webhook-driven, with checkpoints and a records
cache) and actions (writes) ([syncs](https://www.nango.dev/docs/implementation-guides/use-cases/syncs/implement-a-sync)).
TypeScript SDK (`createSync`), replacing the older `nango.yaml` ([functions](https://www.nango.dev/docs/reference/functions)).
Delete tracking is automatic only on full syncs ([sync functions](https://www.nango.dev/docs/guides/functions/syncs/sync-functions.md)).
Inbound webhooks per integration, routed to the connection; docs recommend polling as a backstop
([webhooks](https://nango.dev/docs/getting-started/use-cases/webhooks-from-external-apis)). MCP server per connection
([MCP](https://docs.nango.dev/guides/use-cases/mcp-server)). Elastic License ([#5536](https://github.com/NangoHQ/nango/issues/5536));
free self-hosting covers auth and proxy only ([free self-hosting](https://nango.dev/docs/guides/platform/free-self-hosting.md)).

**Prismatic.** Embedded iPaaS: low-code designer or code-native integrations; Spectral (TypeScript) SDK and `prism`
CLI; custom connectors are private to the organization ([custom connectors](https://prismatic.io/docs/custom-connectors/)).
Per-customer "instances" and an embeddable marketplace. MCP flow server since November 2025: flows marked agentic
become tools, scoped to the customer ([MCP endpoints](https://prismatic.io/docs/ai/mcp-endpoints/)). Self-hosting and
sync primitives *(unverified)*.

**Paragon.** Managed Sync (normalized ingestion), ActionKit (CRUD actions as API and MCP), Workflows
([docs](https://docs.useparagon.com/)). Paragraph: TypeScript integrations-as-code ([Paragraph](https://www.useparagon.com/paragraph)).
On-premise is Enterprise only, Kubernetes with Postgres, Redis and S3 ([on-premise](https://docs.useparagon.com/on-premise)).

**Pipedream Connect.** Joined Workday (announced 2025-11-19,
[SiliconANGLE](https://siliconangle.com/2025/11/19/workday-acquire-pipedream-extend-ai-agent-integrations-across-enterprise-apps/)).
Managed end-user auth, a credentialed proxy, trigger/action "components" ([components](https://pipedream.com/docs/connect/components.md)).
Components under the Pipedream Source Available License, which forbids competing use
([license](https://pipedream.com/blog/introducing-the-pipedream-source-available-license/)).

**Merge.dev.** Unified API with stored Common Models per Linked Account; sync tiers, `modified_after` incremental reads
([sync frequency](https://docs.merge.dev/merge-unified/hris/merge-api-basics/sync-frequency.md)); `remote_was_deleted`
and a paid enhanced deletion detection ([deleted data](https://help.merge.dev/en/articles/5392795-deleted-data-detection));
Agent Handler MCP with Tool Packs ([Agent Handler](https://merge.dev/merge-agent-handler)). Bound to the common model.

**Apideck.** Real-time pass-through unified API, no stored customer data; Vault for credentials; "virtual webhooks"
poll where providers have none, typically every 24 hours depending on plan ([help](https://help.apideck.com/en/articles/7772738)).
MCP with a dynamic 4-meta-tool mode ([MCP](https://developers.apideck.com/mcp)).

**n8n.** Node-based workflow engine; queue mode with Redis and workers, Postgres recommended
([queue mode](https://docs.n8n.io/hosting/scaling/queue-mode/)); MCP Server Trigger needs a single replica for `/mcp*`
([MCP trigger](https://docs.n8n.io/integrations/builtin/core-nodes/n8n-nodes-langchain.mcpTrigger/)). Sustainable Use
License; embedding for end-user accounts needs a commercial license *(unverified, secondary sources)*.

**Activepieces.** Postgres + Redis/BullMQ, stateless app and workers ([hardware](https://activepieces.com/docs/install/configuration/hardware));
TypeScript "pieces" as npm packages ([GitHub](https://github.com/activepieces/activepieces)); MIT community edition,
commercial `ee` folders *(unverified detail)*.

**Temporal.** Durable execution engine, MIT ([GitHub](https://github.com/temporalio/temporal)); Java SDK on the JVM,
no Kotlin SDK ([SDKs](https://docs.temporal.io/encyclopedia/architecture/temporal-sdks.md)); event history capped at
51,200 events / 50 MB, Continue-As-New to go past it ([limits](https://docs.temporal.io/workflow-execution/limits)).
No connectors, OAuth or sync.

**Unito.** SaaS no-code two-way sync "flows"; real-time where webhooks exist, polling 5–15 minutes otherwise
([speed](https://guide.unito.io/how-fast-does-unito-sync)). Per-field direction; conflict rules described on its blog
only *(unverified)* ([blog](https://unito.io/blog/bidirectional-sync/)). No self-hosting or SDK found.

**Exalate.** Distributed: each side keeps outgoing and incoming Groovy scripts and a "replica"
([sync rules](https://docs.exalate.com/docs/sync-rules)); changes processed in order per side, simultaneous updates
queued and resolved by scripts ([simultaneous updates](https://docs.exalate.com/docs/exalate-handle-simultaneous-updates)).
Deployment options and MCP *(unverified)*.

## Capability matrix

Y documented · P partial · N not offered · ? unverified

| Capability | Nango | Prismatic | Paragon | Pipedream | Merge | Apideck | n8n | Activepieces | Temporal | Unito | Exalate |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Managed end-user OAuth | Y | Y | Y | Y | Y | Y | P | P | N | Y | Y |
| Connector SDK | TS | TS | TS | JS/TS | N | N | TS | TS | 7 langs | N | Groovy |
| Records cache | Y | ? | Y | N | Y | N | N | N | N | internal | replica |
| Incremental / checkpointed sync | Y | ? | Y | N | Y | N | N | N | DIY | Y | Y |
| Delete detection | P | ? | ? | N | P (paid) | N | N | N | N | ? | ? |
| Inbound webhook routing | Y | Y | Y | Y | Y | P | Y | Y | N | Y | n/a |
| Workflow engine | functions | Y | Y | Y | N | N | Y | Y | Y | rules | scripts |
| Bidirectional conflict handling | N | N | N | N | N | N | N | N | N | Y (?) | Y |
| Multi-tenant embedded | Y | Y | Y | Y | Y | Y | P | P | N | N | N |
| Self-hosting | P | ? | P | N | ? | ? | Y | Y | Y | N | ? |
| MCP | Y | Y | Y | Y | Y | Y | Y | Y | N | N | ? |

## Implications

1. **Commoditized — build plainly, do not compete on it:** managed OAuth with refresh, credentialed proxying, webhook
   routing and MCP exposure. Every embedded vendor has them. We implement them correctly and simply (vault, PKCE,
   per-connection HTTP) and spend no novelty budget there.
2. **Reuse, do not reinvent:** durable execution exists (Temporal), but its weight (separate cluster, history limits,
   no Kotlin SDK) is not justified for the MVP; Wasichai already has the PostgreSQL patterns we need
   ([ADR-0008](../adr/0008-durable-jobs-in-postgresql.md)). OAuth and HTTP use Spring's WebClient, not a new client.
3. **Differentiate:** bidirectional sync semantics. Only Unito and Exalate (end-user tools) document conflict handling;
   developer platforms leave it to the customer. Field ownership, per-field conflicts that are never overwritten
   silently, echo detection and reconciliation are our core ([ADR-0009](../adr/0009-sync-semantics-and-conflicts.md),
   [ADR-0010](../adr/0010-loop-prevention.md)). Also: libraries under the app's own license and keys, self-hosted by
   default, in a Kotlin/JVM ecosystem none of these SDKs target.
4. **Risk of an over-abstract model:** unified APIs (Merge, Apideck) trade provider semantics for a common model and
   pay for it in coverage. We keep provider-native resources and schemas; canonical models stay optional.
5. **Delete detection is hard everywhere** (Nango partial, Merge paid add-on). Reconciliation is the honest answer and
   is in the MVP; per-provider delete feeds (Salesforce `deleted`) are used where they exist.
6. **Outside the MVP:** large connector catalogs, no-code workflow builders, marketplaces and billing, MCP. The catalog
   race is lost on count; the MVP proves architecture with two deep connectors.
