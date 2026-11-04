package wasichai.integrations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import java.util.UUID

class CredentialCipherTest {
    private val k1 = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val k2 = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
    private val org = UUID.randomUUID()
    private val row = UUID.randomUUID()
    private val context = SealContext(org, "t", row, "c")

    private fun cipher(
        active: String,
        vararg keys: Pair<String, String>
    ) = CredentialCipher(ConfiguredCredentialKeys(mapOf(*keys), active))

    @Test
    fun `seals and opens, and the sealed form holds no plaintext`() {
        val cipher = cipher("k1", "k1" to k1)
        val sealed = cipher.seal("s3cr3t-value", context)
        assertFalse("s3cr3t-value" in sealed)
        assertTrue(sealed.startsWith("v1.k1."))
        assertEquals("s3cr3t-value", cipher.open(sealed, context))
    }

    @Test
    fun `the same secret seals differently every time`() {
        val cipher = cipher("k1", "k1" to k1)
        assertNotEquals(cipher.seal("x", context), cipher.seal("x", context))
    }

    @Test
    fun `a sealed value does not open for another tenant, row or column`() {
        val cipher = cipher("k1", "k1" to k1)
        val sealed = cipher.seal("x", context)
        listOf(
            context.copy(organizationId = UUID.randomUUID()),
            context.copy(rowId = UUID.randomUUID()),
            context.copy(column = "other"),
            context.copy(table = "other")
        ).forEach { other -> assertThrows<CredentialUnreadableException> { cipher.open(sealed, other) } }
    }

    @Test
    fun `tampering is detected`() {
        val cipher = cipher("k1", "k1" to k1)
        val sealed = cipher.seal("x", context)
        val last = sealed.last()
        val tampered = sealed.dropLast(1) + (if (last == 'A') 'B' else 'A')
        assertThrows<CredentialUnreadableException> { cipher.open(tampered, context) }
        assertThrows<CredentialUnreadableException> { cipher.open("plain", context) }
    }

    @Test
    fun `rotation - old keys still open, new seals use the active key`() {
        val old = cipher("k1", "k1" to k1).seal("x", context)
        val rotated = cipher("k2", "k1" to k1, "k2" to k2)
        assertEquals("x", rotated.open(old, context))
        assertTrue(rotated.needsRotation(old))
        val fresh = rotated.seal("x", context)
        assertTrue(fresh.startsWith("v1.k2."))
        assertFalse(rotated.needsRotation(fresh))
    }

    @Test
    fun `a retired key that is no longer configured cannot open`() {
        val old = cipher("k1", "k1" to k1).seal("x", context)
        assertThrows<CredentialUnreadableException> { cipher("k2", "k2" to k2).open(old, context) }
    }

    @Test
    fun `key configuration is validated`() {
        assertThrows<IllegalArgumentException> { ConfiguredCredentialKeys(emptyMap(), null) }
        assertThrows<IllegalArgumentException> { ConfiguredCredentialKeys(mapOf("k1" to "c2hvcnQ="), "k1") }
        assertThrows<IllegalArgumentException> { ConfiguredCredentialKeys(mapOf("k1" to k1), "k9") }
        assertThrows<IllegalArgumentException> { ConfiguredCredentialKeys(mapOf("bad id" to k1), "bad id") }
    }
}
