# ADR-0028: An unpublished example app ships as one image with two roles, configured by the environment only

**Status**: accepted · 2026-11-05 · builds on [ADR-0017](0017-connectors-as-plain-libraries.md),
[ADR-0008](0008-durable-jobs-in-postgresql.md)

## Context

[#45](https://github.com/wasichai/integrations/issues/45) asks for something runnable: until now the modules were only
libraries, booted as an app by the end-to-end suite (`integrations-integration-tests`) and nowhere else. An operator
who wants to try the platform, or deploy it as it is, has to assemble a Spring Boot app, declare connector beans
(ADR-0017), write its configuration and package it. The jobs worker and the sync and workflow schedulers can already be
switched per replica (`jobs.worker-enabled`, `sync.scheduler-enabled`, `workflows.scheduler-enabled`), but nothing
shows a deployment that uses those switches.

## Decision

- **A new Gradle project, `integrations-app`, never published to Maven.** It applies neither `wasichai.publishing` nor
  appears in `integrations-bom` (the BOM's `unpublished` set names it), and the release guard
  (`check-maven-publications.sh`) fails if it ever turns up. It is an example and a deployable, not an API: apps
  that build their own keep depending on the starters and connectors. It ships as a container image built from its
  `Dockerfile`; publishing that image to a registry is left for when someone needs a released image.
- **What it contains.** The Wasichai starter through the integrations, sync, workflows and AI starters, Spring Boot
  WebFlux and actuator (health only), and the shipped connectors: Notion, Salesforce (polling, or the Pub/Sub Change
  Data Capture connector when channels are configured, under the same id `salesforce`), Jira, GitHub and Slack. It
  adds one configuration class and no endpoint, table or behaviour of its own.
- **Connectors are beans the app declares (ADR-0017), each behind `app.connectors.<id>.enabled`** (default `true`), so
  an operator removes one from the catalog without a rebuild. A connector's settings are properties next to its
  switch (`app.connectors.jira.site-url`, `app.connectors.github.api-url`, `app.connectors.salesforce.cdc-channels`...);
  blank counts as unset, because compose passes empty variables as empty strings. CDC channels are one
  `Object=/data/Channel,...` string, not a map: environment variable names cannot carry the case of Salesforce object
  names. The connectors' own validation still runs at start-up, so a bad url stops the app.
- **The OpenAPI connector is not included.** Its configuration is a mapping of resources to operations plus a security
  scheme, written per API in code (ADR-0021). Binding that from environment variables would be a configuration language
  of its own for a connector with no second user in this app. An operator who needs it builds an app on this one's
  model and declares the bean there.
- **Configured by the environment only.** No configuration file is mounted: `application.yml` in the jar holds
  defaults without secrets, and every property is set by Spring's relaxed binding (`WASICHAI_INTEGRATIONS_PUBLICBASEURL`,
  `WASICHAI_INTEGRATIONS_ENCRYPTION_KEYS_K1`), plus Wasichai's own `WASICHAI_DB_*` and `WASICHAI_JWT_SECRET`. The JWT
  secret and the encryption keys have no default; the placeholders of `.env.example` are refused at start-up. Encryption
  key ids are therefore lower-case letters and digits (an environment variable name lowercases them and cannot carry
  `-`). The app turns on Wasichai's sign-in limits and token revocation, as Wasichai recommends for production.
- **One image, two roles, chosen by a Spring profile.** `api` turns the job worker and both schedulers off; `worker`
  turns them on (the modules' defaults, written out). Without a profile one replica does everything. The image does not
  know its role, so the same tag scales either way, and the role cannot drift from the code it runs. A worker still
  serves HTTP; routing decides that it gets no traffic.
- **The image.** Multi-stage: Gradle on `eclipse-temurin:25-jdk` builds the boot jar, `eclipse-temurin:25-jre` runs
  it as a non-root user (uid 10001). The `read:packages` token for the `wasichai-*` libraries is a BuildKit secret
  (`--secret id=packages_token`), mounted for the one build step, never an `ARG` or `ENV`. `java` is the container's
  main process (exec form) so `SIGTERM` reaches it: `server.shutdown=graceful` with a 30 s phase timeout, compose's
  grace period 40 s. The heap follows the container's memory limit (`MaxRAMPercentage=75`) and an
  `OutOfMemoryError` ends the process. `HEALTHCHECK` calls `/actuator/health`, already public in Wasichai's security
  chain and including the database; nothing else of actuator is exposed.
- **Compose is the reference deployment**, in `deploy/compose/`: PostgreSQL 18 with a volume and a healthcheck, `api`
  (the only published port) and `worker` from the same image, the worker started after the api is healthy so a first
  start migrates once. Secrets live in a gitignored `.env` copied from `.env.example`.
- **CI builds the image and smoke-tests it** (`.github/scripts/smoke.sh`): compose up, every service healthy, the
  seeded admin (`WASICHAI_SEED_DEV=true`, Wasichai's development seed, the only way Wasichai 1.10.0 creates a first
  user without SQL) signs in, and the catalog lists the five shipped connectors. A context test
  (`IntegrationsAppTest`) checks the same on Testcontainers in `integrationTest`.

## Consequences

- An operator can run the platform from the repository with Docker and one token, and scale API and background work
  separately with one image.
- The app is one more module to keep building: a new shipped connector is added to it (bean, switch, the smoke test's
  expected list) on purpose.
- The image is built in CI on every pull request but not pushed anywhere; releases publish Maven artifacts only.
  Publishing the image (tags, registry, signing) needs its own decision.
- There is no production way to create the first user from the environment: Wasichai has none. The development seed
  must stay off outside trials, and the first organization is created as Wasichai documents.
- Rate limits and AI tool rate limits stay per replica (ADR-0026, #47): more `api` or `worker` replicas admit more.
