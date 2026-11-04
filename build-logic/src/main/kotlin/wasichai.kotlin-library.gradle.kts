plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jlleitschuh.gradle.ktlint")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun lib(alias: String) = libs.findLibrary(alias).get()

group = "wasichai"

kotlin {
    // 25 always in CI. -Pintegrations.jdk=21 is a local escape hatch for machines without a jdk 25;
    // it only builds the modules that do not link against wasichai (built for 25)
    jvmToolchain(providers.gradleProperty("integrations.jdk").orNull?.toInt() ?: 25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

java {
    withSourcesJar()
}

// ktlint reads the repo .editorconfig
ktlint {
    version.set(libs.findVersion("ktlintTool").get().requiredVersion)
}

// a plain library (no spring-module) still needs a test runner
dependencies {
    testImplementation(platform(lib("spring-boot-bom")))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// ktlint's tool classpath pins logback 1.3.15 (vulnerable). lift it; ktlint only logs through it
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "ch.qos.logback") {
            useVersion(libs.findVersion("logback").get().requiredVersion)
            because("logback below 1.5.34 is vulnerable")
        }
    }
}

// unit tests always run; container-backed ones go through integrationTest
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
}

// format rewrites sources in place: a cache hit would skip the rewrite and leave them unformatted
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask>().configureEach {
    outputs.cacheIf("formats sources in place") { false }
}

// `ktlintCheck ktlintFormat` in one invocation: check the formatted sources, not the old ones
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintCheckTask>().configureEach {
    mustRunAfter(tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.KtLintFormatTask>())
}

// the per-source-set "ktlint<SourceSet>Format" report task (ktlint-gradle's GenerateReportsTask) sits
// downstream of the rewrite above and is @CacheableTask by default: same reasoning, same fix
tasks.withType<org.jlleitschuh.gradle.ktlint.tasks.GenerateReportsTask>().configureEach {
    if (name.endsWith("Format")) {
        outputs.cacheIf("part of the format task group, not just the report") { false }
    }
}
