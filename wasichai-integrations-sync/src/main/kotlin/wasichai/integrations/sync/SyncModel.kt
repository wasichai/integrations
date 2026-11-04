package wasichai.integrations.sync

import tools.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

enum class Side {
    A,
    B;

    val other: Side get() = if (this == A) B else A
}

enum class SyncDirection {
    A_TO_B,
    B_TO_A,
    BIDIRECTIONAL;

    fun allows(
        from: Side,
        to: Side
    ): Boolean =
        from != to &&
            when (this) {
                A_TO_B -> from == Side.A
                B_TO_A -> from == Side.B
                BIDIRECTIONAL -> true
            }
}

enum class DefinitionStatus {
    DRAFT,
    ACTIVE,
    PAUSED
}

enum class DeletePolicy {
    PROPAGATE,
    IGNORE,
    FLAG
}

enum class Owner {
    A,
    B,
    BOTH
}

// how a field changed on both sides is settled (ADR-0009). owner A or B always wins for its field.
enum class ConflictStrategy {
    SOURCE_WINS,
    A_WINS,
    B_WINS,
    LAST_WRITE_WINS,
    MANUAL
}

data class SideRef(
    val connectionId: UUID? = null,
    val resource: String? = null,
    // a field of this side that makes a create idempotent: the upsert key when the connector upserts,
    // otherwise a writable text field the create token is written into and looked up by (issue #32)
    val externalIdField: String? = null
)

data class MatchOn(
    val aField: String? = null,
    val bField: String? = null
)

// one field on each side, how to carry a value across, who may write it, and what a clash means
data class FieldMapping(
    val aField: String? = null,
    val bField: String? = null,
    val transform: JsonNode? = null,
    val owner: Owner = Owner.BOTH,
    val conflictStrategy: ConflictStrategy = ConflictStrategy.MANUAL
) {
    // the snapshot key: stable across edits that reorder mappings
    val key: String get() = "$aField->$bField"

    fun field(side: Side): String = if (side == Side.A) aField!! else bField!!

    // a value may travel from -> to: the direction allows it and the field is not owned by the target side
    fun flows(
        direction: SyncDirection,
        from: Side,
        to: Side
    ): Boolean = direction.allows(from, to) && (owner == Owner.BOTH || owner.name == from.name)
}

data class SyncDefinition(
    val id: UUID,
    val organizationId: UUID,
    val name: String,
    val a: SideRef,
    val b: SideRef,
    val direction: SyncDirection,
    val status: DefinitionStatus,
    val deletePolicy: DeletePolicy,
    val scheduleSeconds: Int?,
    val reconcileSeconds: Int?,
    val matchOn: MatchOn?,
    val mappings: List<FieldMapping>,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun side(side: Side): SideRef = if (side == Side.A) a else b
}

data class SyncDefinitionRequest(
    val name: String? = null,
    val a: SideRef? = null,
    val b: SideRef? = null,
    val direction: SyncDirection? = null,
    val deletePolicy: DeletePolicy = DeletePolicy.FLAG,
    val scheduleSeconds: Int? = null,
    val reconcileSeconds: Int? = null,
    val matchOn: MatchOn? = null,
    val mappings: List<FieldMapping> = emptyList()
)

data class SyncDefinitionView(
    val id: UUID,
    val name: String,
    val a: SideRef,
    val b: SideRef,
    val direction: SyncDirection,
    val status: DefinitionStatus,
    val deletePolicy: DeletePolicy,
    val scheduleSeconds: Int?,
    val reconcileSeconds: Int?,
    val matchOn: MatchOn?,
    val mappings: List<FieldMapping>,
    val version: Int,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ValidationView(
    val valid: Boolean,
    val errors: List<wasichai.core.common.FieldViolation>
)
