package wasichai.integrations.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

// wasichai.integrations.*. the encryption keys have no default, like the jwt secret: a module that stores
// credentials refuses to start without one.
@ConfigurationProperties("wasichai.integrations")
data class IntegrationsProperties(
    val enabled: Boolean = true,
    val encryption: Encryption = Encryption()
) {
    data class Encryption(
        // key id -> base64 of 32 random bytes (AES-256). old ids stay listed while rows still use them
        val keys: Map<String, String> = emptyMap(),
        // the id new secrets are sealed with
        val activeKey: String? = null
    )
}
