# Data model

All tables live in the metadata schema (`${metadataSchema}`, default `wasichai`), are created by the owning module's
Flyway history and carry `organization_id uuid not null` with an index leading on it. Ids are `uuid`. Timestamps are
`timestamptz`. JSON is `jsonb`. This is the target model; each table arrives with the issue that needs it.

## wasichai-integrations (`db/wasichai/integrations`)

| Table | Key columns | Constraints and notes |
|---|---|---|
| `provider_configs` | id, org, connector_id, client_id, `client_secret_*` (ciphertext, nonce, key_id), redirect_uri, scopes, version | UNIQUE (org, connector_id) |
| `connections` | id, org, connector_id, name, provider_account_id, status, auth_type, scopes text[], metadata, health, last_validated_at, last_error, created_by, version | UNIQUE (org, connector_id, provider_account_id) when account id is known |
| `connection_credentials` | connection_id PK/FK, org, ciphertext, nonce, key_id, expires_at, updated_at | 1:1 with connection; cascade on connection delete |
| `oauth_states` | id, org, user_id, connector_id, state_hash UNIQUE, verifier ciphertext/nonce/key_id, redirect_uri, expires_at, consumed_at | single use: consumed in the same transaction as the token write; purged after expiry |
| `integration_jobs` | id, org, kind, dedupe_key, payload, status, attempts, max_attempts, next_attempt_at, lease_until, last_error, correlation_id, created_at, finished_at | UNIQUE (org, kind, dedupe_key) for live jobs; partial index on (status, next_attempt_at) where PENDING |
| `webhook_endpoints` | id (random, unguessable), org, connection_id FK, secret ciphertext/nonce/key_id, status, created_at | id is the path segment |
| `integration_events` | id, org, connection_id FK, connector_id, provider_event_id, body_hash, resource_type, resource_id, event_type, occurred_at null, received_at, source, correlation_id, causation_id, schema_version, payload, metadata, status | UNIQUE (connection_id, provider_event_id) and UNIQUE (connection_id, body_hash) when no provider id |
| `integration_usage_daily` | org, day (UTC date), metric, count bigint, updated_at | PK (org, day, metric); metric `^[a-z][a-z0-9._]{0,63}$`; written as upsert increments by the usage meter's flush (ADR-0029) |

## wasichai-integrations-sync (`db/wasichai/integrations-sync`)

| Table | Key columns | Constraints and notes |
|---|---|---|
| `sync_definitions` | id, org, name, a_connection_id, a_resource, a_external_id_field, b_connection_id, b_resource, b_external_id_field, direction, schedule, reconcile_schedule, delete_policy, status, version | UNIQUE (org, name); FKs to `connections` |
| `field_mappings` | id, definition_id FK, position, a_field, b_field, transform jsonb, owner, conflict_strategy | UNIQUE (definition_id, a_field, b_field) |
| `sync_cursors` | definition_id, side, cursor jsonb, updated_at | PK (definition_id, side) |
| `record_links` | id, org, definition_id, a_id, b_id null, a_version, b_version, a_written_hash, b_written_hash, state, create_token, updated_at, version | UNIQUE (definition_id, a_id); UNIQUE (definition_id, b_id); UNIQUE (definition_id, create_token) |
| `record_snapshots` | link_id PK/FK, values jsonb, hash, synced_at | last values both sides agreed on |
| `sync_runs` | id, org, definition_id, kind, status, started_at, finished_at, counts jsonb, error, correlation_id | index (definition_id, started_at desc) |
| `sync_conflicts` | id, org, link_id, definition_id, kind (FIELDS, DELETED, ORPHANED_CREATE), fields jsonb (a, b, base per field), status, detected_at, resolved_by, resolved_at, resolution jsonb | one OPEN conflict per link (partial unique index) |

## wasichai-integrations-workflows (`db/wasichai/integrations-workflows`)

| Table | Key columns | Constraints and notes |
|---|---|---|
| `integration_workflow_definitions` | id, org, name, status (DRAFT, PUBLISHED), graph jsonb (the draft), published_version, version, created_by, paused, webhook_token_hash | PUBLISHED exactly when published_version is set; paused starts no run; only the SHA-256 of the webhook secret |
| `integration_workflow_versions` | definition_id FK, org, number, graph jsonb, published_at, published_by | PK (definition_id, number); an update trigger refuses any change but clearing published_by |
| `integration_workflow_triggers` | definition_id PK/FK, org, version_number, type (EVENT, WEBHOOK, SCHEDULE, MANUAL), connection_id, event_type, config jsonb, last_fired_at | the published version's trigger, replaced on publish (last_fired_at kept); FK (definition_id, version_number) to versions; index (connection_id, event_type) for events |
| `integration_workflow_executions` | id, org, definition_id, version_number, status (QUEUED, RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED), trigger_type, trigger_key, trigger_payload jsonb, correlation_id, started_by, error, created/started/finished_at | FK (definition_id, version_number) to versions, cascade with the workflow; unique (definition_id, trigger_key) where set: one run per event, schedule firing or webhook key; index (org, definition_id, created_at desc, id desc) |
| `integration_workflow_step_executions` | execution_id FK, org, step_id, step_type, status (PENDING, RUNNING, WAITING, SUCCEEDED, FAILED, SKIPPED), attempt, input/output jsonb, error, idempotency_key, scope jsonb, arrivals int[], wait_until, decided_by | PK (execution_id, step_id): no loops, a step runs at most once per run; a join's arrivals are counted in its row (ADR-0025) |


## wasichai-integrations-ai (`db/wasichai/integrations-ai`)

| Table | Key columns | Constraints and notes |
|---|---|---|
| `integration_ai_tools` | id, org, connection_id FK, name, description, kind (ACTION, RESOURCE), action, resource, operation, external_id_field, input_schema jsonb, field_types jsonb, allowed_roles jsonb, approval_required, rate_limit_per_minute, enabled, version, created_by | UNIQUE (org, name); name `^[a-z][a-z0-9_]{0,63}$`; ACTION has only action, RESOURCE resource and operation; the connection is held (`ConnectionUsage`) |
| `integration_ai_tool_executions` | id, org, tool_id FK (SET NULL), tool_name, tool_version, connection_id, caller_id, service_account, input jsonb, status (PENDING_APPROVAL, RUNNING, SUCCEEDED, FAILED, REJECTED), output jsonb (masked summary ≤ 16 KiB), output_truncated, error, error_kind, decided_by, decided_at, reason, correlation_id, created_at, started_at, finished_at | index (org, created_at desc, id desc) and (org, caller_id, created_at desc); a decision moves PENDING_APPROVAL by a conditional update, once |

## Rules

- **Optimistic locking** on rows people edit (`provider_configs`, `connections`, `sync_definitions`, `record_links`,
  `integration_workflow_definitions`, `integration_ai_tools`):
  `version` column, `If-Match` over REST, `412` on mismatch, following
  [wasichai ADR-051](https://github.com/wasichai/wasichai/blob/main/docs/adr/0051-optimistic-locking-and-partial-update-of-records.md).
- **Idempotency** lives in unique constraints, not in application checks: job dedupe keys, event provider ids or body
  hashes, link create tokens, single-use OAuth states.
- **Soft delete:** none. A disconnected connection keeps its row with status `DISABLED` until deleted; deleting a
  connection deletes its credentials and refuses while a sync definition, a workflow or an AI tool uses it (`409`, the stance of
  [wasichai ADR-017](https://github.com/wasichai/wasichai/blob/main/docs/adr/0017-metadata-deletion-refuses-instead-of-cascading.md));
  the explicit, audited erasure is the only cascade across modules.
- **Retention** ([ADR-0029](../adr/0029-retention-gdpr-usage.md)): expired OAuth states are purged when a new
  authorization starts; a cluster-locked purge deletes, in batches, events that are not `RECEIVED` and `SUCCEEDED`/`DEAD`
  jobs after 30 days, finished sync runs after 90 (the latest per definition and trigger, and the latest successful,
  stay), resolved conflicts after 180, finished workflow runs after 90, finished AI tool executions after 180 and usage
  rows after 400 (`wasichai.integrations.retention.max-age.<task>`). Open conflicts, links and snapshots stay while
  their definition exists; nothing running, waiting or pending is purged. Indexes on the purge columns
  (`received_at`, `finished_at`, `resolved_at`) are partial on the purgeable statuses.
- **Erasure** (ADR-0029): `POST /connections/{id}/erase` deletes a connection with every row derived from it in every
  module in one transaction; `erase-record` forgets one provider record (events deleted, its side of sync links
  cleared and the link `ORPHANED`, finished runs and executions carrying the id deleted).
- **Secrets** never appear outside `*_ciphertext` columns; there is no plaintext column to read by mistake.
