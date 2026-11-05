-- workflow execution (ADR-0025): a run is a row pinned to the version it started on; each step is a row and runs as a
-- job of the integrations queue (kind integrations.workflow.step, dedupe key exec:<id>:step:<stepId>).

-- a paused workflow starts no new run from any trigger; runs already going finish
ALTER TABLE ${metadataSchema}.integration_workflow_definitions ADD COLUMN paused boolean NOT NULL DEFAULT false;
-- sha-256 of the secret of the workflow's webhook url; the secret itself is shown once, when issued
ALTER TABLE ${metadataSchema}.integration_workflow_definitions ADD COLUMN webhook_token_hash text;
-- the last schedule firing the scheduler started (the publish upsert does not touch it)
ALTER TABLE ${metadataSchema}.integration_workflow_triggers ADD COLUMN last_fired_at timestamptz;

CREATE TABLE ${metadataSchema}.integration_workflow_executions (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    definition_id   uuid NOT NULL REFERENCES ${metadataSchema}.integration_workflow_definitions (id) ON DELETE CASCADE,
    version_number  integer NOT NULL,
    status          text NOT NULL DEFAULT 'QUEUED',
    trigger_type    text NOT NULL,
    -- what started it, once: event:<id>, schedule:<instant>, webhook:<Idempotency-Key>; null for manual runs
    trigger_key     text,
    -- {type, payload, ...}: what refs of the form trigger.<path> read
    trigger_payload jsonb NOT NULL,
    correlation_id  text NOT NULL,
    started_by      uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    error           text,
    created_at      timestamptz NOT NULL,
    started_at      timestamptz,
    finished_at     timestamptz,
    updated_at      timestamptz NOT NULL,
    FOREIGN KEY (definition_id, version_number) REFERENCES ${metadataSchema}.integration_workflow_versions (definition_id, number) ON DELETE CASCADE,
    CONSTRAINT integration_workflow_executions_status_valid CHECK (status IN ('QUEUED', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT integration_workflow_executions_trigger_valid CHECK (trigger_type IN ('EVENT', 'WEBHOOK', 'SCHEDULE', 'MANUAL'))
);
CREATE UNIQUE INDEX integration_workflow_executions_trigger_key
    ON ${metadataSchema}.integration_workflow_executions (definition_id, trigger_key) WHERE trigger_key IS NOT NULL;
CREATE INDEX integration_workflow_executions_list_idx
    ON ${metadataSchema}.integration_workflow_executions (organization_id, definition_id, created_at DESC, id DESC);

-- one row per step a run reached. no loops (ADR-0022): a step runs at most once per run, so (execution, step) is the key
CREATE TABLE ${metadataSchema}.integration_workflow_step_executions (
    execution_id    uuid NOT NULL REFERENCES ${metadataSchema}.integration_workflow_executions (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    step_id         text NOT NULL,
    step_type       text NOT NULL,
    status          text NOT NULL,
    -- times the step started work; retries and a resumed crash count
    attempt         integer NOT NULL DEFAULT 0,
    input           jsonb,
    output          jsonb,
    error           text,
    -- stable for every attempt of the step: what a provider may dedupe a retry by
    idempotency_key text NOT NULL,
    -- the parallel branches it runs inside, innermost last: [{"parallel": "p", "branch": 0}]
    scope           jsonb NOT NULL DEFAULT '[]'::jsonb,
    -- a join: the branches of its parallel that reached it
    arrivals        integer[] NOT NULL DEFAULT '{}',
    -- a delay's end, an approval's deadline
    wait_until      timestamptz,
    decided_by      uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at      timestamptz NOT NULL,
    started_at      timestamptz,
    finished_at     timestamptz,
    updated_at      timestamptz NOT NULL,
    PRIMARY KEY (execution_id, step_id),
    CONSTRAINT integration_workflow_step_executions_status_valid
        CHECK (status IN ('PENDING', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED', 'SKIPPED'))
);
