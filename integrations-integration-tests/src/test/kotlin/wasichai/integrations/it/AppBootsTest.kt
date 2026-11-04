package wasichai.integrations.it

import org.junit.jupiter.api.Test
import wasichai.test.WasichaiIntegrationTest

// the floor every suite stands on: wasichai from the packages boots on postgres 18 and signs in
class AppBootsTest : WasichaiIntegrationTest() {
    @Test
    fun `health answers and the seeded admin signs in`() {
        client.get().uri("/api/health").exchange().expectStatus().isOk
        client
            .get()
            .uri("/api/auth/me")
            .header("Authorization", bearer())
            .exchange()
            .expectStatus()
            .isOk
    }
}
