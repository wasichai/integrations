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
    val http: Http = Http(),
    val jobs: Jobs = Jobs(),
    val webhooks: Webhooks = Webhooks(),
    val retention: Retention = Retention(),
    val usage: Usage = Usage(),
    val export: Export = Export()
) {
    // ADR-0029: purge of finished history. each module's task has a name and a default age; max-age overrides it by
    // name (events, jobs, usage, sync.runs, sync.conflicts, workflows.executions, ai.executions), 0 keeps forever
    data class Retention(
        // the scheduler; off, nothing is purged automatically
        val enabled: Boolean = true,
        val interval: Duration = Duration.ofHours(1),
        val initialDelay: Duration = Duration.ofMinutes(5),
        // rows per DELETE: short statements, short locks
        val batchSize: Int = 1000,
        // batches per task and tick; the next tick goes on
        val maxBatches: Int = 100,
        val maxAge: Map<String, Duration> = emptyMap()
    ) {
        init {
            require(batchSize in 1..100_000) { "retention.batch-size must be within 1..100000" }
            require(maxBatches >= 1) { "retention.max-batches must be at least 1" }
            require(interval.isPositive) { "retention.interval must be positive" }
            require(!initialDelay.isNegative) { "retention.initial-delay must not be negative" }
        }
    }

    data class Usage(
        // how long counts wait in memory before they are written; a crash loses at most this much
        val flushInterval: Duration = Duration.ofSeconds(30)
    ) {
        init {
            require(flushInterval.isPositive) { "usage.flush-interval must be positive" }
        }
    }

    data class Export(
        // rows per section in one page of a connection's export
        val pageSize: Int = 200,
        val maxPageSize: Int = 1000
    ) {
        init {
            require(pageSize in 1..maxPageSize) { "export.page-size must be within 1..export.max-page-size" }
        }
    }

    data class Webhooks(
        // a bigger delivery is refused before any parsing (413)
        val maxBodyBytes: Int = 1024 * 1024
    )

    data class Jobs(
        // off on a replica that must only serve the api
        val workerEnabled: Boolean = true,
        val pollInterval: Duration = Duration.ofSeconds(1),
        val batch: Int = 20,
        // a job still RUNNING after this is taken to have lost its worker
        val lease: Duration = Duration.ofMinutes(5),
        val backoff: Duration = Duration.ofSeconds(30),
        val maxBackoff: Duration = Duration.ofHours(1)
    )

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
        val maxResponseBytes: Int = 16 * 1024 * 1024,
        val rateLimit: RateLimit = RateLimit()
    )

    data class RateLimit(
        // one bucket per connection (and per ai tool) in postgresql for every replica, and provider pauses they all
        // honour (ADR-0030). off: each replica limits on its own, n replicas may reach n times the rate
        val shared: Boolean = true
    )

    init {
        publicBaseUrl?.let { require(it.startsWith("https://") || it.startsWith("http://")) { "public-base-url must be an http(s) url" } }
        require(oauth.stateTtl.isPositive) { "oauth.state-ttl must be positive" }
    }
}
