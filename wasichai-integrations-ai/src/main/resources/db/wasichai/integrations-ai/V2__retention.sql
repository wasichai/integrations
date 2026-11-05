-- retention (ADR-0029): the purge reads finished executions by age, across tenants; export and erasure by connection
CREATE INDEX integration_ai_tool_executions_finished_idx ON ${metadataSchema}.integration_ai_tool_executions (finished_at)
    WHERE status IN ('SUCCEEDED', 'FAILED', 'REJECTED');
CREATE INDEX integration_ai_tool_executions_connection_idx ON ${metadataSchema}.integration_ai_tool_executions (organization_id, connection_id);
