-- the sync engine's state (ADR-0009, ADR-0010): which records are the same, what both sides last agreed on,
-- where each side's feed stands, every run, and the conflicts waiting for a person.

CREATE TABLE ${metadataSchema}.integration_record_links (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    definition_id   uuid NOT NULL REFERENCES ${metadataSchema}.integration_sync_definitions (id) ON DELETE CASCADE,
    -- one side is null only while the record is being created there (PENDING_CREATE)
    a_id            text,
    b_id            text,
    a_version       text,
    b_version       text,
    -- hash of the mapped values as last seen or written per side: an equal hash is an echo or nothing new
    a_hash          text,
    b_hash          text,
    state           text NOT NULL,
    create_token    uuid NOT NULL DEFAULT gen_random_uuid(),
    last_synced_at  timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT integration_record_links_state_valid CHECK (state IN ('LINKED', 'PENDING_CREATE', 'CONFLICT', 'ORPHANED')),
    CONSTRAINT integration_record_links_one_side CHECK (a_id IS NOT NULL OR b_id IS NOT NULL)
);
CREATE UNIQUE INDEX integration_record_links_a_unique ON ${metadataSchema}.integration_record_links (definition_id, a_id) WHERE a_id IS NOT NULL;
CREATE UNIQUE INDEX integration_record_links_b_unique ON ${metadataSchema}.integration_record_links (definition_id, b_id) WHERE b_id IS NOT NULL;
CREATE INDEX integration_record_links_state_idx ON ${metadataSchema}.integration_record_links (definition_id, state);

CREATE TABLE ${metadataSchema}.integration_record_snapshots (
    link_id    uuid PRIMARY KEY REFERENCES ${metadataSchema}.integration_record_links (id) ON DELETE CASCADE,
    -- {"<aField>-><bField>": {"a": <value>, "b": <value>}}: the base of every diff
    values     jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ${metadataSchema}.integration_sync_cursors (
    definition_id uuid NOT NULL REFERENCES ${metadataSchema}.integration_sync_definitions (id) ON DELETE CASCADE,
    side          text NOT NULL,
    checkpoint    text,
    updated_at    timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (definition_id, side),
    CONSTRAINT integration_sync_cursors_side_valid CHECK (side IN ('A', 'B'))
);

CREATE TABLE ${metadataSchema}.integration_sync_runs (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    definition_id   uuid NOT NULL REFERENCES ${metadataSchema}.integration_sync_definitions (id) ON DELETE CASCADE,
    trigger         text NOT NULL,
    status          text NOT NULL,
    read_count      integer NOT NULL DEFAULT 0,
    created_count   integer NOT NULL DEFAULT 0,
    updated_count   integer NOT NULL DEFAULT 0,
    skipped_count   integer NOT NULL DEFAULT 0,
    conflict_count  integer NOT NULL DEFAULT 0,
    failed_count    integer NOT NULL DEFAULT 0,
    error           text,
    queued_at       timestamptz NOT NULL DEFAULT now(),
    started_at      timestamptz,
    finished_at     timestamptz,
    CONSTRAINT integration_sync_runs_trigger_valid CHECK (trigger IN ('MANUAL', 'SCHEDULE', 'EVENT', 'INITIAL', 'RECONCILE')),
    CONSTRAINT integration_sync_runs_status_valid CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'))
);
CREATE INDEX integration_sync_runs_definition_idx ON ${metadataSchema}.integration_sync_runs (definition_id, queued_at DESC);

CREATE TABLE ${metadataSchema}.integration_sync_conflicts (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    definition_id   uuid NOT NULL REFERENCES ${metadataSchema}.integration_sync_definitions (id) ON DELETE CASCADE,
    link_id         uuid NOT NULL REFERENCES ${metadataSchema}.integration_record_links (id) ON DELETE CASCADE,
    kind            text NOT NULL,
    -- [{"key", "aField", "bField", "a", "b", "base"}]
    fields          jsonb NOT NULL DEFAULT '[]'::jsonb,
    status          text NOT NULL DEFAULT 'OPEN',
    resolution      jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    resolved_at     timestamptz,
    resolved_by     uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    CONSTRAINT integration_sync_conflicts_kind_valid CHECK (kind IN ('FIELDS', 'DELETED')),
    CONSTRAINT integration_sync_conflicts_status_valid CHECK (status IN ('OPEN', 'RESOLVED'))
);
-- one open conflict per link: a link in CONFLICT is not processed until it is resolved
CREATE UNIQUE INDEX integration_sync_conflicts_open_unique ON ${metadataSchema}.integration_sync_conflicts (link_id) WHERE status = 'OPEN';
CREATE INDEX integration_sync_conflicts_org_idx ON ${metadataSchema}.integration_sync_conflicts (organization_id, status, created_at DESC);
