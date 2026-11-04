package wasichai.integrations

import org.springframework.test.context.TestPropertySource
import wasichai.test.WasichaiIntegrationTest

// one encryption key for every integration test of the module
@TestPropertySource(
    properties = [
        "wasichai.integrations.encryption.keys.it1=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
        "wasichai.integrations.encryption.active-key=it1"
    ]
)
abstract class IntegrationsIntegrationTest : WasichaiIntegrationTest()
