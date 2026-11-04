-- reconciliation findings and the error kind of a failed run (issue #12): what the status endpoint and operators read.

ALTER TABLE ${metadataSchema}.integration_sync_runs
    ADD COLUMN missing_count  integer NOT NULL DEFAULT 0,
    ADD COLUMN diverged_count integer NOT NULL DEFAULT 0,
    ADD COLUMN cursor_resets  integer NOT NULL DEFAULT 0,
    -- transient (the job tries again) or permanent (a person must act); null when the run did not fail
    ADD COLUMN error_kind     text,
    ADD CONSTRAINT integration_sync_runs_error_kind_valid CHECK (error_kind IS NULL OR error_kind IN ('transient', 'permanent'));

-- the scheduler looks for the last run of each kind of every active definition
CREATE INDEX integration_sync_definitions_active_idx ON ${metadataSchema}.integration_sync_definitions (status) WHERE status = 'ACTIVE';
CREATE INDEX integration_sync_runs_trigger_idx ON ${metadataSchema}.integration_sync_runs (definition_id, trigger, queued_at DESC);
