package wasichai.integrations

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec

// AES-256-GCM with a random 96-bit nonce per seal. the associated data names the tenant, the table, the row and
// the column, so a sealed value copied into another row or tenant fails to open instead of leaking.
// sealed form: v1.<keyId>.<base64 nonce>.<base64 ciphertext+tag> (ADR-0005)
class CredentialCipher(
    private val keys: CredentialKeyProvider,
    private val random: SecureRandom = SecureRandom()
) {
    fun seal(
        plaintext: String,
        context: SealContext
    ): String {
        val keyId = keys.activeKeyId()
        val key = keys.key(keyId) ?: error("active key '$keyId' is not configured")
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(context.aad())
        val sealed = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return listOf(VERSION, keyId, b64.encodeToString(nonce), b64.encodeToString(sealed)).joinToString(".")
    }

    fun open(
        sealed: String,
        context: SealContext
    ): String {
        val parts = sealed.split(".")
        if (parts.size != 4 || parts[0] != VERSION) throw CredentialUnreadableException("not a sealed credential")
        val key = keys.key(parts[1]) ?: throw CredentialUnreadableException("sealed with key '${parts[1]}', which is not configured")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, b64d.decode(parts[2])))
            cipher.updateAAD(context.aad())
            String(cipher.doFinal(b64d.decode(parts[3])), StandardCharsets.UTF_8)
        } catch (_: AEADBadTagException) {
            // wrong key, wrong row or tampered: never say which
            throw CredentialUnreadableException("credential does not open in this context")
        } catch (e: IllegalArgumentException) {
            throw CredentialUnreadableException("malformed sealed credential: ${e.javaClass.simpleName}")
        }
    }

    // sealed with an older key: rewrite it on the next write path (rotation, ADR-0005)
    fun needsRotation(sealed: String): Boolean = sealed.split(".").getOrNull(1) != keys.activeKeyId()

    companion object {
        private const val VERSION = "v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val b64 = Base64.getUrlEncoder().withoutPadding()
        private val b64d = Base64.getUrlDecoder()
    }
}

// what a sealed value belongs to. part of the ciphertext's authentication, never stored with it.
data class SealContext(
    val organizationId: java.util.UUID,
    val table: String,
    val rowId: java.util.UUID,
    val column: String
) {
    fun aad(): ByteArray = "wasichai-integrations|$organizationId|$table|$rowId|$column".toByteArray(StandardCharsets.UTF_8)
}

class CredentialUnreadableException(
    message: String
) : IllegalStateException(message)
