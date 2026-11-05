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

## Quick start (Docker)

Run the example app (every module, the shipped connectors) with PostgreSQL 18, as an `api` and a `worker` container:

```bash
cd deploy/compose
cp .env.example .env                 # fill in every replace-me: openssl rand -base64 48 / -base64 32 / -hex 24
export WASICHAI_PACKAGES_TOKEN=...   # read:packages, to build the image
docker compose build && docker compose up -d
curl -s localhost:8080/actuator/health
```

Set `WASICHAI_SEED_DEV=true` in `.env` for a local trial to sign in as `admin@wasichai.local` / `admin`; never on a
reachable host. Variables, roles and scaling: [deployment guide](docs/guides/deployment.md#the-example-app-and-its-container-image).

## Build

```bash
./gradlew build             # ktlint + unit tests
./gradlew integrationTest   # needs Docker (Testcontainers)
```

The `wasichai-*` libraries come from GitHub Packages: export `WASICHAI_PACKAGES_TOKEN` (a token with `read:packages`),
or build against a local checkout with `-Pwasichai.local=../wasichai`.
