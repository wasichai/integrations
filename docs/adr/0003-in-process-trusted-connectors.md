# ADR-0003: Connectors run in-process as trusted libraries; untrusted code waits for a remote protocol

**Status**: accepted · 2026-11-04

## Context

Third-party JVM plugins loaded into the app share its memory, credentials and network; class-loader isolation does not
stop a determined plugin. Isolated workers or a remote protocol need infrastructure the MVP does not have.

## Decision

- The MVP runs only connectors that are on the app's classpath, chosen by whoever builds the app: built-in connectors
  and third-party Maven artifacts the operator trusts. There is no upload of connector code at runtime.
- Even trusted connectors reach the network only through `ProviderHttp` (SSRF guard, rate limits, timeouts, redaction)
  and get only their own connection's credentials.
- Untrusted connectors, when needed, run out of process behind a remote execution protocol (the SDK interfaces over
  HTTP or gRPC) with resource limits, network policy and per-tenant secrets; that is a later ADR.

## Consequences

- A malicious connector artifact is a supply-chain problem of the app, as any dependency is.
- Contracts are already serializable shapes (JSON trees, typed errors), which keeps a remote protocol possible.
