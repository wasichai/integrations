package wasichai.integrations

import wasichai.integrations.sdk.AccountInfo
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Authenticator
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ConnectorManifest
import wasichai.integrations.sdk.HttpErrors
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.ProviderInfo
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.SdkVersion
import wasichai.integrations.sdk.TokenGrant
import wasichai.integrations.sdk.testing.StubProviderServer

// one provider on localhost for every integration test of the module: oauth endpoints and a /me
object StubProvider {
    val server = StubProviderServer()

    private fun manifest(
        id: String,
        method: AuthMethod
    ) = ConnectorManifest(
        id = id,
        provider = ProviderInfo("Stub"),
        version = "1.0.0",
        sdkVersion = SdkVersion.CURRENT,
        description = "stub provider",
        authMethods = listOf(method),
        capabilities = mapOf(Capability.AUTHENTICATION to Maturity.TESTED)
    )

    private open class Base(
        override val manifest: ConnectorManifest
    ) : Connector,
        Authenticator {
        override suspend fun accountInfo(context: ConnectorContext): AccountInfo {
            val response = context.http.exchange(ProviderRequest(HttpMethod.GET, "${server.baseUrl}/me"))
            if (!response.successful) throw HttpErrors.classify(response)
            val me = response.json()
            return AccountInfo(me.get("account").asString(), me.get("name").asString())
        }

        override fun connectionMetadata(grant: TokenGrant): Map<String, String> =
            grant.raw["instance_url"]?.let { mapOf("instance_url" to it.toString()) }.orEmpty()

        override fun authorizationParameters() = mapOf("prompt" to "consent")
    }

    fun oauth(): Connector =
        Base(
            manifest(
                "stub-oauth",
                AuthMethod.OAuth2AuthorizationCode(
                    authorizeUrl = "${server.baseUrl}/authorize",
                    tokenUrl = "${server.baseUrl}/token",
                    defaultScopes = listOf("read"),
                    revokeUrl = "${server.baseUrl}/revoke"
                )
            )
        )

    fun apiKey(): Connector = Base(manifest("stub-key", AuthMethod.ApiKey(headerName = "X-Api-Key", prefix = null)))
}
