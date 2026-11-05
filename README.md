# Wasichai Integrations

An integration platform for [Wasichai](https://github.com/wasichai/wasichai) apps, shipped as libraries: a Kotlin
connector SDK, managed connections and encrypted credentials, inbound webhooks, durable jobs and a bidirectional
synchronization engine. The UI packages live in [wasichai/integrations-ui](https://github.com/wasichai/integrations-ui).

Status: under construction. See [docs/product/](docs/product/) and the issues for the roadmap.

## Documentation

- [Guides](docs/guides/README.md): [user guide](docs/guides/user-guide.md),
  [connector SDK](docs/guides/connector-sdk.md), [writing a connector](docs/guides/connector-developer.md),
  [synchronization](docs/guides/sync.md), [workflows](docs/guides/workflows.md), [AI tools](docs/guides/ai-tools.md),
  [deployment](docs/guides/deployment.md), [operations](docs/guides/operations.md).
- Everything else (architecture, REST API, ADRs, product): [docs/README.md](docs/README.md).

## Build

```bash
./gradlew build             # ktlint + unit tests
./gradlew integrationTest   # needs Docker (Testcontainers)
```

The `wasichai-*` libraries come from GitHub Packages: export `WASICHAI_PACKAGES_TOKEN` (a token with `read:packages`),
or build against a local checkout with `-Pwasichai.local=../wasichai`.
