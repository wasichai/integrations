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
        "wasichai.integrations.http.allow-private-addresses=true",
        // job tests drive the runner themselves: no loop racing them
        "wasichai.integrations.jobs.worker-enabled=false",
        "wasichai.integrations.jobs.backoff=1ms",
        "wasichai.integrations.jobs.max-backoff=1ms",
        // tests call the purge themselves
        "wasichai.integrations.retention.enabled=false"
    ]
)
abstract class IntegrationsIntegrationTest : WasichaiIntegrationTest()
