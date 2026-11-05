plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai integrations Jira Cloud connector: OAuth 2.0 (3LO) or API token, projects, JQL reads, issue writes, transitions"

dependencies {
    api(project(":integrations-connector-sdk"))
    testImplementation(project(":integrations-connector-sdk-test"))
}
