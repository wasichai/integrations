plugins {
    `maven-publish`
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${System.getenv("GITHUB_REPOSITORY") ?: "wasichai/integrations"}")
            // only read when publishing there; publishToMavenLocal never needs them
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

plugins.withId("java-library") {
    publishing.publications.create<MavenPublication>("maven") {
        from(components["java"])
        // consumers get real versions, not "managed by a bom you don't import"
        versionMapping {
            usage("java-api") { fromResolutionOf("runtimeClasspath") }
            usage("java-runtime") { fromResolutionResult() }
        }
    }
}

plugins.withId("java-platform") {
    publishing.publications.create<MavenPublication>("maven") {
        from(components["javaPlatform"])
    }
}
