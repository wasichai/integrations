package wasichai.integrations.connector.openapi

import tools.jackson.databind.JsonNode
import wasichai.integrations.sdk.AuthMethod
import wasichai.integrations.sdk.FieldType
import wasichai.integrations.sdk.ResourceSchema

// the config checked against its document once, at bean creation: every problem is listed in one message,
// so a wrong operationId or parameter fails the application start instead of a sync at night
internal class OpenApiMapping private constructor(
    val config: OpenApiConfig,
    val document: OpenApiDocument,
    val baseUrl: String,
    val authMethod: AuthMethod,
    val resources: Map<String, Resource>
) {
    class Resource(
        val config: OpenApiResource,
        val list: OpenApiDocument.Operation,
        val get: OpenApiDocument.Operation,
        val create: OpenApiDocument.Operation?,
        val update: OpenApiDocument.Operation?,
        val delete: OpenApiDocument.Operation?,
        val schema: ResourceSchema
    ) {
        val name: String get() = config.name
        val incremental: Boolean get() = config.updatedSinceParam != null
        val writable: Boolean get() = create != null || update != null || delete != null
    }

    companion object {
        fun of(config: OpenApiConfig): OpenApiMapping {
            val problems = mutableListOf<String>()
            val document =
                runCatching { OpenApiDocument.parse(config.document) }
                    .getOrElse { throw invalid(config, listOf(it.message ?: "unreadable document")) }
            val baseUrl = baseUrl(config, document, problems)
            val auth =
                runCatching { authMethod(config, document) }.getOrElse {
                    problems += it.message ?: "bad security scheme"
                    null
                }
            if (config.resources.isEmpty()) problems += "no resource is mapped"
            config.resources
                .groupingBy { it.name }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .forEach { problems += "resource '$it' is mapped twice" }
            val resources =
                config.resources.mapNotNull { resource ->
                    runCatching { resource(resource, document, problems) }
                        .getOrElse {
                            problems += "resource '${resource.name}': ${it.message}"
                            null
                        }
                }
            if (problems.isNotEmpty() || auth == null || baseUrl == null) throw invalid(config, problems)
            return OpenApiMapping(config, document, baseUrl, auth, resources.associateBy { it.name })
        }

        private fun invalid(
            config: OpenApiConfig,
            problems: List<String>
        ) = IllegalArgumentException("openapi connector '${config.id}' is misconfigured: ${problems.joinToString("; ")}")

        // loopback http only for a stub provider in tests, as the other connectors allow
        fun isAllowedUrl(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://127.0.0.1")

        private fun baseUrl(
            config: OpenApiConfig,
            document: OpenApiDocument,
            problems: MutableList<String>
        ): String? {
            val url =
                config.baseUrl ?: document.root
                    .path("servers")
                    .path(0)
                    .path("url")
                    .asString(null)
            when {
                url == null -> problems += "no baseUrl and no servers[0].url in the document"
                '{' in url -> problems += "servers[0].url '$url' has variables: set baseUrl"
                !isAllowedUrl(url) -> problems += "the base url '$url' must be absolute https"
                else -> return url.trimEnd('/')
            }
            return null
        }

        // the scheme as the platform runs it: api keys go in a header (the platform has no query or cookie key)
        private fun authMethod(
            config: OpenApiConfig,
            document: OpenApiDocument
        ): AuthMethod {
            val name = config.securityScheme
            val raw =
                document.root
                    .path("components")
                    .path("securitySchemes")
                    .path(name)
            if (raw.isMissingNode) throw IllegalArgumentException("security scheme '$name' is not in components.securitySchemes")
            val scheme = document.deref(raw)
            return when (val type = scheme.path("type").asString("")) {
                "apiKey" -> {
                    val location = scheme.path("in").asString("")
                    if (location !=
                        "header"
                    ) {
                        throw IllegalArgumentException("security scheme '$name': api keys in $location are not supported, only in a header")
                    }
                    AuthMethod.ApiKey(headerName = scheme.path("name").asString(), prefix = null)
                }
                "http" -> {
                    val http = scheme.path("scheme").asString("")
                    if (!http.equals(
                            "bearer",
                            ignoreCase = true
                        )
                    ) {
                        throw IllegalArgumentException("security scheme '$name': http $http is not supported, only bearer")
                    }
                    AuthMethod.ApiKey(headerName = "Authorization", prefix = "Bearer ")
                }
                "oauth2" -> {
                    val flow = scheme.path("flows").path("authorizationCode")
                    if (flow.isMissingNode) throw IllegalArgumentException("security scheme '$name': only the oauth2 authorizationCode flow is supported")
                    val authorize = flow.path("authorizationUrl").asString("")
                    val token = flow.path("tokenUrl").asString("")
                    listOf(authorize, token).forEach {
                        if (!isAllowedUrl(it)) throw IllegalArgumentException("security scheme '$name': oauth urls must be absolute https, not '$it'")
                    }
                    AuthMethod.OAuth2AuthorizationCode(
                        authorizeUrl = authorize,
                        tokenUrl = token,
                        defaultScopes = config.scopes ?: flow.path("scopes").propertyNames().toList(),
                        pkce = config.pkce
                    )
                }
                else -> throw IllegalArgumentException("security scheme '$name': type '$type' is not supported")
            }
        }

        private fun resource(
            config: OpenApiResource,
            document: OpenApiDocument,
            problems: MutableList<String>
        ): Resource? {
            val before = problems.size
            val at = "resource '${config.name}'"

            fun operation(
                role: String,
                id: String?,
                pathParameters: Int
            ): OpenApiDocument.Operation? {
                if (id == null) return null
                val operation = document.operations[id]
                if (operation == null) {
                    problems += "$at: $role operation '$id' is not in the document"
                    return null
                }
                val count = operation.pathParameters().size
                if (count != pathParameters) problems += "$at: $role operation '$id' must take $pathParameters path parameter(s), it takes $count"
                return operation
            }
            val list = operation("list", config.list, 0)
            val get = operation("get", config.get, 1)
            val create = operation("create", config.create, 0)
            val update = operation("update", config.update, 1)
            val delete = operation("delete", config.delete, 1)
            listOfNotNull(create, update).forEach {
                if (document.requestSchema(it) == null) problems += "$at: '${it.id}' takes no json request body"
            }
            if (list != null) checkList(config, list, problems)
            if (config.updatedSinceParam != null && config.updatedAtField == null) problems += "$at: updatedSinceParam needs updatedAtField"
            val schema = if (list != null && get != null) schema(config, document, list, get, create, problems) else null
            if (schema != null) {
                if (schema.field(config.idField) == null) problems += "$at: id field '${config.idField}' is not in the record schema"
                config.updatedAtField?.let { field ->
                    if (schema.field(field)?.type != FieldType.DATETIME) problems += "$at: updatedAtField '$field' is not a date-time field of the record"
                }
            }
            if (problems.size > before || list == null || get == null || schema == null) return null
            return Resource(config, list, get, create, update, delete, schema)
        }

        // every query parameter the mapping sends is declared, every required one is sent
        private fun checkList(
            config: OpenApiResource,
            list: OpenApiDocument.Operation,
            problems: MutableList<String>
        ) {
            val sent =
                when (val pagination = config.pagination) {
                    OpenApiPagination.None -> listOf()
                    is OpenApiPagination.Page -> listOfNotNull(pagination.pageParam, pagination.limitParam)
                    is OpenApiPagination.Cursor -> listOfNotNull(pagination.cursorParam, pagination.limitParam)
                    is OpenApiPagination.NextUrl -> listOfNotNull(pagination.limitParam)
                } + listOfNotNull(config.updatedSinceParam)
            sent.filter { list.query(it) == null }.forEach {
                problems += "resource '${config.name}': list operation '${list.id}' has no query parameter '$it'"
            }
            list.parameters.filter { it.required && it.name !in sent }.forEach {
                problems +=
                    "resource '${config.name}': list operation '${list.id}' requires ${it.location} parameter '${it.name}', which the mapping does not send"
            }
        }

        // the get response's object schema, or else the list's item schema; required comes from the create body
        private fun schema(
            config: OpenApiResource,
            document: OpenApiDocument,
            list: OpenApiDocument.Operation,
            get: OpenApiDocument.Operation,
            create: OpenApiDocument.Operation?,
            problems: MutableList<String>
        ): ResourceSchema? {
            val record = document.responseSchema(get)?.let(document::shape) ?: listItem(config, document, list)?.let(document::shape)
            if (record == null || record.properties.isEmpty()) {
                problems += "resource '${config.name}': neither '${get.id}' nor '${list.id}' answers an object schema with properties"
                return null
            }
            val requiredOnCreate =
                create
                    ?.let(document::requestSchema)
                    ?.let(document::shape)
                    ?.required
                    .orEmpty()
            val fields =
                record.properties.map { (name, property) ->
                    document.field(name, property, required = name in requiredOnCreate, readOnly = name == config.idField)
                }
            return ResourceSchema(config.name, config.label, fields)
        }

        private fun listItem(
            config: OpenApiResource,
            document: OpenApiDocument,
            list: OpenApiDocument.Operation
        ): JsonNode? {
            var schema = document.responseSchema(list) ?: return null
            config.itemsField?.split('.')?.forEach { part ->
                schema = document.deref(document.shape(schema)?.properties?.get(part) ?: return null)
            }
            return schema.path("items").takeIf { schema.path("type").asString("") == "array" && it.isObject }
        }
    }
}
