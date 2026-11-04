package wasichai.integrations.connector.salesforce

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SalesforceConfigTest {
    @Test
    fun `login urls are https, or loopback http for a stub provider`() {
        assertEquals("https://login.salesforce.com", SalesforceConfig().loginUrl)
        SalesforceConfig(loginUrl = "http://127.0.0.1:8123")
        assertThrows<IllegalArgumentException> { SalesforceConfig(loginUrl = "http://login.salesforce.com") }
        assertThrows<IllegalArgumentException> { SalesforceConfig(loginUrl = "http://10.0.0.1") }
        assertThrows<IllegalArgumentException> { SalesforceConfig(apiVersion = "v66") }
    }
}
