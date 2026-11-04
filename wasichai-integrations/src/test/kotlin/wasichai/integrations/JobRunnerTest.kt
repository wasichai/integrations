package wasichai.integrations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import wasichai.integrations.sdk.ConnectorException
import java.time.Duration

class JobRunnerTest {
    @Test
    fun `failures are classified for retry`() {
        assertEquals(Triple(true, "rate_limited", Duration.ofSeconds(9)), JobRunner.classify(ConnectorException.RateLimited("x", Duration.ofSeconds(9))))
        assertEquals(Triple(true, "transient", null), JobRunner.classify(ConnectorException.Transient("x")))
        assertEquals(Triple(false, "permanent", null), JobRunner.classify(ConnectorException.Permanent("x")))
        assertEquals(Triple(false, "auth_expired", null), JobRunner.classify(ConnectorException.AuthExpired("x")))
        assertEquals(Triple(false, "permanent", null), JobRunner.classify(PermanentJobFailure("x")))
        assertEquals(Triple(true, "unexpected", null), JobRunner.classify(IllegalStateException("x")))
    }
}
