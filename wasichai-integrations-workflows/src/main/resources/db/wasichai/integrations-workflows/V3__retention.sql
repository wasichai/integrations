-- retention (ADR-0029): the purge reads finished runs by age, across tenants
CREATE INDEX integration_workflow_executions_finished_idx ON ${metadataSchema}.integration_workflow_executions (finished_at)
    WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED');
