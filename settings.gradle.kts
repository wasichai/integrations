pluginManagement {
    includeBuild("build-logic")
}

rootProject.name = "wasichai-integrations"

// the wasichai libraries come from GitHub Packages, which wants a token even to read.
// WASICHAI_PACKAGES_TOKEN (read:packages) wins; in CI the workflow's GITHUB_TOKEN is enough
// once each package grants this repository read access.
val packagesToken: String? = System.getenv("WASICHAI_PACKAGES_TOKEN") ?: System.getenv("GITHUB_TOKEN")
val packagesUser: String = System.getenv("GITHUB_ACTOR") ?: "token"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven {
            name = "WasichaiPackages"
            url = uri("https://maven.pkg.github.com/wasichai/wasichai")
            credentials {
                username = packagesUser
                password = packagesToken
            }
            content { includeGroup("wasichai") }
        }
    }
}

// -Pwasichai.local=../wasichai builds against a wasichai checkout instead of the packages (composite build)
providers.gradleProperty("wasichai.local").orNull?.let { includeBuild(it) }

// a folder is a module when it has a build file. no list to keep in sync.
fun includeModules(dirs: List<File>) {
    dirs
        .filter { it.isDirectory && File(it, "build.gradle.kts").isFile }
        .sortedBy { it.name }
        .forEach { dir ->
            include(dir.name)
            project(":${dir.name}").projectDir = dir
        }
}

fun children(parent: File): List<File> = parent.listFiles()?.toList() ?: emptyList()

includeModules(children(rootDir).filter { it.name.startsWith("integrations-") || it.name.startsWith("wasichai-integrations") })
includeModules(children(file("starters")))
