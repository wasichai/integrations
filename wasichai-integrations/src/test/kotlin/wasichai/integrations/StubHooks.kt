package wasichai.integrations

import tools.jackson.databind.json.JsonMapper
import wasichai.integrations.sdk.AccountInfo
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Authenticator
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ConnectorManifest
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.ProviderEvent
import wasichai.integrations.sdk.ProviderInfo
import wasichai.integrations.sdk.SdkVersion
import wasichai.integrations.sdk.WebhookHandler
import wasichai.integrations.sdk.WebhookRequest
import wasichai.integrations.sdk.WebhookSupport
import wasichai.integrations.sdk.WebhookVerification
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// a webhook provider for tests: X-Stub-Signature = hex hmac-sha256(secret, body); {"challenge":..} is the handshake
class StubHooks :
    Connector,
    Authenticator,
    WebhookHandler {
    private val json = JsonMapper.builder().build()

    override val manifest =
        ConnectorManifest(
            id = "stub-hooks",
            provider = ProviderInfo("Stub hooks"),
            version = "1.0.0",
            sdkVersion = SdkVersion.CURRENT,
            description = "webhooks only",
            authMethods = listOf(AuthMethod.ApiKey()),
            capabilities = mapOf(Capability.AUTHENTICATION to Maturity.TESTED, Capability.WEBHOOKS to Maturity.TESTED),
            webhooks = WebhookSupport("hmac", listOf("record.updated"))
        )

    override suspend fun accountInfo(context: ConnectorContext) = AccountInfo("hooks-account")

    override fun verify(
        request: WebhookRequest,
        secret: String?
    ): WebhookVerification {
        val signature = request.header("X-Stub-Signature")
        if (signature == null) {
            val challenge = runCatching { json.readTree(request.body).path("challenge").asString() }.getOrNull()?.takeIf { it.isNotEmpty() }
            return if (challenge != null && secret == null) {
                WebhookVerification.Handshake(responseBody = challenge.toByteArray(), secretToStore = challenge)
            } else {
                WebhookVerification.Invalid("no signature")
            }
        }
        if (secret == null) return WebhookVerification.Invalid("no secret")
        return if (MessageDigest.isEqual(sign(secret, request.body).toByteArray(), signature.toByteArray())) {
            WebhookVerification.Valid
        } else {
            WebhookVerification.Invalid("bad signature")
        }
    }

    override fun parse(request: WebhookRequest): List<ProviderEvent> {
        val body = json.readTree(request.body)
        return listOf(ProviderEvent(body.path("id").asString().ifEmpty { null }, body.path("type").asString(), "Thing", body.path("record").asString(), null))
    }

    companion object {
        fun sign(
            secret: String,
            body: ByteArray
        ): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
            return HexFormat.of().formatHex(mac.doFinal(body))
        }
    }
}

// remembers what reached it; acts only on record.updated
class RecordingListener : IntegrationEventListener {
    val seen = CopyOnWriteArrayList<IntegrationEvent>()

    override suspend fun onEvent(event: IntegrationEvent): Boolean {
        seen += event
        return event.eventType == "record.updated"
    }
}
