// kotlin-dsl pulls gradle's embedded kotlin plugin (2.4.10, vulnerable build cache). lift it to ours
buildscript {
    dependencies {
        constraints {
            classpath(libs.kotlin.gradle.plugin)
        }
    }
}

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.ktlint.gradle.plugin)

    testImplementation(gradleTestKit())
    testImplementation(platform(libs.spring.boot.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // testkit builds are slow. one jvm, no parallel forks fighting over the gradle user home.
    maxParallelForks = 1
}
