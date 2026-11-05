package wasichai.integrations.ai

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import wasichai.core.platform.ModuleMigration
import wasichai.integrations.ConnectionUsage
import wasichai.integrations.ai.autoconfigure.WasichaiIntegrationsAiAutoConfiguration
import wasichai.integrations.ai.mcp.McpController
import wasichai.integrations.ai.mcp.McpServer
import wasichai.integrations.autoconfigure.WasichaiIntegrationsAutoConfiguration
import wasichai.test.WasichaiContextRunner
import java.util.Base64

class WasichaiIntegrationsAiAutoConfigurationTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })
    private val runner =
        WasichaiContextRunner
            .core()
            .withConfiguration(AutoConfigurations.of(WasichaiIntegrationsAutoConfiguration::class.java, WasichaiIntegrationsAiAutoConfiguration::class.java))
            .withPropertyValues("wasichai.integrations.encryption.keys.k1=$key", "wasichai.integrations.encryption.active-key=k1")

    @Test
    fun `migrates after sync and workflows and guards the connections its tools use`() {
        runner.run { context ->
            assertThat(context).hasNotFailed()
            val migration = context.getBean("wasichaiIntegrationsAiMigration", ModuleMigration::class.java)
            assertThat(migration.order).isEqualTo(WasichaiIntegrationsAutoConfiguration.ORDER + 30)
            assertThat(context).hasSingleBean(ToolController::class.java)
            assertThat(context).hasSingleBean(ToolExecutions::class.java)
            assertThat(context.getBeansOfType(ConnectionUsage::class.java)).containsKey("toolConnectionUsage")
            assertThat(context).hasSingleBean(McpController::class.java)
        }
    }

    @Test
    fun `switched off, no tool beans and so no routes`() {
        runner.withPropertyValues("wasichai.integrations.ai.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(ToolController::class.java)
            assertThat(context).doesNotHaveBean(ToolExecutions::class.java)
            assertThat(context).doesNotHaveBean("wasichaiIntegrationsAiMigration")
            assertThat(context).doesNotHaveBean(McpController::class.java)
        }
    }

    @Test
    fun `MCP switched off, the tool routes stay`() {
        runner.withPropertyValues("wasichai.integrations.ai.mcp.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(McpController::class.java)
            assertThat(context).doesNotHaveBean(McpServer::class.java)
            assertThat(context).hasSingleBean(ToolController::class.java)
        }
    }

    @Test
    fun `an MCP page size out of range fails the start`() {
        runner.withPropertyValues("wasichai.integrations.ai.mcp.page-size=0").run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `without the integrations module, nothing`() {
        runner.withPropertyValues("wasichai.integrations.enabled=false").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context).doesNotHaveBean(ToolController::class.java)
        }
    }
}
