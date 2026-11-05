plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai integrations workflows: definitions, immutable versions, validation and durable execution of integration workflows"

dependencies {
    api(project(":wasichai-integrations"))
    implementation(libs.kotlinx.coroutines.reactor)

    testImplementation(libs.wasichai.test)
    testRuntimeOnly(libs.r2dbc.postgresql)
    testImplementation(libs.postgresql.jdbc)
    testRuntimeOnly(libs.flyway.postgresql)
}
