package wasichai.integrations.connector.salesforce.pubsub

import java.time.Duration

// which objects read their changes through change data capture, and from which channel. an object without a
// channel keeps the polling feed: cdc needs an entitlement and channel membership set up in salesforce (ADR-0024)
data class SalesforcePubSubConfig(
    // object api name -> cdc channel: "Account" to "/data/AccountChangeEvent", or a custom channel "/data/Sync__chn"
    val channels: Map<String, String>,
    // host:port of the pub/sub api; tls always
    val endpoint: String = "api.pubsub.salesforce.com:7443",
    // events asked for per pull; salesforce accepts at most 100 per fetch request
    val batchSize: Int = 100,
    // how long one pull waits for events before it closes the stream and returns what it got
    val drain: Duration = Duration.ofSeconds(5)
) {
    init {
        require(channels.isNotEmpty()) { "channels must name at least one object; without one this is the polling connector" }
        channels.forEach { (resource, channel) ->
            require(OBJECT.matches(resource)) { "not a Salesforce object name: '$resource'" }
            require(CHANNEL.matches(channel)) { "a change data capture channel looks like /data/AccountChangeEvent, got '$channel'" }
        }
        require(ENDPOINT.matches(endpoint)) { "endpoint must be host:port" }
        require(batchSize in 1..100) { "batchSize must be within 1..100" }
        require(!drain.isNegative && !drain.isZero && drain <= Duration.ofMinutes(1)) { "drain must be within (0, 1 minute]" }
    }

    companion object {
        private val OBJECT = Regex("""^[A-Za-z](?:[A-Za-z0-9_]*[A-Za-z0-9])?$""")
        private val CHANNEL = Regex("""^/data/[A-Za-z][A-Za-z0-9_]*$""")
        private val ENDPOINT = Regex("""^[A-Za-z0-9.-]+:\d{1,5}$""")
    }
}
