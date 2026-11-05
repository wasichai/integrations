plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai integrations GitHub connector: OAuth app, GitHub App user token or PAT, repositories, issue reads and writes"

dependencies {
    api(project(":integrations-connector-sdk"))
    testImplementation(project(":integrations-connector-sdk-test"))
}
