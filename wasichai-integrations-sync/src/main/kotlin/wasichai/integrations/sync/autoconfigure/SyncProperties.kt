package wasichai.integrations.sync.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("wasichai.integrations.sync")
data class SyncProperties(
    val enabled: Boolean = true,
    // records read per change-feed page
    val pageSize: Int = 100
) {
    init {
        require(pageSize in 1..1000) { "wasichai.integrations.sync.page-size must be within 1..1000" }
    }
}
