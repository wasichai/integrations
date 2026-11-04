# Implementation backlog

One issue, one pull request; a pull request is merged only when the CI unit and integration jobs are green. Backend
lands before the UI that uses it. P0 = needed for the first milestone, P1 = MVP polish, P2 = later.

| # | Issue | Pri | Depends on | Acceptance (summary) | Tests |
|---|---|---|---|---|---|
| 1 | [chore(build): skeleton and CI](https://github.com/wasichai/integrations/issues/1) | P0 | — | CI green; packages resolve | boot smoke IT |
| 2 | [docs: Phase 0](https://github.com/wasichai/integrations/issues/2) | P0 | 1 | 13 questions answered; ADRs | — |
| 3 | [feat(sdk): contracts, manifest, contract kit](https://github.com/wasichai/integrations/issues/3) | P0 | 1 | manifest ⇔ interfaces checked | unit |
| 4 | [feat(integrations): vault, registry, catalog](https://github.com/wasichai/integrations/issues/4) | P0 | 3 | no plaintext secret; isolation; 404 when off | unit + IT |
| 5 | [feat(integrations): connections, OAuth/PKCE](https://github.com/wasichai/integrations/issues/5) | P0 | 4 | state single-use; refresh; revoke | unit + IT (stub OAuth) |
| 6 | [feat(integrations): HTTP runtime, jobs, discovery, health](https://github.com/wasichai/integrations/issues/6) | P0 | 5 | lease recovery; backoff; 429 | unit + IT |
| 7 | [feat(integrations): webhooks and events](https://github.com/wasichai/integrations/issues/7) | P0 | 6 | bad signature 401; dedupe | unit + IT |
| 8 | [feat(connector-notion)](https://github.com/wasichai/integrations/issues/8) | P0 | 3 (6, 7) | contract kit on stubs; HMAC | contract |
| 9 | [feat(connector-salesforce)](https://github.com/wasichai/integrations/issues/9) | P0 | 3 (6) | contract kit; SOQL escaping; upsert | contract |
| 10 | [feat(sync): definitions and mappings](https://github.com/wasichai/integrations/issues/10) | P0 | 6 | invalid mapping 422 | unit + IT |
| 11 | [feat(sync): engine](https://github.com/wasichai/integrations/issues/11) | P0 | 8, 9, 10 | no silent overwrite; no duplicates | unit + IT |
| 12 | [feat(sync): reconciliation and monitoring](https://github.com/wasichai/integrations/issues/12) | P0 | 11 | deletes/divergence detected | unit + IT |
| 13 | [test(it): Notion ↔ Salesforce e2e](https://github.com/wasichai/integrations/issues/13) | P0 | 12 | all scenarios green | e2e IT |
| 14 | [feat(connector-salesforce): CDC](https://github.com/wasichai/integrations/issues/14) | P1 | 13 | replay ids stored; expiry → reconcile | contract (gRPC stub) |
| UI1 | [chore: workspace and CI](https://github.com/wasichai/integrations-ui/issues/1) | P0 | — | `@wasichai/*` resolve; CI green | vitest |
| UI2 | [feat(integrations) UI](https://github.com/wasichai/integrations-ui/issues/2) | P1 | 4, 5, 6 | types match `api.md`; no secret shown | vitest + mockFetch |
| UI3 | [feat(integration-sync) UI](https://github.com/wasichai/integrations-ui/issues/3) | P1 | 10, 11, 12 | violations on the right field | vitest + mockFetch |
| 15 | [later: Phase 5 connectors](https://github.com/wasichai/integrations/issues/15) | P2 | 13 | — | — |
| 16 | [later: Phases 6–8](https://github.com/wasichai/integrations/issues/16) | P2 | 13 | — | — |

## Test strategy

- **Unit**: SDK contracts, manifest validation, error classification, PKCE, vault, transforms, diff/ownership/conflict
  strategies, echo detection, cursor handling, webhook signatures.
- **Integration** (`@Tag("integration")`, Testcontainers PostgreSQL 18, `WasichaiIntegrationTest`): migrations, tenant
  isolation (a second provisioned organization per suite), connection lifecycle, jobs with leases, webhook dedupe,
  sync processing.
- **Contract**: each connector against deterministic stub providers built from documented payloads, through the
  `ConnectorContractTest` kit; an optional sandbox suite gated by environment variables, skipped in CI.
- **End-to-end** (#13): the whole Notion ↔ Salesforce scenario incl. duplicates, conflicts, expired tokens, rate limits,
  worker restart and reconciliation.

## Recommended sequence

1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13, then UI1 → UI2 → UI3 once their backend is merged; 14 if time
allows. 8 and 9 depend only on the SDK and may be reordered with 7 when that unblocks review.
