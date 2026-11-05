package wasichai.integrations.it

import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import wasichai.test.WasichaiIntegrationTest

// the floor every suite stands on: wasichai from the packages, with the integration modules, boots on postgres 18
// and signs in. the integrations module refuses to start without an encryption key
@TestPropertySource(
    properties = [
        "wasichai.integrations.encryption.keys.boot1=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        "wasichai.integrations.encryption.active-key=boot1",
        "wasichai.integrations.jobs.worker-enabled=false",
        "wasichai.integrations.sync.scheduler-enabled=false"
    ]
)
class AppBootsTest : WasichaiIntegrationTest() {
    @Test
    fun `health answers and the seeded admin signs in`() {
        client
            .get()
            .uri("/api/health")
            .exchange()
            .expectStatus()
            .isOk
        client
            .get()
            .uri("/api/auth/me")
            .header("Authorization", bearer())
            .exchange()
            .expectStatus()
            .isOk
        // every integrations module answers next to the others (workflows migrate after sync, AI tools after workflows)
        listOf(
            "/api/integrations/connectors",
            "/api/integrations/sync-definitions",
            "/api/integrations/workflows",
            "/api/integrations/ai/tools"
        ).forEach {
            client
                .get()
                .uri(it)
                .header("Authorization", bearer())
                .exchange()
                .expectStatus()
                .isOk
        }
    }
}
