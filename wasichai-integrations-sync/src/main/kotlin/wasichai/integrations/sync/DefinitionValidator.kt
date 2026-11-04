package wasichai.integrations.sync

import wasichai.core.common.FieldViolation
import wasichai.integrations.ConnectionRepository
import wasichai.integrations.ConnectorContexts
import wasichai.integrations.ConnectorRegistry
import wasichai.integrations.ProviderErrors
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.FieldDescriptor
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.ResourceDiscovery
import wasichai.integrations.sdk.ResourceReader
import wasichai.integrations.sdk.ResourceSchema
import wasichai.integrations.sdk.ResourceUpserter
import wasichai.integrations.sdk.ResourceWriter
import java.util.UUID

// checks a definition: its own shape always, and against both sides' discovered schemas before it may run
class DefinitionValidator(
    private val registry: ConnectorRegistry,
    private val connections: ConnectionRepository,
    private val contexts: ConnectorContexts
) {
    // shape and ownership; connections must be the tenant's. no provider is called
    suspend fun structural(
        organizationId: UUID,
        request: SyncDefinitionRequest
    ): List<FieldViolation> {
        val v = mutableListOf<FieldViolation>()
        val name = request.name?.trim().orEmpty()
        if (name.isEmpty() || name.length > 200) v += FieldViolation("name", "1 to 200 characters")
        if (request.direction == null) v += FieldViolation("direction", "required")
        side(organizationId, request.a, "a", v)
        side(organizationId, request.b, "b", v)
        request.scheduleSeconds?.let { if (it < 60) v += FieldViolation("scheduleSeconds", "at least 60") }
        request.reconcileSeconds?.let { if (it < 300) v += FieldViolation("reconcileSeconds", "at least 300") }
        if (request.mappings.isEmpty()) v += FieldViolation("mappings", "at least one mapping")
        if (request.mappings.size > 200) v += FieldViolation("mappings", "at most 200 mappings")
        val direction = request.direction
        request.mappings.forEachIndexed { i, m ->
            val path = "mappings[$i]"
            if (m.aField.isNullOrBlank()) v += FieldViolation("$path.aField", "required")
            if (m.bField.isNullOrBlank()) v += FieldViolation("$path.bField", "required")
            val transform = Transform.parse(m.transform, "$path.transform", v)
            if (direction != null) {
                if (m.owner == Owner.BOTH &&
                    direction != SyncDirection.BIDIRECTIONAL
                ) {
                    v += FieldViolation("$path.owner", "BOTH needs a BIDIRECTIONAL definition")
                }
                if (m.owner == Owner.A && direction == SyncDirection.B_TO_A) v += FieldViolation("$path.owner", "a field owned by A cannot be written from B")
                if (m.owner == Owner.B && direction == SyncDirection.A_TO_B) v += FieldViolation("$path.owner", "a field owned by B cannot be written from A")
                if (transform != null && !transform.invertible && (m.owner != Owner.A || direction == SyncDirection.B_TO_A)) {
                    v += FieldViolation("$path.transform", "this transform only flows A to B: owner must be A")
                }
            }
        }
        duplicates(request.mappings.mapNotNull { it.aField }).forEach { v += FieldViolation("mappings", "field '$it' of A is mapped twice") }
        duplicates(request.mappings.mapNotNull { it.bField }).forEach { v += FieldViolation("mappings", "field '$it' of B is mapped twice") }
        request.matchOn?.let { if (it.aField.isNullOrBlank() || it.bField.isNullOrBlank()) v += FieldViolation("matchOn", "aField and bField") }
        for (side in Side.entries) externalIdField(side, if (side == Side.A) request.a else request.b, request, v)
        return v
    }

    // only where the sync creates records, and never a mapped field: a mapping would overwrite the create token
    private fun externalIdField(
        side: Side,
        ref: SideRef?,
        request: SyncDefinitionRequest,
        v: MutableList<FieldViolation>
    ) {
        val name = ref?.externalIdField?.trim() ?: return
        val path = "${side.name.lowercase()}.externalIdField"
        if (name.isEmpty() || name.length > 200) {
            v += FieldViolation(path, "1 to 200 characters")
            return
        }
        if (request.direction?.allows(side.other, side) == false) v += FieldViolation(path, "the sync never creates records in ${side.name}")
        if (request.mappings.any { (if (side == Side.A) it.aField else it.bField)?.trim() == name }) {
            v += FieldViolation(path, "'$name' is mapped: a mapping would overwrite the create token")
        }
    }

    // the fields against what the providers say today. provider failures surface as http errors, not as violations
    suspend fun withSchemas(definition: SyncDefinition): List<FieldViolation> {
        val request = definition.toRequest()
        val v = structural(definition.organizationId, request).toMutableList()
        if (v.isNotEmpty()) return v
        val schemas = mutableMapOf<Side, ResourceSchema>()
        val upserts = mutableMapOf<Side, Boolean>()
        for (side in Side.entries) {
            val ref = definition.side(side)
            val connection = connections.find(definition.organizationId, ref.connectionId!!)!!
            val connector = registry.find(connection.connectorId)
            val label = side.name.lowercase()
            if (connector !is ResourceDiscovery) {
                v += FieldViolation("$label.connectionId", "connector '${connection.connectorId}' cannot describe its resources")
                continue
            }
            if (connector !is ResourceReader) v += FieldViolation("$label.connectionId", "connector '${connection.connectorId}' cannot read records")
            if (definition.direction.allows(side.other, side) && connector !is ResourceWriter) {
                v += FieldViolation("$label.connectionId", "connector '${connection.connectorId}' cannot write records")
            }
            upserts[side] = connector is ResourceUpserter
            schemas[side] =
                try {
                    connector.describe(contexts.forConnection(connection), ref.resource!!)
                } catch (e: ConnectorException.NotFound) {
                    v += FieldViolation("$label.resource", "resource '${ref.resource}' does not exist")
                    continue
                } catch (e: ConnectorException) {
                    throw ProviderErrors.toHttp(e)
                }
        }
        if (schemas.size < 2) return v
        definition.mappings.forEachIndexed { i, m -> mapping(i, m, definition.direction, schemas.getValue(Side.A), schemas.getValue(Side.B), v) }
        definition.matchOn?.let { match ->
            if (schemas.getValue(Side.A).field(match.aField!!) == null) v += FieldViolation("matchOn.aField", "no field '${match.aField}' in A")
            if (schemas.getValue(Side.B).field(match.bField!!) == null) v += FieldViolation("matchOn.bField", "no field '${match.bField}' in B")
        }
        for (side in Side.entries) {
            val name = definition.side(side).externalIdField ?: continue
            val schema = schemas.getValue(side)
            externalIdProblem(name, schema.field(name), upserts.getValue(side), schema.label)?.let {
                v += FieldViolation("${side.name.lowercase()}.externalIdField", it)
            }
        }
        return v
    }

    private fun mapping(
        i: Int,
        m: FieldMapping,
        direction: SyncDirection,
        a: ResourceSchema,
        b: ResourceSchema,
        v: MutableList<FieldViolation>
    ) {
        val path = "mappings[$i]"
        val fa = a.field(m.aField!!)
        val fb = b.field(m.bField!!)
        if (fa == null) v += FieldViolation("$path.aField", "no field '${m.aField}' in ${a.label}")
        if (fb == null) v += FieldViolation("$path.bField", "no field '${m.bField}' in ${b.label}")
        if (fa == null || fb == null) return
        if (m.flows(direction, Side.A, Side.B) && fb.readOnly) v += FieldViolation("$path.bField", "'${fb.name}' is read-only")
        if (m.flows(direction, Side.B, Side.A) && fa.readOnly) v += FieldViolation("$path.aField", "'${fa.name}' is read-only")
        val transform = Transform.of(m)
        if (!compatible(transform, fa, fb)) {
            v +=
                FieldViolation("$path.transform", "${fa.type} and ${fb.type} do not fit through ${transform.javaClass.simpleName}")
        }
        if (transform is Transform.EnumMap) {
            if (fb.options.isNotEmpty()) {
                transform.map.values.filterNot { it in fb.options }.forEach {
                    v +=
                        FieldViolation("$path.transform.map", "'$it' is not an option of ${fb.name}")
                }
            }
            if (fa.options.isNotEmpty()) {
                transform.map.keys.filterNot { it in fa.options }.forEach {
                    v +=
                        FieldViolation("$path.transform.map", "'$it' is not an option of ${fa.name}")
                }
            }
        }
    }

    companion object {
        // an upserting connector keys on one of its external id fields; any other connector needs a writable
        // text field to carry the create token (marker mode). null when the field fits
        fun externalIdProblem(
            name: String,
            field: FieldDescriptor?,
            upserts: Boolean,
            label: String
        ): String? =
            when {
                field == null -> "no field '$name' in $label"
                field.readOnly -> "'$name' is read-only"
                upserts && !field.externalId -> "'$name' is not an external id field of $label"
                !upserts && field.type != FieldType.TEXT -> "'$name' must be a TEXT field to hold the create token (the connector cannot upsert)"
                else -> null
            }

        private val TEXTUAL = setOf(FieldType.TEXT, FieldType.CHOICE)
        private val TEMPORAL = setOf(FieldType.DATE, FieldType.DATETIME)

        // similar names imply nothing; types must fit through the transform
        fun compatible(
            transform: Transform,
            a: FieldDescriptor,
            b: FieldDescriptor
        ): Boolean =
            when (transform) {
                is Transform.Copy, is Transform.Default -> same(a.type, b.type)
                is Transform.EnumMap -> a.type in TEXTUAL && b.type in TEXTUAL
                is Transform.DateTime -> a.type in TEMPORAL && b.type in TEMPORAL
                is Transform.Number -> b.type == FieldType.NUMBER && (a.type == FieldType.NUMBER || a.type == FieldType.TEXT)
                is Transform.Static, is Transform.Conditional -> true
            }

        private fun same(
            a: FieldType,
            b: FieldType
        ): Boolean = a == b || (a in TEXTUAL && b in TEXTUAL) || a == FieldType.JSON || b == FieldType.JSON

        private fun duplicates(values: List<String>): Set<String> =
            values
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
    }

    private suspend fun side(
        organizationId: UUID,
        ref: SideRef?,
        label: String,
        v: MutableList<FieldViolation>
    ) {
        if (ref?.connectionId == null) {
            v += FieldViolation("$label.connectionId", "required")
        } else if (connections.find(organizationId, ref.connectionId) == null) {
            // another tenant's connection reads as unknown, like a missing one
            v += FieldViolation("$label.connectionId", "unknown connection")
        }
        if ((ref?.resource ?: "").let { it.isBlank() || it.length > 300 }) v += FieldViolation("$label.resource", "required, at most 300 characters")
    }
}

fun SyncDefinition.toRequest() = SyncDefinitionRequest(name, a, b, direction, deletePolicy, scheduleSeconds, reconcileSeconds, matchOn, mappings)
