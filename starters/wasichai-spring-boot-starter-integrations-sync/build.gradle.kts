plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Starter: wasichai plus the integrations and sync modules"

dependencies {
    api(project(":wasichai-spring-boot-starter-integrations"))
    api(project(":wasichai-integrations-sync"))
}
