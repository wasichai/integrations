-- wasichai-integrations (ADR-0001, ADR-0005): the oauth app a tenant registered at a provider.
-- the client secret is sealed (AES-256-GCM, bound to tenant, table, row and column); nothing here is plaintext secret.

CREATE TABLE ${metadataSchema}.integration_provider_configs (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id      uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    connector_id         text NOT NULL,
    client_id            text NOT NULL,
    client_secret_sealed text,
    -- scopes asked for on top of the connector's defaults, and provider options (a login url): no secrets
    scopes               jsonb NOT NULL DEFAULT '[]'::jsonb,
    options              jsonb NOT NULL DEFAULT '{}'::jsonb,
    version              integer NOT NULL DEFAULT 1,
    created_by           uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT integration_provider_configs_connector_valid CHECK (connector_id ~ '^[a-z][a-z0-9-]{1,39}$'),
    CONSTRAINT integration_provider_configs_unique UNIQUE (organization_id, connector_id)
);
