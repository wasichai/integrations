package wasichai.integrations.connector.salesforce.pubsub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.SdkVersion
import java.time.Duration
import java.time.Instant

class SalesforcePubSubConfigTest {
    @Test
    fun `channels are change data capture channels of valid object names`() {
        SalesforcePubSubConfig(mapOf("Account" to "/data/AccountChangeEvent", "Invoice__c" to "/data/Invoice__ChangeEvent"))
        SalesforcePubSubConfig(mapOf("Account" to "/data/ChangeEvents"))
        assertThrows<IllegalArgumentException> { SalesforcePubSubConfig(emptyMap()) }
        assertThrows<IllegalArgumentException> { SalesforcePubSubConfig(mapOf("Account" to "/event/Order_Placed__e")) }
        assertThrows<IllegalArgumentException> { SalesforcePubSubConfig(mapOf("Account; DROP" to "/data/AccountChangeEvent")) }
    }

    @Test
    fun `batch size, drain and endpoint stay within what the api accepts`() {
        val channels = mapOf("Account" to "/data/AccountChangeEvent")
        assertThrows<IllegalArgumentException> { SalesforcePubSubConfig(channels, batchSize = 101) }
        assertThrows<IllegalArgumentException> { SalesforcePubSubConfig(channels, batchSize = 0) }
        assertThrows<IllegalArgumentException> { SalesforcePubSubConfig(channels, drain = Duration.ZERO) }
        assertThrows<IllegalArgumentException> { SalesforcePubSubConfig(channels, endpoint = "https://api.pubsub.salesforce.com") }
    }

    @Test
    fun `the manifest is the salesforce one, on sdk 1_1, naming the objects read by change data capture`() {
        val connector = SalesforcePubSubConnector(SalesforcePubSubConfig(mapOf("Account" to "/data/AccountChangeEvent")))
        connector.use {
            assertEquals("salesforce", it.manifest.id)
            assertEquals(SdkVersion(1, 1), it.manifest.sdkVersion)
            assertEquals(Maturity.TESTED, it.manifest.maturityOf(Capability.CHANGE_FEED))
            assert("Pub/Sub API for Account" in it.manifest.description)
        }
    }

    @Test
    fun `checkpoints keep the replay id byte for byte, and polling checkpoints are not read as one`() {
        val replay = byteArrayOf(0, 1, -2, 127, -128)
        val state = CdcCheckpoint("/data/AccountChangeEvent", replay, Instant.parse("2026-11-05T10:00:00Z"), null)
        assertEquals(state, CdcCheckpoint.parseOrNull(state.format()))
        assertNull(CdcCheckpoint.parseOrNull("""{"since":"2026-11-05T10:00:00Z"}"""))
        assertNull(CdcCheckpoint.parseOrNull("not json"))
    }
}
