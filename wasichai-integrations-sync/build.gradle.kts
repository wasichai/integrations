plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai integrations sync: definitions, field mappings, record links, conflicts and the sync engine"

dependencies {
    api(project(":wasichai-integrations"))
    implementation(libs.kotlinx.coroutines.reactor)

    testImplementation(libs.wasichai.test)
    testImplementation(project(":integrations-connector-sdk-test"))
    testRuntimeOnly(libs.r2dbc.postgresql)
    testImplementation(libs.postgresql.jdbc)
    testRuntimeOnly(libs.flyway.postgresql)
}
