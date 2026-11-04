package wasichai.integrations

import org.springframework.test.context.TestPropertySource
import wasichai.test.WasichaiIntegrationTest

// one encryption key for every integration test of the module
@TestPropertySource(
    properties = [
        "wasichai.integrations.encryption.keys.it1=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        "wasichai.integrations.encryption.active-key=it1",
        "wasichai.integrations.public-base-url=https://app.example.test",
        "wasichai.integrations.oauth.return-url=https://ui.example.test/integrations/connections",
        // the stub provider listens on 127.0.0.1
        "wasichai.integrations.http.allow-private-addresses=true"
    ]
)
abstract class IntegrationsIntegrationTest : WasichaiIntegrationTest()
