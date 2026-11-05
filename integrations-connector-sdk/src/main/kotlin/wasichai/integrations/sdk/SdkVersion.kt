package wasichai.integrations.sdk

// the contract version a connector is built against. major bumps break connectors, minor ones add.
data class SdkVersion(
    val major: Int,
    val minor: Int
) : Comparable<SdkVersion> {
    init {
        require(major >= 0 && minor >= 0) { "sdk version parts must not be negative" }
    }

    override fun compareTo(other: SdkVersion): Int = compareValuesBy(this, other, SdkVersion::major, SdkVersion::minor)

    // a connector built for 1.2 runs on a 1.5 runtime, never on 2.x nor on 1.1
    fun isSupportedBy(runtime: SdkVersion): Boolean = major == runtime.major && minor <= runtime.minor

    override fun toString(): String = "$major.$minor"

    companion object {
        // 1.1: ProviderHttp.credentialHeaders
        val CURRENT = SdkVersion(1, 1)

        private val FORMAT = Regex("""^(0|[1-9]\d*)\.(0|[1-9]\d*)$""")

        fun parse(text: String): SdkVersion {
            val match = requireNotNull(FORMAT.matchEntire(text)) { "sdk version must be <major>.<minor>, got '$text'" }
            return SdkVersion(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
    }
}
