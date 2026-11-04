plugins {
    id("wasichai.kotlin-library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

dependencies {
    testImplementation(lib("spring-boot-testcontainers"))
    testImplementation(lib("testcontainers-junit"))
    testImplementation(lib("testcontainers-postgresql"))
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Runs tests tagged 'integration' against a PostgreSQL container."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("integration")
    }
    // WASICHAI_TEST_DB_* env vars (if set) point tests at an external db instead of testcontainers;
    // test workers inherit the environment already, no passthrough needed
    shouldRunAfter(tasks.named("test"))
    // every module applies this plugin, most have no integration tests: zero tests is not an error
    failOnNoDiscoveredTests.set(false)
}
