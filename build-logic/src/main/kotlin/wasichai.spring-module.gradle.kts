plugins {
    id("wasichai.kotlin-library")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    api(platform(lib("spring-boot-bom")))
    // the wasichai libraries this repository builds on: one version for all of them
    api(platform(lib("wasichai-bom")))
    // security floors over the boot bom (see libs.versions.toml). api: apps on these modules get them too
    api(platform(lib("jackson2-bom")))
    api(platform(lib("jackson3-bom")))
    constraints {
        // via r2dbc-postgresql / pgjdbc: channel-binding downgrade below 3.3
        api(lib("scram-client"))
        api(lib("scram-common"))
    }
    api(lib("spring-boot-autoconfigure"))
    // Spring needs this for Kotlin constructor binding / @ConfigurationProperties classes
    implementation(lib("kotlin-reflect"))

    testImplementation(lib("spring-boot-starter-test"))
    testImplementation(lib("reactor-test"))
    testImplementation(lib("kotlinx-coroutines-test"))
    // junit-platform-launcher comes from wasichai.kotlin-library
}
