package wasichai.integrations.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// wasichai plus every integrations module through auto-configuration; this app only adds the connector beans.
// configured by environment variables (ADR-0028); the role (api, worker) is a spring profile
@SpringBootApplication
class IntegrationsApplication

fun main(args: Array<String>) {
    runApplication<IntegrationsApplication>(*args)
}
