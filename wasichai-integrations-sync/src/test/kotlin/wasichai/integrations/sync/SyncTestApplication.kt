package wasichai.integrations.sync

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Bean

// core, integrations and sync through auto-configuration, with two in-memory providers (Connector beans)
@SpringBootConfiguration
@EnableAutoConfiguration
class SyncTestApplication {
    @Bean
    fun memA(): MemConnector = MemConnector("mem-a")

    @Bean
    fun memB(): MemConnector = MemConnector("mem-b")

    @Bean
    fun memU(): MemUpserter = MemUpserter("mem-u")
}
