# Wasichai Integrations — integration platform, as Wasichai libraries (backend + docs)

Reusable Spring Boot modules that connect a Wasichai app to external systems: a connector SDK, managed connections and
credentials, webhooks, durable jobs and bidirectional synchronization. The React packages live in
[wasichai/integrations-ui](https://github.com/wasichai/integrations-ui); its decisions live here too. Wasichai itself
([wasichai/wasichai](https://github.com/wasichai/wasichai)) is consumed as published libraries and never changed from here.

## Non-negotiable stack

- Kotlin 2.4.21, Spring Boot 4.1 **WebFlux**, Gradle (Kotlin DSL, version catalog, convention plugins in `build-logic`), JDK 25
- PostgreSQL 18 through R2DBC `DatabaseClient`; Flyway migrations per module
- Wasichai `wasichai-*` 1.10.0 from GitHub Packages (`wasichai-bom` aligns them)

## Architectural rules

1. **Wasichai's rules apply** (its `CLAUDE.md`): no JPA/Hibernate/Spring Data, SQL only through `DatabaseClient` with
   bound values, schema names from `WasichaiSchemas`, tenant from the JWT (`CurrentUser`), beans in auto-configurations
   with `@ConditionalOnMissingBean`, a `wasichai.<module>.enabled` switch and own Flyway history per module.
2. **The core knows no provider.** Provider names, URLs and payload shapes live only in `integrations-connector-*`.
3. **Connectors talk to the platform only through the SDK** (`integrations-connector-sdk`), and declare what they really
   implement; a capability not tested is not marked `TESTED`.
4. **Secrets are never plaintext**: stored through the credential vault, masked in responses, absent from logs.
5. **Never silently discard** an event or a conflict; never silently overwrite a concurrent change.
6. **Reactive paths never block.**
7. **The REST API is the contract with integrations-ui**: backend first, documented in `docs/architecture/api.md`.
8. **No overengineering**: an abstraction needs a concrete second user, or an ADR that says why not.

## Working rules

- `.editorconfig` is law (Kotlin 4 spaces, 160 columns, LF). `./gradlew ktlintFormat` before finishing.
- Conventional Commits (`feat(sync): …`, `fix(connector-notion): …`, `docs: …`), commit body lines ≤ 100 chars.
- Code, identifiers and comments in English; comments short, say why.
- History in `docs/HISTORY.md` (newest first), decisions in `docs/adr/` (never edit an accepted ADR, supersede it).
- Tests first. Integration tests are tagged `integration` and run on Testcontainers PostgreSQL 18 (never port 5432).
- No real provider account in CI: connectors are tested against deterministic stubs built from documented payloads.

## Commands

```bash
./gradlew build                                  # ktlint + unit tests
./gradlew integrationTest                        # Testcontainers PostgreSQL
./gradlew build -Pwasichai.local=../wasichai     # build against a wasichai checkout instead of GitHub Packages
```

Reading GitHub Packages needs `WASICHAI_PACKAGES_TOKEN` (or `GITHUB_TOKEN`) with `read:packages`.

## Where things are

- Product: [docs/product/](docs/product/) · Architecture: [docs/architecture/](docs/architecture/) · ADRs: [docs/adr/](docs/adr/)

## Definition of Done

Works · has tests (unit + integration where data is touched) · tenant isolation tested · errors handled · observable ·
documented · ADR when a decision is made · formatted · CI green.
