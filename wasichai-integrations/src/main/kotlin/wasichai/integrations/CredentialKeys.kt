package wasichai.integrations

import java.util.Base64
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

// where the keys that seal credentials come from. the default reads them from configuration (environment);
// an app on a kms or vault implements this and keeps key material out of the process config (ADR-0005).
interface CredentialKeyProvider {
    fun activeKeyId(): String

    // null when the id is unknown: a row sealed with a retired key that is no longer configured
    fun key(id: String): SecretKey?
}

class ConfiguredCredentialKeys(
    keys: Map<String, String>,
    private val activeKey: String?
) : CredentialKeyProvider {
    private val ring: Map<String, SecretKey>

    init {
        require(keys.isNotEmpty()) {
            "wasichai.integrations.encryption.keys is empty: configure at least one key (base64 of 32 random bytes)"
        }
        ring =
            keys.mapValues { (id, encoded) ->
                require(KEY_ID.matches(id)) { "encryption key id '$id' must match ${KEY_ID.pattern}" }
                val bytes = runCatching { Base64.getDecoder().decode(encoded.trim()) }.getOrNull()
                require(bytes != null && bytes.size == 32) { "encryption key '$id' must be base64 of exactly 32 bytes" }
                SecretKeySpec(bytes, "AES")
            }
        require(activeKey != null && activeKey in ring) { "wasichai.integrations.encryption.active-key must name one of the configured keys" }
    }

    override fun activeKeyId(): String = activeKey!!

    override fun key(id: String): SecretKey? = ring[id]

    companion object {
        val KEY_ID = Regex("^[A-Za-z0-9_-]{1,32}$")
    }
}
