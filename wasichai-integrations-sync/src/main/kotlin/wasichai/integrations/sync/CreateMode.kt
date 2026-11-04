package wasichai.integrations.sync

import wasichai.integrations.sdk.FieldValue
import wasichai.integrations.sdk.ProviderRecord
import wasichai.integrations.sdk.ResourceUpserter

// how a create on a target side can be found again after a crash (issue #32)
enum class CreateMode {
    // the connector upserts keyed by the externalIdField: writing again with the same token is idempotent
    UPSERT,

    // the create token is written into the externalIdField and looked up there
    MARKER,

    // nothing to find it by: a create cut short is ORPHANED for an operator
    NONE;

    companion object {
        fun of(
            externalIdField: String?,
            connector: Any
        ): CreateMode =
            when {
                externalIdField == null -> NONE
                connector is ResourceUpserter -> UPSERT
                else -> MARKER
            }

        // the create token a record carries in the marker field, if any
        fun token(
            record: ProviderRecord,
            field: String
        ): String? = (record.fields[field] as? FieldValue.Text)?.value?.takeIf { it.isNotBlank() }
    }
}
