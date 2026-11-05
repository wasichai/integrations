# Releasing

This repository releases the Maven modules of Wasichai Integrations, all on one version (`gradle.properties`), aligned
by `integrations-bom`. The npm packages are released from
[wasichai/integrations-ui](https://github.com/wasichai/integrations-ui) on their own version line.

## Flow

1. Merge Conventional Commits (`feat:`, `fix:`, ...) to `main`.
2. `release-please.yml` opens or updates a release PR that bumps `version=` in `gradle.properties` and writes
   `CHANGELOG.md`.
3. Merging that PR tags `vX.Y.Z` and creates a GitHub Release.
4. The release triggers `publish.yml`: the `guards` job publishes to a throwaway local repository and runs
   `.github/scripts/check-maven-publications.sh`; only then the `maven` job runs `./gradlew publish` to
   `https://maven.pkg.github.com/wasichai/integrations`, version = the tag without `v`.

## Published modules

`integrations-bom`, `integrations-connector-sdk`, `integrations-connector-sdk-test`, `integrations-connector-notion`,
`integrations-connector-salesforce`, `integrations-connector-salesforce-pubsub`, `integrations-connector-jira`,
`integrations-connector-github`, `integrations-connector-slack`, `integrations-connector-openapi`, `wasichai-integrations`,
`wasichai-integrations-sync`, `wasichai-integrations-workflows`, `wasichai-integrations-ai`,
`wasichai-spring-boot-starter-integrations`, `wasichai-spring-boot-starter-integrations-sync`,
`wasichai-spring-boot-starter-integrations-workflows`, `wasichai-spring-boot-starter-integrations-ai`. Group `wasichai`.
`integrations-bom` constrains every published module by itself (any project but the BOM, the end-to-end suite and the
example app). `integrations-integration-tests` and `integrations-app` are never published; the app ships as a
container image built from its Dockerfile ([ADR-0028](../adr/0028-example-app-and-image.md)), not as a Maven artifact.

A new module fails the guard (CI job "Publish dry run (Maven)" on every pull request) until it is added to the list in
`.github/scripts/check-maven-publications.sh` on purpose.

## Secrets

| Secret | Needed for |
|---|---|
| `PACKAGES_TOKEN` (organization) | reading the `wasichai-*` packages in CI (the image build too, as a BuildKit secret) and in `publish.yml` |
| `RELEASE_PLEASE_TOKEN` | a release that triggers `publish.yml` (a release made with `GITHUB_TOKEN` triggers no workflow) |

Publishing itself uses the workflow's `GITHUB_TOKEN` (`packages: write`). After the first publish, give the apps
that consume these packages read access (package settings → Manage Actions access), as for wasichai's packages.

## Consuming

```kotlin
dependencies {
    implementation(platform("wasichai:integrations-bom:<version>"))
    implementation("wasichai:wasichai-spring-boot-starter-integrations")
    implementation("wasichai:wasichai-spring-boot-starter-integrations-sync")
    implementation("wasichai:wasichai-spring-boot-starter-integrations-workflows")
    implementation("wasichai:wasichai-spring-boot-starter-integrations-ai")
    implementation("wasichai:integrations-connector-notion")
    implementation("wasichai:integrations-connector-salesforce")          // or -salesforce-pubsub for change data capture
    implementation("wasichai:integrations-connector-jira")
    implementation("wasichai:integrations-connector-github")
    implementation("wasichai:integrations-connector-slack")
    implementation("wasichai:integrations-connector-openapi")
}
```

with the GitHub Packages repositories of `wasichai/wasichai` and `wasichai/integrations` declared and a token holding
`read:packages`.
