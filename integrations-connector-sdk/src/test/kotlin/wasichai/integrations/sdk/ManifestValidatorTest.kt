package wasichai.integrations.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ManifestValidatorTest {
    private fun manifest(
        capabilities: Map<Capability, Maturity> = mapOf(Capability.AUTHENTICATION to Maturity.TESTED),
        id: String = "sample",
        version: String = "1.0.0",
        sdk: SdkVersion = SdkVersion.CURRENT,
        resources: List<ResourceDeclaration> = emptyList(),
        webhooks: WebhookSupport? = null,
        actions: List<ActionDeclaration> = emptyList()
    ) = ConnectorManifest(
        id = id,
        provider = ProviderInfo("Sample"),
        version = version,
        sdkVersion = sdk,
        description = "test",
        authMethods = listOf(AuthMethod.ApiKey()),
        capabilities = capabilities,
        resources = resources,
        webhooks = webhooks,
        actions = actions
    )

    private open class AuthOnly(
        override val manifest: ConnectorManifest
    ) : Connector,
        Authenticator {
        override suspend fun accountInfo(context: ConnectorContext) = AccountInfo("acct")
    }

    @Test
    fun `a manifest that matches its interfaces is valid`() {
        assertEquals(emptyList<String>(), ManifestValidator.problems(AuthOnly(manifest())))
    }

    @Test
    fun `declaring a capability that is not implemented is rejected`() {
        val connector = AuthOnly(manifest(mapOf(Capability.AUTHENTICATION to Maturity.TESTED, Capability.READ to Maturity.IMPLEMENTED)))
        val error = assertThrows<InvalidManifestException> { ManifestValidator.validate(connector) }
        assertTrue(error.problems.any { "declares READ" in it }, error.problems.toString())
    }

    @Test
    fun `implementing a capability that is not declared is rejected`() {
        val connector = AuthOnly(manifest(capabilities = emptyMap()))
        assertTrue(ManifestValidator.problems(connector).any { "does not declare AUTHENTICATION" in it })
    }

    @Test
    fun `a capability only DECLARED is a plan, not a promise of code`() {
        val connector = AuthOnly(manifest(mapOf(Capability.AUTHENTICATION to Maturity.TESTED, Capability.READ to Maturity.DECLARED)))
        assertEquals(emptyList<String>(), ManifestValidator.problems(connector))
    }

    @Test
    fun `bad id, version and sdk are reported`() {
        val problems = ManifestValidator.problems(AuthOnly(manifest(id = "Bad_Id", version = "1.0", sdk = SdkVersion(2, 0))))
        assertTrue(problems.any { "id" in it })
        assertTrue(problems.any { "semantic" in it })
        assertTrue(problems.any { "built for sdk 2.0" in it })
    }

    @Test
    fun `duplicate resources and webhook mismatch are reported`() {
        val problems =
            ManifestValidator.problems(
                AuthOnly(
                    manifest(
                        resources = listOf(ResourceDeclaration("Account", "A"), ResourceDeclaration("Account", "B")),
                        webhooks = WebhookSupport("hmac", listOf("x"))
                    )
                )
            )
        assertTrue(problems.any { "declared twice" in it })
        assertTrue(problems.any { "WEBHOOKS" in it })
    }
}
