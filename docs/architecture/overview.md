# Architecture overview

Wasichai Integrations is a set of Wasichai modules inside one Spring Boot WebFlux app on PostgreSQL 18: a modular
monolith ([wasichai ADR-001](https://github.com/wasichai/wasichai/blob/main/docs/adr/0001-modular-monolith.md),
[ADR-024](https://github.com/wasichai/wasichai/blob/main/docs/adr/0024-libraries-and-starters.md)). Contracts are drawn
so that a part can move to its own service later without changing them.

## Module graph

```
wasichai-core (published by wasichai)
   ▲
   ├── wasichai-integrations ──► integrations-connector-sdk
   │       ▲
   │       ├── wasichai-integrations-sync
   │       ├── wasichai-integrations-workflows
   │       └── wasichai-integrations-ai
   │
integrations-connector-notion ─────► integrations-connector-sdk
integrations-connector-salesforce ─► integrations-connector-sdk
integrations-connector-salesforce-pubsub ─► integrations-connector-salesforce   (+ grpc, protobuf, avro)
integrations-connector-jira ───────► integrations-connector-sdk
integrations-connector-github ─────► integrations-connector-sdk
integrations-connector-slack ──────► integrations-connector-sdk
integrations-connector-openapi ────► integrations-connector-sdk
integrations-connector-sdk-test ───► integrations-connector-sdk   (test scope only)
starters/wasichai-spring-boot-starter-integrations(-sync, -workflows, -ai)
integrations-integration-tests (never published)
```

- The SDK knows no Spring and no Wasichai. Connectors depend only on the SDK; the core modules never depend on a
  connector. Provider names appear only in `integrations-connector-*`.
- Each Spring module ships its auto-configuration, `wasichai.<module>.enabled` switch, properties and Flyway history
  (`db/wasichai/integrations`, order 200; `db/wasichai/integrations-sync`, order 210;
  `db/wasichai/integrations-workflows`, order 220; `db/wasichai/integrations-ai`, order 230).

## Control plane and data plane

Logical boundaries inside the same deployable:

| Control plane | Data plane |
|---|---|
| Connector registry and catalog | Webhook ingestion and verification |
| Provider configs (OAuth clients) | Event normalization (Event Envelope) and routing to jobs |
| Connections, credential vault, OAuth | Durable job queue and workers |
| Schema discovery | Connector runtime (`ProviderHttp`, rate limits, retries) |
| Sync definitions and mappings | Sync engine (diff, ownership, conflicts, writes) |
| Conflict resolution, health API | Reconciliation, retry and recovery |
| Workflow definitions, versions, validation, approvals | Workflow runs on the job queue, triggers, schedules |

The control plane is request/response under a user's JWT. The data plane runs as the platform per tenant
(`RecordService.asPlatform` semantics,
[ADR-039](https://github.com/wasichai/wasichai/blob/main/docs/adr/0039-background-work-runs-as-the-platform-with-a-cluster-lock.md)),
driven by jobs claimed with `FOR UPDATE SKIP LOCKED` ([ADR-0008](../adr/0008-durable-jobs-in-postgresql.md)).

## Request and work paths

- **Connect:** UI → `POST …/connections/oauth/start` → provider → public callback → vault → connection `ACTIVE`.
- **Inbound change:** provider → public webhook → connector verifies raw body → Event Envelope stored (deduped) → job →
  incremental read → sync engine.
- **Polling:** schedule (cluster-locked) → job → `ResourceReader.read(since = cursor)` → sync engine → cursor saved.
- **Write:** sync engine → connector `ResourceWriter` through `ProviderHttp` → link and snapshot persisted.
- **Workflow:** editor → draft graph → `validate` → `publish` → immutable version and its trigger row
  ([ADR-0022](../adr/0022-integration-workflows.md)). Event, schedule (cluster-locked), webhook or manual trigger →
  run row pinned to the version → one job per step (`integrations.workflow.step`) → connector actions and writes
  through the SDK → next steps in the same transaction ([ADR-0025](../adr/0025-workflow-execution.md)).

## Where to read next

[components](components.md) · [data model](data-model.md) · [event model](event-model.md) ·
[sync semantics](sync-semantics.md) · [security model](security-model.md) · [REST API](api.md) · [ADRs](../adr/README.md)
