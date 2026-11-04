package wasichai.integrations.sync

import java.time.Instant
import java.util.UUID

data class CursorView(
    val side: Side,
    val checkpoint: String?,
    val updatedAt: Instant
)

// one definition at a glance: how it went last, what waits for a person, where the feeds stand, what comes next
data class SyncStatusView(
    val definitionId: UUID,
    val status: DefinitionStatus,
    val lastRun: SyncRunView?,
    val lastSuccessAt: Instant?,
    val lastError: String?,
    // transient (it retries by itself) or permanent (a person must act)
    val errorKind: String?,
    val pendingConflicts: Long,
    val links: Map<LinkState, Long>,
    val cursors: List<CursorView>,
    val nextScheduledAt: Instant?,
    val nextReconcileAt: Instant?,
    val queuedOrRunning: Boolean
)
