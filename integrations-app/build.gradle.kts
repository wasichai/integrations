plugins {
    id("wasichai.spring-module")
    id("wasichai.integration-test")
    alias(libs.plugins.spring.boot)
}

// never published (no wasichai.publishing, left out of the bom): the runnable example app the container image
// is built from (ADR-0028). configured by environment variables only
description = "Wasichai integrations example application"

dependencies {
    // wasichai plus every integrations module, through their starters
    implementation(project(":wasichai-spring-boot-starter-integrations-sync"))
    implementation(project(":wasichai-spring-boot-starter-integrations-workflows"))
    implementation(project(":wasichai-spring-boot-starter-integrations-ai"))
    implementation(libs.spring.boot.starter.webflux)
    // /actuator/health for the container's healthcheck; the wasichai starter has it only as a runtime dependency
    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.r2dbc.postgresql)
    runtimeOnly(libs.postgresql.jdbc)
    runtimeOnly(libs.flyway.postgresql)

    // the shipped connectors; each bean is switched by app.connectors.<id>.enabled
    implementation(project(":integrations-connector-notion"))
    implementation(project(":integrations-connector-salesforce"))
    implementation(project(":integrations-connector-salesforce-pubsub"))
    implementation(project(":integrations-connector-jira"))
    implementation(project(":integrations-connector-github"))
    implementation(project(":integrations-connector-slack"))

    testImplementation(libs.wasichai.test)
}

// one fixed name: the Dockerfile copies it
tasks.bootJar {
    archiveFileName.set("integrations-app.jar")
}
