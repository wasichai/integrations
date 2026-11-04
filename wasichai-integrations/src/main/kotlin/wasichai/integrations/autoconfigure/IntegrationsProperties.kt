package wasichai.integrations.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// wasichai.integrations.*. the encryption keys have no default, like the jwt secret: a module that stores
// credentials refuses to start without one.
@ConfigurationProperties("wasichai.integrations")
data class IntegrationsProperties(
    val enabled: Boolean = true,
    // how providers reach this app (the oauth callback, webhooks): https://app.example.com. no default
    val publicBaseUrl: String? = null,
    val encryption: Encryption = Encryption(),
    val oauth: OAuth = OAuth(),
    val http: Http = Http()
) {
    data class Encryption(
        // key id -> base64 of 32 random bytes (AES-256). old ids stay listed while rows still use them
        val keys: Map<String, String> = emptyMap(),
        // the id new secrets are sealed with
        val activeKey: String? = null
    )

    data class OAuth(
        // where the browser lands after the provider: the ui's connections page. configured, never from a request
        val returnUrl: String = "/",
        val stateTtl: Duration = Duration.ofMinutes(10),
        // refresh a token this long before it expires
        val refreshSkew: Duration = Duration.ofSeconds(60)
    )

    data class Http(
        // provider calls to private, loopback or link-local addresses are refused unless this is on (tests, labs)
        val allowPrivateAddresses: Boolean = false,
        val timeout: Duration = Duration.ofSeconds(30),
        val maxResponseBytes: Int = 16 * 1024 * 1024
    )

    init {
        publicBaseUrl?.let { require(it.startsWith("https://") || it.startsWith("http://")) { "public-base-url must be an http(s) url" } }
        require(oauth.stateTtl.isPositive) { "oauth.state-ttl must be positive" }
    }
}
