-- token buckets shared by every replica (ADR-0030): a provider's rate per connection, an ai tool's per tool.
-- one row per key, updated in place by one atomic upsert per call; tokens may go below zero, a reservation the
-- caller waits out. no tenant column: a key names a connection or a tool, both already tenant-checked by the caller.
-- the row of a deleted connection stays, a few dozen bytes that refill and are never read again.

CREATE TABLE ${metadataSchema}.integration_rate_buckets (
    bucket_key   text PRIMARY KEY,
    tokens       double precision NOT NULL,
    updated_at   timestamptz NOT NULL,
    -- a provider's Retry-After: no replica calls it before then
    paused_until timestamptz,
    CONSTRAINT integration_rate_buckets_key_valid CHECK (length(bucket_key) BETWEEN 1 AND 200)
) WITH (fillfactor = 50);
