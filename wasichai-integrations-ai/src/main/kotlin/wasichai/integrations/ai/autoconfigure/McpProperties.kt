package wasichai.integrations.ai.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("wasichai.integrations.ai.mcp")
data class McpProperties(
    val enabled: Boolean = true,
    // browser origins (scheme://host[:port]) that may call the endpoint; any other Origin is 403. empty: none
    val allowedOrigins: List<String> = emptyList(),
    // tools per tools/list page
    val pageSize: Int = 100
) {
    init {
        require(pageSize in 1..1000) { "wasichai.integrations.ai.mcp.page-size must be within 1..1000" }
    }
}
