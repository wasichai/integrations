# Guides

How to use, extend and run Wasichai Integrations. The guides describe the code as it is; the REST contract is
[api.md](../architecture/api.md) and the reasoning is in the [ADRs](../adr/README.md).

| Guide | For |
|---|---|
| [User guide](user-guide.md) | people who connect accounts, map fields, run syncs and resolve conflicts in the UI |
| [Connector SDK reference](connector-sdk.md) | connector authors: contracts, manifest, maturity, errors, `ProviderHttp`, `ConnectorContext` |
| [Writing a connector](connector-developer.md) | connector authors: step by step, testing with the contract kit, packaging, versioning |
| [Synchronization](sync.md) | whoever designs sync definitions: mappings, transforms, owners, conflicts, creates, deletes, schedules |
| [Workflows](workflows.md) | whoever designs integration workflows: graph JSON, steps, conditions, validation, versions |
| [AI tools](ai-tools.md) | tenant administrators exposing connector actions and records to agents: schemas, roles, approvals, audit |
| [Deployment](deployment.md) | whoever installs it in an app: dependencies, every property, keys, OAuth, webhooks, scaling |
| [Operations](operations.md) | whoever runs it: health, jobs, events, rate limits, metrics, diagnosis, logs |
