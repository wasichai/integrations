package wasichai.integrations.sdk

// a provider resource as discovery found it: native names, no forced canonical model
data class ResourceDescriptor(
    val name: String,
    val label: String,
    val description: String? = null
)

data class ResourceSchema(
    val resource: String,
    val label: String,
    val fields: List<FieldDescriptor>
) {
    fun field(name: String): FieldDescriptor? = fields.firstOrNull { it.name == name }
}

data class FieldDescriptor(
    val name: String,
    val label: String,
    val type: FieldType,
    val required: Boolean = false,
    val readOnly: Boolean = false,
    // can identify a record for upsert (a Salesforce external id field)
    val externalId: Boolean = false,
    val options: List<String> = emptyList(),
    // provider type name, kept for diagnosis and advanced mapping
    val nativeType: String? = null
)

enum class FieldType {
    TEXT,
    NUMBER,
    BOOLEAN,
    DATE,
    DATETIME,
    CHOICE,
    MULTI_CHOICE,
    REFERENCE,
    JSON
}
