plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai integrations Slack connector: OAuth v2 bot token, channels, message reads and writes, Events API"

dependencies {
    api(project(":integrations-connector-sdk"))
    testImplementation(project(":integrations-connector-sdk-test"))
}
