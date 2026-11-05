package wasichai.integrations.ai

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean

// core, integrations and the AI tools module through auto-configuration, with in-memory providers
@SpringBootConfiguration
@EnableAutoConfiguration
class AiTestApplication {
    @Bean
    fun memRecords(): MemRecords = MemRecords("mem-r")

    @Bean
    fun memMessenger(): MemMessenger = MemMessenger("mem-m")
}
