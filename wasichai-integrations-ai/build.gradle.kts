plugins {
    id("wasichai.spring-module")
    id("wasichai.publishing")
    id("wasichai.integration-test")
}

description = "Wasichai integrations AI: a tool catalog of explicitly exposed connector actions and record operations, with an execution policy"

dependencies {
    api(project(":wasichai-integrations"))
    implementation(libs.kotlinx.coroutines.reactor)

    testImplementation(libs.wasichai.test)
    testRuntimeOnly(libs.r2dbc.postgresql)
    testImplementation(libs.postgresql.jdbc)
    testRuntimeOnly(libs.flyway.postgresql)
}
