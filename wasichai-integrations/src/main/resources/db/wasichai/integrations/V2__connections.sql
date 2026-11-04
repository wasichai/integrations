-- connections to provider accounts (ADR-0006, ADR-0011). credentials live apart, sealed, one row per connection.

CREATE TABLE ${metadataSchema}.integration_connections (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id     uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    connector_id        text NOT NULL,
    name                text NOT NULL,
    -- the provider's stable id of the account; never a display name
    provider_account_id text,
    display_name        text,
    status              text NOT NULL,
    auth_type           text NOT NULL,
    scopes              jsonb NOT NULL DEFAULT '[]'::jsonb,
    -- non-secret facts the connector kept (an instance url, a workspace id)
    metadata            jsonb NOT NULL DEFAULT '{}'::jsonb,
    health              text NOT NULL DEFAULT 'UNKNOWN',
    last_validated_at   timestamptz,
    last_error          text,
    version             integer NOT NULL DEFAULT 1,
    created_by          uuid REFERENCES ${metadataSchema}.users (id) ON DELETE SET NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT integration_connections_status_valid CHECK (status IN ('ACTIVE', 'NEEDS_REAUTH', 'DISABLED', 'ERROR')),
    CONSTRAINT integration_connections_health_valid CHECK (health IN ('UNKNOWN', 'HEALTHY', 'DEGRADED', 'FAILING')),
    CONSTRAINT integration_connections_auth_valid
        CHECK (auth_type IN ('OAUTH2_AUTHORIZATION_CODE', 'OAUTH2_CLIENT_CREDENTIALS', 'API_KEY', 'BASIC')),
    CONSTRAINT integration_connections_name_length CHECK (char_length(name) BETWEEN 1 AND 200)
);
-- one connection per provider account and connector in a tenant: connecting it again refreshes it
CREATE UNIQUE INDEX integration_connections_account_unique
    ON ${metadataSchema}.integration_connections (organization_id, connector_id, provider_account_id)
    WHERE provider_account_id IS NOT NULL;
CREATE INDEX integration_connections_org_idx ON ${metadataSchema}.integration_connections (organization_id, connector_id);

CREATE TABLE ${metadataSchema}.integration_connection_credentials (
    connection_id    uuid PRIMARY KEY REFERENCES ${metadataSchema}.integration_connections (id) ON DELETE CASCADE,
    organization_id  uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    -- sealed json of the secrets (tokens, key, password); see CredentialCipher
    sealed           text NOT NULL,
    access_expires_at timestamptz,
    refreshed_at     timestamptz,
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- one row per authorization started. the state itself is never stored, only its sha-256; the pkce
-- verifier is sealed. single use: consumed_at is set by the callback that wins.
CREATE TABLE ${metadataSchema}.integration_oauth_states (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    user_id         uuid NOT NULL REFERENCES ${metadataSchema}.users (id) ON DELETE CASCADE,
    connector_id    text NOT NULL,
    state_hash      text NOT NULL UNIQUE,
    verifier_sealed text,
    redirect_uri    text NOT NULL,
    connection_name text NOT NULL,
    expires_at      timestamptz NOT NULL,
    consumed_at     timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX integration_oauth_states_expiry_idx ON ${metadataSchema}.integration_oauth_states (expires_at);
