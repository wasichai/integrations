# ADR-0014: Salesforce changes are polled first; Pub/Sub Change Data Capture comes later

**Status**: accepted · 2026-11-04

## Context

Salesforce Change Data Capture is delivered through the Pub/Sub API over gRPC with Avro payloads, 72-hour retention and
opaque replay ids ([durability](https://developer.salesforce.com/docs/platform/pub-sub-api/guide/event-message-durability.html)).
That adds gRPC, Avro and long-lived subscriptions per connection.

## Decision

- The MVP reads changes by SOQL on `SystemModstamp` and the REST `updated` / `deleted` resources, with cursors and
  reconciliation as backstop. The API version is discovered at runtime (`GET /services/data/`).
- CDC is wasichai/integrations#14: replay ids stored, never computed; an expired replay id triggers reconciliation.

## Consequences

- Latency is the polling interval for Salesforce in the MVP; correctness does not depend on it.
