package wasichai.integrations.sdk

// checks a connector before the platform registers it: the manifest must be well formed and must say
// exactly what the connector implements, no more and no less.
object ManifestValidator {
    private val ID = Regex("""^[a-z][a-z0-9-]{1,39}$""")
    private val SEMVER = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-[0-9A-Za-z.-]+)?$""")
    private val NAME = Regex("""^[A-Za-z][A-Za-z0-9_.-]{0,99}$""")

    // which interface stands for which capability
    private val IMPLEMENTATIONS: Map<Capability, Class<*>> =
        mapOf(
            Capability.AUTHENTICATION to Authenticator::class.java,
            Capability.DISCOVERY to ResourceDiscovery::class.java,
            Capability.READ to ResourceReader::class.java,
            Capability.CHANGE_FEED to ChangeFeed::class.java,
            Capability.WRITE to ResourceWriter::class.java,
            Capability.UPSERT to ResourceUpserter::class.java,
            Capability.WEBHOOKS to WebhookHandler::class.java,
            Capability.ACTIONS to ActionExecutor::class.java
        )

    fun problems(
        connector: Connector,
        runtime: SdkVersion = SdkVersion.CURRENT
    ): List<String> {
        val manifest = connector.manifest
        val problems = mutableListOf<String>()
        if (!ID.matches(manifest.id)) problems += "id '${manifest.id}' must match ${ID.pattern}"
        if (!SEMVER.matches(manifest.version)) problems += "version '${manifest.version}' is not semantic (x.y.z)"
        if (!manifest.sdkVersion.isSupportedBy(runtime)) {
            problems += "built for sdk ${manifest.sdkVersion}, the runtime is $runtime"
        }
        if (manifest.provider.name.isBlank()) problems += "provider name is blank"
        if (manifest.authMethods.isEmpty()) problems += "no authentication method"
        if (manifest.authMethods
                .map { it.type }
                .toSet()
                .size != manifest.authMethods.size
        ) {
            problems += "an auth type is listed twice"
        }
        IMPLEMENTATIONS.forEach { (capability, type) ->
            val declared = manifest.supports(capability)
            val implemented = type.isInstance(connector)
            if (declared && !implemented) problems += "declares $capability but does not implement ${type.simpleName}"
            if (implemented && manifest.maturityOf(capability) == null) {
                problems += "implements ${type.simpleName} but does not declare $capability"
            }
        }
        if (manifest.supports(Capability.WEBHOOKS) != (manifest.webhooks != null)) {
            problems += "WEBHOOKS capability and webhook support must be declared together"
        }
        if (manifest.supports(Capability.ACTIONS) && manifest.actions.isEmpty()) problems += "ACTIONS declared without any action"
        duplicates(manifest.resources.map { it.name }).forEach { problems += "resource '$it' is declared twice" }
        duplicates(manifest.actions.map { it.name }).forEach { problems += "action '$it' is declared twice" }
        (manifest.resources.map { it.name } + manifest.actions.map { it.name })
            .filterNot(NAME::matches)
            .forEach { problems += "name '$it' must match ${NAME.pattern}" }
        return problems
    }

    fun validate(
        connector: Connector,
        runtime: SdkVersion = SdkVersion.CURRENT
    ) {
        val problems = problems(connector, runtime)
        if (problems.isNotEmpty()) {
            throw InvalidManifestException(connector.manifest.id, problems)
        }
    }

    private fun duplicates(names: List<String>): Set<String> =
        names
            .groupingBy { it }
            .eachCount()
            .filterValues { it > 1 }
            .keys
}

class InvalidManifestException(
    val connectorId: String,
    val problems: List<String>
) : IllegalStateException("connector '$connectorId' is invalid: ${problems.joinToString("; ")}")
