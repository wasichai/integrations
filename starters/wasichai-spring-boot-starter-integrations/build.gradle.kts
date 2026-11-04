plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Starter: wasichai plus the integrations module"

dependencies {
    api(platform(libs.wasichai.bom))
    api(libs.wasichai.spring.boot.starter)
    api(project(":wasichai-integrations"))
}
