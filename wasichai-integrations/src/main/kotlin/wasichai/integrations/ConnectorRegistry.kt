package wasichai.integrations

import wasichai.core.common.NotFoundException
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ManifestValidator
import wasichai.integrations.sdk.SdkVersion

// the connectors an app installed: every Connector bean, checked once at start. an invalid manifest or a
// duplicate id fails the start, so a connector never runs on promises it does not keep.
class ConnectorRegistry(
    connectors: List<Connector>,
    runtime: SdkVersion = SdkVersion.CURRENT
) {
    private val byId: Map<String, Connector>

    init {
        connectors.forEach { ManifestValidator.validate(it, runtime) }
        val duplicates = connectors.groupBy { it.manifest.id }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "connector ids registered twice: $duplicates" }
        byId = connectors.associateBy { it.manifest.id }.toSortedMap()
    }

    fun all(): List<Connector> = byId.values.toList()

    fun find(id: String): Connector? = byId[id]

    // unknown and not installed look the same to a caller: 404
    fun require(id: String): Connector = byId[id] ?: throw NotFoundException("Connector '$id' not found")

    inline fun <reified T> capability(id: String): T? = require(id) as? T
}
