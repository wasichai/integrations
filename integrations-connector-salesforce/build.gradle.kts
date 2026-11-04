plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai integrations Salesforce connector: OAuth, describe, SOQL, CRUD, external-id upsert, polling change feed"

dependencies {
    api(project(":integrations-connector-sdk"))
    testImplementation(project(":integrations-connector-sdk-test"))
}
