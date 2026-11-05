package wasichai.integrations.connector.slack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import wasichai.integrations.sdk.ConnectorException
import java.time.Duration
import java.time.Instant

class SlackValuesTest {
    @Test
    fun `urls are https, or loopback http for a stub provider`() {
        SlackConfig(apiUrl = "http://127.0.0.1:8123", webUrl = "http://127.0.0.1:8123")
        assertThrows<IllegalArgumentException> { SlackConfig(apiUrl = "http://slack.com/api") }
    }

    @Test
    fun `a ts is epoch seconds with six decimals`() {
        assertEquals(Instant.parse("2017-12-01T00:52:30.000216Z"), SlackTs.instant("1512089550.000216"))
        assertTrue(SlackTs.isTs("1512085950.000216"))
        assertFalse(SlackTs.isTs("1512085950.216"))
        assertFalse(SlackTs.isTs("../x"))
        assertNull(SlackTs.instant("nope"))
        assertEquals("1512085950.000216", SlackTs.max("1512085950.000216", "1512085949.999999"))
        assertEquals("1512085940.000216", SlackTs.minus("1512085950.000216", Duration.ofSeconds(10)))
        assertEquals("0.000000", SlackTs.minus("5.000000", Duration.ofSeconds(10)))
    }

    @Test
    fun `checkpoints round-trip, an unreadable one is refused`() {
        val checkpoint = SlackCheckpoint("1512085950.000216", "dXNlcjpVMDYxTkZUVDI=", "1512085990.000100")
        assertEquals(checkpoint, SlackCheckpoint.parse(checkpoint.render()))
        assertEquals(SlackCheckpoint(null), SlackCheckpoint.parse(null))
        assertEquals("{}", SlackCheckpoint(null).render())
        assertThrows<ConnectorException.Permanent> { SlackCheckpoint.parse("not json") }
        assertThrows<ConnectorException.Permanent> { SlackCheckpoint.parse("""{"oldest":"yesterday"}""") }
    }
}
