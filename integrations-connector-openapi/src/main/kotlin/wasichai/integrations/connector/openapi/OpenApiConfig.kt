package wasichai.integrations.connector.openapi

import wasichai.integrations.sdk.Maturity
import wasichai.integrations.sdk.RateLimitHint

// one connector bean: an openapi 3 document plus the explicit mapping of its operations to resources.
// written by the app in code, never uploaded by a tenant (ADR-0021)
data class OpenApiConfig(
    // the connector id, stable forever: connections and sync definitions store it
    val id: String,
    // the openapi 3.x document, json or yaml
    val document: String,
    // the key in components.securitySchemes the connections use
    val securityScheme: String,
    val resources: List<OpenApiResource>,
    // null: the document's servers[0].url, which must then be absolute and without variables
    val baseUrl: String? = null,
    // null: the document's info.title
    val providerName: String? = null,
    // the connector's own semver, bumped by hand with the mapping
    val version: String = "0.1.0",
    // oauth2 only. null: every scope the authorization code flow lists
    val scopes: List<String>? = null,
    // oauth2 only: send pkce (S256). turn off only when the provider documents it does not take it
    val pkce: Boolean = true,
    val rateLimit: RateLimitHint? = null,
    // the label every capability the mapping provides gets. IMPLEMENTED until the app tests its own mapping
    // against a stub of its provider (ConnectorContractTest plus its own tests); then TESTED
    val maturity: Maturity = Maturity.IMPLEMENTED
)

// a resource is a pair of operations (list, get) plus the optional writes, by operationId
data class OpenApiResource(
    val name: String,
    val list: String,
    val get: String,
    val create: String? = null,
    val update: String? = null,
    val delete: String? = null,
    // the record id, a top-level field of the record. get, update and delete take it as their only path parameter
    val idField: String = "id",
    val label: String = name,
    // where the list response keeps its records, dotted ("data", "result.items"). null: the response is the array
    val itemsField: String? = null,
    val pagination: OpenApiPagination = OpenApiPagination.None,
    // a top-level date-time field: the record's version and modification time
    val updatedAtField: String? = null,
    // the list operation's query parameter that keeps records updated at or after an instant (iso-8601).
    // with updatedAtField it makes the change feed
    val updatedSinceParam: String? = null
)

// how a list operation pages. parameters are query parameters the list operation declares
sealed interface OpenApiPagination {
    // one response holds every record
    data object None : OpenApiPagination

    // page numbers. a page shorter than the limit (or empty, without a limit) is the last
    data class Page(
        val pageParam: String = "page",
        val limitParam: String? = "limit",
        val firstPage: Int = 1
    ) : OpenApiPagination

    // the response names the next cursor (dotted field); a missing, null or empty cursor ends the list
    data class Cursor(
        val cursorField: String,
        val cursorParam: String,
        val limitParam: String? = null
    ) : OpenApiPagination

    // the next page's url, from a dotted response field or, when nextField is null, from the rfc 8288 Link header.
    // followed only under the base url
    data class NextUrl(
        val nextField: String? = null,
        val limitParam: String? = null
    ) : OpenApiPagination
}
