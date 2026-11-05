package wasichai.integrations.workflows

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean

// core, integrations and workflows through auto-configuration, with in-memory providers (Connector beans)
@SpringBootConfiguration
@EnableAutoConfiguration
class WorkflowsTestApplication {
    @Bean
    fun memA(): MemConnector = MemConnector("mem-a")

    @Bean
    fun memM(): MemMessenger = MemMessenger("mem-m")
}
