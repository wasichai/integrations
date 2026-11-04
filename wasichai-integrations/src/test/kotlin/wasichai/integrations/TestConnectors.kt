package wasichai.integrations

import wasichai.integrations.sdk.AccountInfo
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.Authenticator
import wasichai.integrations.sdk.Capability
import wasichai.integrations.sdk.Connector
import wasichai.integrations.sdk.ConnectorContext
import wasichai.integrations.sdk.ConnectorManifest
import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.ProviderInfo
import wasichai.integrations.sdk.SdkVersion

// connectors that exist only for the platform's own tests: no provider behind them
object TestConnectors {
    fun oauth(id: String = "test-oauth"): Connector =
        object : Connector, Authenticator {
            override val manifest =
                ConnectorManifest(
                    id = id,
                    provider = ProviderInfo("Test OAuth"),
                    version = "1.0.0",
                    sdkVersion = SdkVersion.CURRENT,
                    description = "oauth test connector",
                    authMethods = listOf(AuthMethod.OAuth2AuthorizationCode("https://auth.example.test/authorize", "https://auth.example.test/token")),
                    capabilities = mapOf(Capability.AUTHENTICATION to Maturity.TESTED)
                )

            override suspend fun accountInfo(context: ConnectorContext) = AccountInfo("acct-1")
        }

    fun apiKey(id: String = "test-key"): Connector =
        object : Connector, Authenticator {
            override val manifest =
                ConnectorManifest(
                    id = id,
                    provider = ProviderInfo("Test Key"),
                    version = "1.0.0",
                    sdkVersion = SdkVersion.CURRENT,
                    description = "api key test connector",
                    authMethods = listOf(AuthMethod.ApiKey()),
                    capabilities = mapOf(Capability.AUTHENTICATION to Maturity.TESTED)
                )

            override suspend fun accountInfo(context: ConnectorContext) = AccountInfo("acct-2")
        }
}
