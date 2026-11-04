package wasichai.integrations.sync.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("wasichai.integrations.sync")
data class SyncProperties(
    val enabled: Boolean = true,
    // records read per change-feed page
    val pageSize: Int = 100,
    // starts scheduled and reconcile runs; tests turn it off and call SyncScheduler.tick themselves
    val schedulerEnabled: Boolean = true,
    val schedulerTick: Duration = Duration.ofSeconds(30)
) {
    init {
        require(pageSize in 1..1000) { "wasichai.integrations.sync.page-size must be within 1..1000" }
        require(!schedulerTick.isNegative && !schedulerTick.isZero) { "wasichai.integrations.sync.scheduler-tick must be positive" }
    }
}
