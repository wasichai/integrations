package wasichai.integrations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.core.common.NotFoundException
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.InvalidManifestException
import wasichai.integrations.sdk.Maturity

class ConnectorRegistryTest {
    @Test
    fun `lists connectors by id and finds them`() {
        val registry = ConnectorRegistry(listOf(TestConnectors.oauth("b-conn"), TestConnectors.apiKey("a-conn")))
        assertEquals(listOf("a-conn", "b-conn"), registry.all().map { it.manifest.id })
        assertEquals("b-conn", registry.require("b-conn").manifest.id)
        assertNull(registry.find("nope"))
        assertThrows<NotFoundException> { registry.require("nope") }
    }

    @Test
    fun `a duplicate id fails the start`() {
        assertThrows<IllegalArgumentException> { ConnectorRegistry(listOf(TestConnectors.oauth("x-conn"), TestConnectors.apiKey("x-conn"))) }
    }

    @Test
    fun `an invalid manifest fails the start`() {
        val liar =
            object : Connector {
                override val manifest =
                    TestConnectors.apiKey("liar").manifest.copy(capabilities = mapOf(Capability.READ to Maturity.TESTED))
            }
        assertThrows<InvalidManifestException> { ConnectorRegistry(listOf(liar)) }
    }
}
