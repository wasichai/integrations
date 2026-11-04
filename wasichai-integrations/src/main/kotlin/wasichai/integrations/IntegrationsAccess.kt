package wasichai.integrations

import wasichai.core.common.Actions
import wasichai.core.identity.AuthenticatedUser
import wasichai.core.identity.CurrentUser

// who may do what (ADR-0011). managing connections, provider apps and syncs hands out access to another
// system, so it is MANAGE_ORGANIZATION: the tenant administrator, never a service account (ADR-043 of wasichai).
// reading the catalog and the state of connections is any member of the tenant.
class IntegrationsAccess(
    private val currentUser: CurrentUser
) {
    suspend fun reader(): AuthenticatedUser = currentUser.require()

    suspend fun manager(): AuthenticatedUser = currentUser.requireWithPermission(Actions.MANAGE_ORGANIZATION)
}
