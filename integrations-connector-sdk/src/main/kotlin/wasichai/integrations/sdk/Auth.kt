package wasichai.integrations.sdk

// how a connection proves who it is to the provider. the platform runs the flows and stores the secrets;
// a connector only declares which methods it accepts.
sealed interface AuthMethod {
    val type: AuthType

    data class OAuth2AuthorizationCode(
        val authorizeUrl: String,
        val tokenUrl: String,
        val defaultScopes: List<String> = emptyList(),
        val pkce: Boolean = true,
        // client credentials in the token request body or as http basic
        val clientAuthentication: ClientAuthentication = ClientAuthentication.BODY,
        val revokeUrl: String? = null,
        val scopeSeparator: String = " "
    ) : AuthMethod {
        override val type = AuthType.OAUTH2_AUTHORIZATION_CODE
    }

    data class OAuth2ClientCredentials(
        val tokenUrl: String,
        val defaultScopes: List<String> = emptyList(),
        val clientAuthentication: ClientAuthentication = ClientAuthentication.BODY
    ) : AuthMethod {
        override val type = AuthType.OAUTH2_CLIENT_CREDENTIALS
    }

    data class ApiKey(
        val headerName: String = "Authorization",
        val prefix: String? = "Bearer "
    ) : AuthMethod {
        override val type = AuthType.API_KEY
    }

    data object Basic : AuthMethod {
        override val type = AuthType.BASIC
    }
}

enum class AuthType {
    OAUTH2_AUTHORIZATION_CODE,
    OAUTH2_CLIENT_CREDENTIALS,
    API_KEY,
    BASIC
}

enum class ClientAuthentication {
    BODY,
    BASIC
}

// what the provider account behind a connection is. providerAccountId is the stable identity, never a display name.
data class AccountInfo(
    val providerAccountId: String,
    val displayName: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

// the token response as the provider sent it, for connectors that keep extra fields (an instance url)
data class TokenGrant(
    val accessToken: String,
    val refreshToken: String?,
    val expiresInSeconds: Long?,
    val scopes: List<String>,
    val raw: Map<String, Any?>
)
