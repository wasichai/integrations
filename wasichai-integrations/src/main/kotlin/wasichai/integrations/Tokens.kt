package wasichai.integrations

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import wasichai.integrations.sdk.ClientAuthentication
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.HttpErrors
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.TokenGrant
import java.util.Base64

// the provider refused a grant (invalid_grant and friends): a person must authorize again
class TokenRefusedException(
    val error: String
) : RuntimeException("token request refused: $error")

data class OAuthClient(
    val clientId: String,
    val clientSecret: String?,
    val authentication: ClientAuthentication
)

// rfc 6749 token requests over the platform's http. responses are parsed, never logged
class TokenClient(
    private val http: PlatformHttp,
    private val json: JsonMapper
) {
    suspend fun exchangeCode(
        tokenUrl: String,
        client: OAuthClient,
        code: String,
        redirectUri: String,
        verifier: String?,
        separator: String
    ): TokenGrant =
        request(
            tokenUrl,
            client,
            mapOf("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirectUri, "code_verifier" to verifier),
            separator
        )

    suspend fun refresh(
        tokenUrl: String,
        client: OAuthClient,
        refreshToken: String,
        separator: String
    ): TokenGrant = request(tokenUrl, client, mapOf("grant_type" to "refresh_token", "refresh_token" to refreshToken), separator)

    suspend fun clientCredentials(
        tokenUrl: String,
        client: OAuthClient,
        scopes: List<String>,
        separator: String
    ): TokenGrant =
        request(
            tokenUrl,
            client,
            mapOf("grant_type" to "client_credentials", "scope" to scopes.joinToString(separator).ifBlank { null }),
            separator
        )

    // rfc 7009, best effort: a disconnect goes ahead whatever the provider answers
    suspend fun revoke(
        revokeUrl: String,
        client: OAuthClient,
        token: String
    ) {
        runCatching { http.send(formRequest(revokeUrl, client, mapOf("token" to token))) }
    }

    private suspend fun request(
        tokenUrl: String,
        client: OAuthClient,
        params: Map<String, String?>,
        separator: String
    ): TokenGrant {
        val response = http.send(formRequest(tokenUrl, client, params))
        val body = runCatching { json.readTree(response.body) }.getOrNull()
        if (!response.successful) {
            val error = body?.path("error")?.let { if (it.isMissingNode || it.isNull) null else it.asString() }
            if (response.status in 400..401 && error != null) throw TokenRefusedException(error)
            throw HttpErrors.classify(response)
        }
        val access = body?.path("access_token")?.takeUnless { it.isMissingNode || it.isNull }?.asString()
        if (body == null || access.isNullOrBlank()) throw ConnectorException.Permanent("token response without access_token")
        return TokenGrant(
            accessToken = access,
            refreshToken = body.path("refresh_token").takeUnless { it.isMissingNode || it.isNull }?.asString(),
            expiresInSeconds =
                body
                    .path("expires_in")
                    .takeIf { it.isNumber || it.isString }
                    ?.asString()
                    ?.toLongOrNull(),
            scopes =
                body
                    .path("scope")
                    .takeUnless { it.isMissingNode || it.isNull }
                    ?.asString()
                    ?.split(separator, " ", ",")
                    ?.filter { it.isNotBlank() }
                    .orEmpty(),
            raw = flat(body)
        )
    }

    private fun formRequest(
        url: String,
        client: OAuthClient,
        params: Map<String, String?>
    ): ProviderRequest {
        val headers = mutableMapOf("Accept" to "application/json")
        val form = params.toMutableMap()
        when (client.authentication) {
            ClientAuthentication.BASIC -> {
                val raw = "${PlatformHttp.encode(client.clientId)}:${PlatformHttp.encode(client.clientSecret.orEmpty())}"
                headers["Authorization"] = "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray())
            }
            ClientAuthentication.BODY -> {
                form["client_id"] = client.clientId
                form["client_secret"] = client.clientSecret
            }
        }
        return ProviderRequest(HttpMethod.POST, url, headers, body = PlatformHttp.form(form), contentType = "application/x-www-form-urlencoded")
    }

    // top-level scalar fields only: what a connector may keep as metadata (an instance url)
    private fun flat(node: JsonNode): Map<String, Any?> =
        node
            .properties()
            .filter { (_, v) -> v.isValueNode }
            .associate { (k, v) -> k to if (v.isNull) null else v.asString() }
}
