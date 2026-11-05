-- retention, GDPR and usage metering (ADR-0029).

-- per tenant daily counters, written as upsert increments by the usage meter's flush. a day is UTC
CREATE TABLE ${metadataSchema}.integration_usage_daily (
    organization_id uuid NOT NULL REFERENCES ${metadataSchema}.organizations (id) ON DELETE CASCADE,
    day             date NOT NULL,
    metric          text NOT NULL,
    count           bigint NOT NULL DEFAULT 0,
    updated_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (organization_id, day, metric),
    CONSTRAINT integration_usage_daily_metric_valid CHECK (metric ~ '^[a-z][a-z0-9._]{0,63}$'),
    CONSTRAINT integration_usage_daily_count_valid CHECK (count >= 0)
);
CREATE INDEX integration_usage_daily_day_idx ON ${metadataSchema}.integration_usage_daily (day);

-- the purge reads finished rows by age, across tenants
CREATE INDEX integration_events_received_idx ON ${metadataSchema}.integration_events (received_at) WHERE status <> 'RECEIVED';
CREATE INDEX integration_jobs_finished_idx ON ${metadataSchema}.integration_jobs (finished_at) WHERE status IN ('SUCCEEDED', 'DEAD');
