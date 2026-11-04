package wasichai.integrations.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SdkVersionTest {
    @Test
    fun `parses major and minor`() {
        assertEquals(SdkVersion(1, 2), SdkVersion.parse("1.2"))
        assertEquals("1.2", SdkVersion(1, 2).toString())
    }

    @Test
    fun `rejects malformed versions`() {
        listOf("", "1", "1.2.3", "01.2", "a.b", "-1.0").forEach { text ->
            assertThrows<IllegalArgumentException> { SdkVersion.parse(text) }
        }
    }

    @Test
    fun `same major and lower or equal minor is supported`() {
        val runtime = SdkVersion(1, 5)
        assertTrue(SdkVersion(1, 0).isSupportedBy(runtime))
        assertTrue(SdkVersion(1, 5).isSupportedBy(runtime))
        assertFalse(SdkVersion(1, 6).isSupportedBy(runtime))
        assertFalse(SdkVersion(2, 0).isSupportedBy(runtime))
        assertFalse(SdkVersion(0, 9).isSupportedBy(runtime))
    }
}
