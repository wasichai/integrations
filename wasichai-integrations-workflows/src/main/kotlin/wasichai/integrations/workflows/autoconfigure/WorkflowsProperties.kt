package wasichai.integrations.workflows.autoconfigure

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("wasichai.integrations.workflows")
data class WorkflowsProperties(
    val enabled: Boolean = true,
    // attempts of one step on the job queue (with its backoff) before it fails and onError is taken
    val stepMaxAttempts: Int = 5,
    // starts scheduled runs; tests turn it off and call WorkflowScheduler.tick themselves
    val schedulerEnabled: Boolean = true,
    val schedulerTick: Duration = Duration.ofSeconds(15)
) {
    init {
        require(stepMaxAttempts in 1..20) { "wasichai.integrations.workflows.step-max-attempts must be within 1..20" }
        require(!schedulerTick.isNegative && !schedulerTick.isZero) { "wasichai.integrations.workflows.scheduler-tick must be positive" }
    }
}
