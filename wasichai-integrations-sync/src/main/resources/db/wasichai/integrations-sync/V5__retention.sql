-- retention (ADR-0029): the purge reads finished runs and resolved conflicts by age, across tenants
CREATE INDEX integration_sync_runs_finished_idx ON ${metadataSchema}.integration_sync_runs (finished_at) WHERE finished_at IS NOT NULL;
CREATE INDEX integration_sync_conflicts_resolved_idx ON ${metadataSchema}.integration_sync_conflicts (resolved_at) WHERE status = 'RESOLVED';
