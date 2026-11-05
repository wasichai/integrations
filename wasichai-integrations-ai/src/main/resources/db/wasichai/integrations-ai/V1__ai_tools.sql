-- AI tools (ADR-0026): what a tenant administrator exposed, and every execution of it. nothing is exposed by default.

CREATE TABLE ${metadataSchema}.integration_ai_tools (
    id                    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    -- a connection a tool stands on is not deleted under it (ConnectionUsage), so no cascade here
    connection_id         uuid NOT NULL REFERENCES ${metadataSchema}.integration_connections (id),
    name                  text NOT NULL,
    description           text NOT NULL,
    kind                  text NOT NULL,
    action                text,
    resource              text,
    operation             text,
    external_id_field     text,
    input_schema          jsonb NOT NULL,
    field_types           jsonb NOT NULL DEFAULT '{}'::jsonb,
    allowed_roles         jsonb NOT NULL DEFAULT '[]'::jsonb,
    approval_required     boolean NOT NULL,
    rate_limit_per_minute integer NOT NULL,
    enabled               boolean NOT NULL DEFAULT true,
    version               integer NOT NULL DEFAULT 1,
    created_by            uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT integration_ai_tools_name_unique UNIQUE (organization_id, name),
    CONSTRAINT integration_ai_tools_name_valid CHECK (name ~ '^[a-z][a-z0-9_]{0,63}$'),
    CONSTRAINT integration_ai_tools_description_length CHECK (char_length(description) BETWEEN 1 AND 1000),
    CONSTRAINT integration_ai_tools_kind_valid CHECK (kind IN ('ACTION', 'RESOURCE')),
    CONSTRAINT integration_ai_tools_operation_valid CHECK (operation IN ('READ', 'LIST', 'CREATE', 'UPDATE', 'UPSERT', 'DELETE')),
    CONSTRAINT integration_ai_tools_action CHECK ((kind = 'ACTION') = (action IS NOT NULL AND resource IS NULL AND operation IS NULL)),
    CONSTRAINT integration_ai_tools_resource CHECK ((kind = 'RESOURCE') = (resource IS NOT NULL AND operation IS NOT NULL AND action IS NULL)),
    CONSTRAINT integration_ai_tools_rate_valid CHECK (rate_limit_per_minute BETWEEN 1 AND 10000)
);
CREATE INDEX integration_ai_tools_connection_idx ON ${metadataSchema}.integration_ai_tools (organization_id, connection_id);

-- one row per execution that passed the policy. the tool may be deleted later: its name and version stay here
CREATE TABLE ${metadataSchema}.integration_ai_tool_executions (
    id               uuid PRIMARY KEY,
    organization_id  uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    tool_id          uuid REFERENCES ${metadataSchema}.integration_ai_tools (id) ON DELETE SET NULL,
    tool_name        text NOT NULL,
    tool_version     integer NOT NULL,
    connection_id    uuid NOT NULL,
    caller_id        uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    service_account  text,
    -- the caller's input as validated (at most 64 KiB, no credential-like keys: the schema allows none)
    input            jsonb NOT NULL,
    status           text NOT NULL,
    -- a summary: credential-like keys masked, at most 16 KiB
    output           jsonb,
    output_truncated boolean NOT NULL DEFAULT false,
    error            text,
    error_kind       text,
    decided_by       uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    decided_at       timestamptz,
    reason           text,
    correlation_id   text,
    created_at       timestamptz NOT NULL,
    started_at       timestamptz,
    finished_at      timestamptz,
    CONSTRAINT integration_ai_tool_executions_status_valid CHECK (status IN ('PENDING_APPROVAL', 'RUNNING', 'SUCCEEDED', 'FAILED', 'REJECTED'))
);
CREATE INDEX integration_ai_tool_executions_org_idx ON ${metadataSchema}.integration_ai_tool_executions (organization_id, created_at DESC, id DESC);
CREATE INDEX integration_ai_tool_executions_caller_idx ON ${metadataSchema}.integration_ai_tool_executions (organization_id, caller_id, created_at DESC);
