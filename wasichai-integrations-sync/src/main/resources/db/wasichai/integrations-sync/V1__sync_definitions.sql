-- sync definitions (ADR-0009): a resource on connection A, a resource on connection B, a direction and field mappings.
-- mappings live as jsonb on the definition: they are read and written with it, never queried one by one.

CREATE TABLE ${metadataSchema}.integration_sync_definitions (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    name              text NOT NULL,
    a_connection_id   uuid NOT NULL REFERENCES ${metadataSchema}.integration_connections (id) ON DELETE CASCADE,
    a_resource        text NOT NULL,
    b_connection_id   uuid NOT NULL REFERENCES ${metadataSchema}.integration_connections (id) ON DELETE CASCADE,
    b_resource        text NOT NULL,
    direction         text NOT NULL,
    status            text NOT NULL DEFAULT 'DRAFT',
    delete_policy     text NOT NULL DEFAULT 'FLAG',
    schedule_seconds  integer,
    reconcile_seconds integer,
    match_on          jsonb,
    mappings          jsonb NOT NULL DEFAULT '[]'::jsonb,
    version           integer NOT NULL DEFAULT 1,
    created_by        uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT integration_sync_definitions_direction_valid CHECK (direction IN ('A_TO_B', 'B_TO_A', 'BIDIRECTIONAL')),
    CONSTRAINT integration_sync_definitions_status_valid CHECK (status IN ('DRAFT', 'ACTIVE', 'PAUSED')),
    CONSTRAINT integration_sync_definitions_delete_valid CHECK (delete_policy IN ('PROPAGATE', 'IGNORE', 'FLAG')),
    CONSTRAINT integration_sync_definitions_name_length CHECK (char_length(name) BETWEEN 1 AND 200),
    CONSTRAINT integration_sync_definitions_schedule_valid CHECK (schedule_seconds IS NULL OR schedule_seconds >= 60),
    CONSTRAINT integration_sync_definitions_reconcile_valid CHECK (reconcile_seconds IS NULL OR reconcile_seconds >= 300)
);
CREATE INDEX integration_sync_definitions_org_idx ON ${metadataSchema}.integration_sync_definitions (organization_id, status);
CREATE INDEX integration_sync_definitions_a_idx ON ${metadataSchema}.integration_sync_definitions (a_connection_id);
CREATE INDEX integration_sync_definitions_b_idx ON ${metadataSchema}.integration_sync_definitions (b_connection_id);
