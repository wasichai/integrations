-- integration workflows (ADR-0022): a definition holds the draft graph; publishing copies it into an immutable,
-- numbered version and derives the trigger from it. graphs are jsonb: read and written whole, never queried by part.

CREATE TABLE ${metadataSchema}.integration_workflow_definitions (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    name              text NOT NULL,
    status            text NOT NULL DEFAULT 'DRAFT',
    graph             jsonb NOT NULL DEFAULT '{}'::jsonb,
    published_version integer,
    version           integer NOT NULL DEFAULT 1,
    created_by        uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT integration_workflow_definitions_status_valid CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT integration_workflow_definitions_name_length CHECK (char_length(name) BETWEEN 1 AND 200),
    CONSTRAINT integration_workflow_definitions_published CHECK ((status = 'PUBLISHED') = (published_version IS NOT NULL))
);
CREATE INDEX integration_workflow_definitions_org_idx ON ${metadataSchema}.integration_workflow_definitions (organization_id, name);

CREATE TABLE ${metadataSchema}.integration_workflow_versions (
    definition_id   uuid NOT NULL REFERENCES ${metadataSchema}.integration_workflow_definitions (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    number          integer NOT NULL,
    graph           jsonb NOT NULL,
    published_at    timestamptz NOT NULL,
    published_by    uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    PRIMARY KEY (definition_id, number),
    CONSTRAINT integration_workflow_versions_number_valid CHECK (number >= 1)
);

-- a version never changes: only published_by may be cleared, when its user is deleted
CREATE FUNCTION ${metadataSchema}.integration_workflow_versions_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.definition_id IS DISTINCT FROM OLD.definition_id OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
        OR NEW.number IS DISTINCT FROM OLD.number OR NEW.graph IS DISTINCT FROM OLD.graph
        OR NEW.published_at IS DISTINCT FROM OLD.published_at OR NEW.published_by IS NOT NULL AND NEW.published_by IS DISTINCT FROM OLD.published_by THEN
        RAISE EXCEPTION 'integration workflow versions are immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER integration_workflow_versions_immutable
    BEFORE UPDATE ON ${metadataSchema}.integration_workflow_versions
    FOR EACH ROW EXECUTE FUNCTION ${metadataSchema}.integration_workflow_versions_immutable();

-- the trigger of the published version, one per definition, for the runner (#42) to find by type and connection
CREATE TABLE ${metadataSchema}.integration_workflow_triggers (
    definition_id   uuid PRIMARY KEY REFERENCES ${metadataSchema}.integration_workflow_definitions (id) ON DELETE CASCADE,
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    version_number  integer NOT NULL,
    type            text NOT NULL,
    -- event triggers only; a used connection is not deleted (ConnectionUsage), so no cascade here
    connection_id   uuid REFERENCES ${metadataSchema}.integration_connections (id),
    event_type      text,
    config          jsonb NOT NULL,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (definition_id, version_number) REFERENCES ${metadataSchema}.integration_workflow_versions (definition_id, number),
    CONSTRAINT integration_workflow_triggers_type_valid CHECK (type IN ('EVENT', 'WEBHOOK', 'SCHEDULE', 'MANUAL')),
    CONSTRAINT integration_workflow_triggers_event CHECK ((type = 'EVENT') = (connection_id IS NOT NULL AND event_type IS NOT NULL))
);
CREATE INDEX integration_workflow_triggers_org_idx ON ${metadataSchema}.integration_workflow_triggers (organization_id, type);
CREATE INDEX integration_workflow_triggers_event_idx ON ${metadataSchema}.integration_workflow_triggers (connection_id, event_type) WHERE type = 'EVENT';
