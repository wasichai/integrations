package wasichai.integrations.sdk.testing

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ManifestValidator
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.ResourceDiscovery

// every connector's test suite extends this. it checks the promises the manifest makes;
// the connector's own tests check the provider behaviour against its stub.
abstract class ConnectorContractTest {
    abstract fun connector(): Connector

    // a context whose stub answers discovery; null skips the discovery checks
    open fun discoveryContext(): TestConnectorContext? = null

    @Test
    fun `manifest is valid and matches the implemented interfaces`() {
        assertEquals(emptyList<String>(), ManifestValidator.problems(connector()))
    }

    @Test
    fun `nothing is marked TESTED without being implemented`() {
        val manifest = connector().manifest
        manifest.capabilities
            .filterValues { it == Maturity.TESTED }
            .keys
            .forEach { assertTrue(manifest.supports(it), "$it is TESTED but not supported") }
    }

    @Test
    fun `discovery lists every static resource the manifest declares, and describes it`() {
        val connector = connector()
        val context = discoveryContext()
        assumeTrue(connector.manifest.supports(Capability.DISCOVERY) && context != null, "no discovery to check")
        connector as ResourceDiscovery
        runBlocking {
            val found = connector.listResources(context!!).map { it.name }.toSet()
            connector.manifest.resources.filterNot { it.dynamic }.forEach { declared ->
                assertTrue(declared.name in found, "declared resource ${declared.name} not discovered")
                val schema = connector.describe(context, declared.name)
                assertEquals(declared.name, schema.resource)
                assertTrue(schema.fields.isNotEmpty(), "${declared.name} has no fields")
            }
        }
    }
}
