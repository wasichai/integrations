-- durable work of the data plane (ADR-0008): webhook processing, sync runs, reconciliation.
-- a worker claims with FOR UPDATE SKIP LOCKED and a lease; a lease that runs out (the worker died) is claimed again.

CREATE TABLE ${metadataSchema}.integration_jobs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    kind            text NOT NULL,
    -- one active job per key: enqueuing the same work twice while it waits or runs is a no-op
    dedupe_key      text,
    payload         jsonb NOT NULL DEFAULT '{}'::jsonb,
    status          text NOT NULL DEFAULT 'PENDING',
    attempts        integer NOT NULL DEFAULT 0,
    max_attempts    integer NOT NULL DEFAULT 8,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until     timestamptz,
    last_error      text,
    error_kind      text,
    correlation_id  text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    finished_at     timestamptz,
    CONSTRAINT integration_jobs_status_valid CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'DEAD')),
    CONSTRAINT integration_jobs_kind_valid CHECK (kind ~ '^[a-z][a-z0-9._-]{0,63}$')
);
CREATE UNIQUE INDEX integration_jobs_active_key
    ON ${metadataSchema}.integration_jobs (organization_id, kind, dedupe_key)
    WHERE dedupe_key IS NOT NULL AND status IN ('PENDING', 'RUNNING');
CREATE INDEX integration_jobs_due_idx ON ${metadataSchema}.integration_jobs (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX integration_jobs_lease_idx ON ${metadataSchema}.integration_jobs (lease_until) WHERE status = 'RUNNING';
CREATE INDEX integration_jobs_org_idx ON ${metadataSchema}.integration_jobs (organization_id, status, updated_at DESC);
