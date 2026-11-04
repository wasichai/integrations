plugins {
    id("wasichai.kotlin-library")
    id("wasichai.publishing")
}

description = "Wasichai integrations connector for Notion: data sources, pages, webhooks"

dependencies {
    api(project(":integrations-connector-sdk"))
    testImplementation(project(":integrations-connector-sdk-test"))
}
