plugins {
    `java-platform`
    id("wasichai.publishing")
}

description = "Wasichai integrations BOM: one version for every published integrations module, on top of wasichai's"

javaPlatform {
    allowDependencies()
}

// never published as a constraint: the bom itself, the end-to-end suite and the example app (ADR-0028).
// rule this filter assumes: every other integrations project applies wasichai.publishing
val unpublished = setOf("integrations-bom", "integrations-integration-tests", "integrations-app")

dependencies {
    // the wasichai line these modules are built against
    api(platform(libs.wasichai.bom))
    constraints {
        rootProject.subprojects
            .filter { it.path != project.path && it.name !in unpublished && it.subprojects.isEmpty() }
            .forEach { api(project(it.path)) }
    }
}
