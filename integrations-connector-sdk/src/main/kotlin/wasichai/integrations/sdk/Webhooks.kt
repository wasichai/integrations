package wasichai.integrations.sdk

import java.time.Instant

// an inbound delivery as received: the raw bytes are what signatures are computed over
class WebhookRequest(
    val headers: Map<String, List<String>>,
    val body: ByteArray
) {
    fun header(name: String): String? =
        headers.entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

sealed interface WebhookVerification {
    data object Valid : WebhookVerification

    data class Invalid(
        val reason: String
    ) : WebhookVerification

    // a subscription handshake: answer it, and keep the secret the provider handed over (if any)
    data class Handshake(
        val status: Int = 200,
        val responseBody: ByteArray? = null,
        val secretToStore: String? = null
    ) : WebhookVerification
}

// a provider event as the connector understood it. the platform wraps it in its own envelope.
data class ProviderEvent(
    // the provider's own id for the delivery, for de-duplication. null when the provider gives none
    val providerEventId: String?,
    val eventType: String,
    val resourceType: String?,
    val resourceId: String?,
    // null when the provider gives no trustworthy time
    val occurredAt: Instant?
)

interface WebhookHandler {
    // secret is the endpoint's stored verification secret, null before the handshake stored one
    fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification

    fun parse(request: WebhookRequest): List<ProviderEvent>
}
