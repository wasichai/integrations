package wasichai.integrations.app

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.json.JsonMapper
import wasichai.test.WasichaiIntegrationTest

// the app the image runs boots on postgres with nothing but properties, answers its healthcheck and lists the
// shipped connectors: what the container smoke test checks in ci (.github/scripts/smoke.sh)
@TestPropertySource(
    properties = [
        "wasichai.integrations.encryption.keys.k1=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        "wasichai.integrations.encryption.active-key=k1",
        "wasichai.integrations.jobs.worker-enabled=false",
        "wasichai.integrations.sync.scheduler-enabled=false",
        "wasichai.integrations.workflows.scheduler-enabled=false"
    ]
)
class IntegrationsAppTest : WasichaiIntegrationTest() {
    @Test
    fun `health is public and up, the catalog lists the shipped connectors`() {
        client
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("UP")
        val body =
            client
                .get()
                .uri("/api/integrations/connectors")
                .header("Authorization", bearer())
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        val catalog = JsonMapper.builder().build().readTree(body)
        val ids = (0 until catalog.size()).map { catalog.get(it).get("id").asString() }
        assertThat(ids).containsExactlyInAnyOrder("notion", "salesforce", "jira", "github", "slack")
    }
}
