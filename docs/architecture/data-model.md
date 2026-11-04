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

## Rules

- **Optimistic locking** on rows people edit (`provider_configs`, `connections`, `sync_definitions`, `record_links`):
  `version` column, `If-Match` over REST, `412` on mismatch, following
  [wasichai ADR-051](https://github.com/wasichai/wasichai/blob/main/docs/adr/0051-optimistic-locking-and-partial-update-of-records.md).
- **Idempotency** lives in unique constraints, not in application checks: job dedupe keys, event provider ids or body
  hashes, link create tokens, single-use OAuth states.
- **Soft delete:** none. A disconnected connection keeps its row with status `DISABLED` until deleted; deleting a
  connection deletes its credentials and refuses while an active sync definition uses it (`409`, the stance of
  [wasichai ADR-017](https://github.com/wasichai/wasichai/blob/main/docs/adr/0017-metadata-deletion-refuses-instead-of-cascading.md)).
- **Retention:** consumed or expired OAuth states purged after a day; succeeded jobs and processed events purged after
  a configured retention (default 30 days); runs kept 90 days; conflicts and links kept while the definition exists.
- **Secrets** never appear outside `*_ciphertext` columns; there is no plaintext column to read by mistake.
