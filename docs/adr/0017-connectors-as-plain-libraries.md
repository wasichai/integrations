# ADR-0017: Connectors are plain SDK libraries; the app declares their beans

**Status**: accepted · 2026-11-05 · supersedes [ADR-0012](0012-connector-distribution-and-versioning.md) on how a
connector is registered; its versioning rules (semver, `sdkVersion` compatibility) stay as they are

## Context

ADR-0012 says each connector artifact ships its own auto-configuration that registers a `Connector` bean, so that
adding the dependency installs the connector. None of the shipped connectors does that
([#59](https://github.com/wasichai/integrations/issues/59)): `integrations-connector-notion`,
`integrations-connector-salesforce` and `integrations-connector-jira` depend only on `integrations-connector-sdk`, have
no Spring dependency and no `AutoConfiguration.imports`. The platform's `ConnectorRegistry` collects every `Connector`
bean the application context holds, and the app (as `integrations-integration-tests` does) declares those beans.

## Decision

- A connector artifact is a plain library on the SDK: Kotlin, coroutines and Jackson, no Spring. It ships no
  auto-configuration, and adding the dependency registers nothing by itself.
- The app declares each connector it installs as a bean, with its configuration in code, as the
  [deployment guide](../guides/deployment.md) and the [connector developer guide](../guides/connector-developer.md#6-install-it-in-an-app)
  show:

  ```kotlin
  @Configuration
  class ConnectorsConfiguration {
      @Bean
      fun notionConnector(): NotionConnector = NotionConnector()

      @Bean
      fun salesforceConnector(): SalesforceConnector = SalesforceConnector(SalesforceConfig(loginUrl = "https://test.salesforce.com"))
  }
  ```

- The registry validates every bean at start-up; an invalid manifest or a duplicate id stops the app (unchanged).
- A third-party connector may still ship an auto-configuration of its own; it then keeps its SDK code free of Spring
  and guards the bean with `@ConditionalOnMissingBean`. Ours do not.

Why:

- **No Spring in connectors.** A connector is tested and reused against the SDK alone (`integrations-connector-sdk-test`
  needs no application context), and its code cannot reach platform beans, which keeps the boundary of ADR-0003.
- **An explicit choice of installed connectors.** Connectors run in-process with the platform's trust (ADR-0003): an
  app installs exactly the ones it names, and a dependency pulled in transitively cannot start talking to a provider.
- **Configuration in code.** Sandboxes, API versions and test stubs (`SalesforceConfig`, `NotionConfig`, `JiraConfig`)
  are constructor arguments, so no `wasichai.integrations.connectors.*` property tree is needed per connector
  (CLAUDE.md, no overengineering).

## Consequences

- Installing a connector takes a dependency plus one bean declaration; the deployment guide says so.
- Connector versions, `sdkVersion` and the compatibility check are still ADR-0012's.
- A catalog or marketplace, when it comes, lists artifacts and the bean to declare, not auto-configurations.
