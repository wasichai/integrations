plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai integrations: connector registry, connections, encrypted credentials, provider http, jobs and webhooks"

dependencies {
    api(libs.wasichai.core)
    api(project(":integrations-connector-sdk"))
    implementation(libs.kotlinx.coroutines.reactor)
    // meters for provider calls, jobs and syncs; the app's registry when it has actuator
    api(libs.micrometer.core)

    testImplementation(libs.wasichai.test)
    testImplementation(project(":integrations-connector-sdk-test"))
    testRuntimeOnly(libs.r2dbc.postgresql)
    testImplementation(libs.postgresql.jdbc)
    testRuntimeOnly(libs.flyway.postgresql)
}
