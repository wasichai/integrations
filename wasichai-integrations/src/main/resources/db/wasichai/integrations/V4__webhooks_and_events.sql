-- inbound webhooks and the event envelope (ADR-0007). an endpoint id is random and unguessable; the
-- signature, checked against the endpoint's sealed secret, is what makes a delivery trusted.

CREATE TABLE ${metadataSchema}.integration_webhook_endpoints (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    connection_id   uuid NOT NULL REFERENCES ${metadataSchema}.integration_connections (id) ON DELETE CASCADE,
    connector_id    text NOT NULL,
    secret_sealed   text,
    status          text NOT NULL DEFAULT 'PENDING_VERIFICATION',
    last_delivery_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT integration_webhook_endpoints_status_valid CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED'))
);
CREATE INDEX integration_webhook_endpoints_connection_idx ON ${metadataSchema}.integration_webhook_endpoints (organization_id, connection_id);

CREATE TABLE ${metadataSchema}.integration_events (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    connection_id     uuid NOT NULL REFERENCES ${metadataSchema}.integration_connections (id) ON DELETE CASCADE,
    connector_id      text NOT NULL,
    endpoint_id       uuid REFERENCES ${metadataSchema}.integration_webhook_endpoints (id) ON DELETE SET NULL,
    -- the provider's id when it gives one; otherwise the sha-256 of the delivery: one row per delivery
    provider_event_id text,
    dedupe_key        text NOT NULL,
    event_type        text NOT NULL,
    resource_type     text,
    resource_id       text,
    occurred_at       timestamptz,
    received_at       timestamptz NOT NULL DEFAULT now(),
    source            text NOT NULL,
    schema_version    integer NOT NULL DEFAULT 1,
    correlation_id    text NOT NULL,
    causation_id      text,
    payload           jsonb NOT NULL DEFAULT '{}'::jsonb,
    metadata          jsonb NOT NULL DEFAULT '{}'::jsonb,
    status            text NOT NULL DEFAULT 'RECEIVED',
    processed_at      timestamptz,
    error             text,
    CONSTRAINT integration_events_source_valid CHECK (source IN ('WEBHOOK', 'POLL', 'SYNC', 'SYSTEM')),
    CONSTRAINT integration_events_status_valid CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    CONSTRAINT integration_events_dedupe UNIQUE (connection_id, dedupe_key)
);
CREATE INDEX integration_events_org_idx ON ${metadataSchema}.integration_events (organization_id, received_at DESC, id);
CREATE INDEX integration_events_resource_idx ON ${metadataSchema}.integration_events (connection_id, resource_type, resource_id);
