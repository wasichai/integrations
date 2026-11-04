plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai integrations connector test kit: a stub provider server and contract tests every connector runs"

dependencies {
    api(project(":integrations-connector-sdk"))
    api(platform(libs.spring.boot.bom))
    api(libs.reactor.netty)
    api(libs.kotlinx.coroutines.reactor)
    api("org.junit.jupiter:junit-jupiter")
}
