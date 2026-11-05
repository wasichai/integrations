plugins {
    id("wasichai.spring-module")
    id("wasichai.integration-test")
}

// never published: the end-to-end suites of the integration modules, booted as one app on postgres
description = "Wasichai integrations end-to-end tests"

dependencies {
    testImplementation(libs.wasichai.spring.boot.starter)
    testImplementation(libs.wasichai.test)
    testImplementation(libs.spring.boot.starter.webflux)
    testImplementation(libs.kotlinx.coroutines.reactor)

    // the platform and two real connectors, each pointed at a stateful stub provider (no real account in ci)
    testImplementation(project(":wasichai-integrations"))
    testImplementation(project(":wasichai-integrations-sync"))
    testImplementation(project(":wasichai-integrations-workflows"))
    testImplementation(project(":integrations-connector-notion"))
    testImplementation(project(":integrations-connector-salesforce"))
    testImplementation(project(":integrations-connector-sdk-test"))
    testRuntimeOnly(libs.r2dbc.postgresql)
    testImplementation(libs.postgresql.jdbc)
    testRuntimeOnly(libs.flyway.postgresql)
}
