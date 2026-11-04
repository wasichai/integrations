-- creates that survive a crash (issue #32): per side, the target field that makes a create findable again,
-- and a conflict kind that shows an operator a create nobody can recover.

ALTER TABLE ${metadataSchema}.integration_sync_definitions
    -- upsert key (connector implements upsert) or marker field holding the link's create token; null when neither
    ADD COLUMN a_external_id_field text,
    ADD COLUMN b_external_id_field text,
    ADD CONSTRAINT integration_sync_definitions_a_external_id_length CHECK (a_external_id_field IS NULL OR char_length(a_external_id_field) BETWEEN 1 AND 200),
    ADD CONSTRAINT integration_sync_definitions_b_external_id_length CHECK (b_external_id_field IS NULL OR char_length(b_external_id_field) BETWEEN 1 AND 200);

ALTER TABLE ${metadataSchema}.integration_sync_conflicts
    DROP CONSTRAINT integration_sync_conflicts_kind_valid,
    ADD CONSTRAINT integration_sync_conflicts_kind_valid CHECK (kind IN ('FIELDS', 'DELETED', 'ORPHANED_CREATE'));
