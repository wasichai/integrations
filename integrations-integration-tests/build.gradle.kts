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
}
