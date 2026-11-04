package wasichai.integrations

import java.util.UUID

// another module that holds on to connections (the sync module's definitions). deleting a used connection
// would take their links, runs and open conflicts with it, so it is refused while any usage answers.
fun interface ConnectionUsage {
    // human-readable names of what uses the connection, empty when nothing does
    suspend fun usedBy(
        organizationId: UUID,
        connectionId: UUID
    ): List<String>
}
