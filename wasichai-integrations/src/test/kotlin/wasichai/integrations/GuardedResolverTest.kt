package wasichai.integrations

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import wasichai.integrations.autoconfigure.IntegrationsProperties
import wasichai.integrations.sdk.ConnectorException
import wasichai.integrations.sdk.HttpMethod
import wasichai.integrations.sdk.ProviderRequest
import wasichai.integrations.sdk.testing.StubProviderServer
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GuardedResolverTest {
    private val stub = StubProviderServer().also { it.onJson(HttpMethod.GET, "/ok", """{"ok":true}""") }
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val public = InetAddress.getByAddress("public.example", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))

    @AfterAll
    fun stop() = stub.close()

    private fun port() = stub.baseUrl.substringAfterLast(':')

    @Test
    fun `a name with any private address is refused`() {
        assertThrows<BlockedAddressException> { GuardedNameResolver.checked("mixed.example", listOf(public, loopback), allowPrivate = false) }
        assertThrows<BlockedAddressException> {
            GuardedNameResolver.checked("ula.example", listOf(InetAddress.getByName("fd00::1")), allowPrivate = false)
        }
        assertEquals(listOf(public), GuardedNameResolver.checked("public.example", listOf(public), allowPrivate = false))
        assertEquals(listOf(loopback), GuardedNameResolver.checked("lab.example", listOf(loopback), allowPrivate = true))
    }

    @Test
    fun `a name that rebinds to a private address after a first public answer never gets a connection`() {
        val lookups = AtomicInteger()
        // first answer public (what an earlier check would have seen), then the rebinding answer
        val rebinding: (String) -> List<InetAddress> = { if (lookups.getAndIncrement() == 0) listOf(public) else listOf(loopback) }
        val http = PlatformHttp(IntegrationsProperties.Http(), GuardedResolverGroup(allowPrivate = false, lookup = rebinding))
        rebinding("warm-up.example")
        val error =
            assertThrows<ConnectorException.PermissionDenied> {
                runBlocking { http.send(ProviderRequest(HttpMethod.GET, "http://rebind.example:${port()}/ok")) }
            }
        assertEquals("host rebind.example resolves to a private address", error.message)
    }

    @Test
    fun `the connection goes to the address the resolver checked`() {
        // the name does not exist anywhere: only the guarded resolver knows it, so a 200 proves it was used to connect
        val http = PlatformHttp(IntegrationsProperties.Http(allowPrivateAddresses = true), GuardedResolverGroup(true) { listOf(loopback) })
        val response = runBlocking { http.send(ProviderRequest(HttpMethod.GET, "http://only-in-the-resolver.invalid:${port()}/ok")) }
        assertEquals(200, response.status)
    }
}
