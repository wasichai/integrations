package wasichai.integrations.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class HttpErrorsTest {
    private fun response(
        status: Int,
        headers: Map<String, List<String>> = emptyMap()
    ) = ProviderResponse(status, headers, ByteArray(0))

    @Test
    fun `statuses map to the error taxonomy`() {
        assertInstanceOf(ConnectorException.AuthExpired::class.java, HttpErrors.classify(response(401)))
        assertInstanceOf(ConnectorException.PermissionDenied::class.java, HttpErrors.classify(response(403)))
        assertInstanceOf(ConnectorException.NotFound::class.java, HttpErrors.classify(response(404)))
        assertInstanceOf(ConnectorException.Conflict::class.java, HttpErrors.classify(response(412)))
        assertInstanceOf(ConnectorException.RateLimited::class.java, HttpErrors.classify(response(429)))
        assertInstanceOf(ConnectorException.Transient::class.java, HttpErrors.classify(response(503)))
        assertInstanceOf(ConnectorException.Permanent::class.java, HttpErrors.classify(response(400)))
    }

    @Test
    fun `only transient kinds are retryable`() {
        assertTrue(HttpErrors.classify(response(503)).retryable)
        assertTrue(HttpErrors.classify(response(429)).retryable)
        assertTrue(HttpErrors.classify(response(401)).retryable)
        assertFalse(HttpErrors.classify(response(400)).retryable)
        assertFalse(HttpErrors.classify(response(404)).retryable)
        assertFalse(HttpErrors.classify(response(409)).retryable)
    }

    @Test
    fun `retry-after in seconds is read, any other form is unknown`() {
        val limited = HttpErrors.classify(response(429, mapOf("retry-after" to listOf("7")))) as ConnectorException.RateLimited
        assertEquals(Duration.ofSeconds(7), limited.retryAfter)
        assertNull(HttpErrors.retryAfter(response(429, mapOf("Retry-After" to listOf("Wed, 21 Oct 2015 07:28:00 GMT")))))
        assertNull(HttpErrors.retryAfter(response(429, mapOf("Retry-After" to listOf("-3")))))
    }
}
