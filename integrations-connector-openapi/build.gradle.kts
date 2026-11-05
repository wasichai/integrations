plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai integrations OpenAPI connector: a REST API described by an OpenAPI 3 document plus an explicit mapping"

dependencies {
    api(project(":integrations-connector-sdk"))
    // most openapi documents are yaml (ADR-0021)
    implementation(libs.jackson.dataformat.yaml)
    testImplementation(project(":integrations-connector-sdk-test"))
}
