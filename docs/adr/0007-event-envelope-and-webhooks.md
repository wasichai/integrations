# ADR-0007: Inbound changes are stored as Event Envelopes; webhooks are verified signals

**Status**: accepted · 2026-11-04

## Context

Providers deliver webhooks late, twice, out of order or not at all, and not all of them sign deliveries or timestamp
changes reliably.

## Decision

- Every webhook delivery and poll result is stored as an Event Envelope ([event model](../architecture/event-model.md))
  keeping the native payload, with internal id, provider event id, logical record identity, correlation and causation.
- Webhooks arrive at `/webhooks/{connectorId}/{endpointId}` with a random endpoint id; the connector's `WebhookHandler`
  verifies the raw body (signature, handshake) before parsing; a bad signature stores nothing.
- Deduplication by unique constraint on provider event id, or on the body hash when there is none.
- A webhook event enqueues an incremental read; the engine acts on what it reads. Polling stays on as a backstop.

## Consequences

- One processing path for webhooks and polling; ordering bugs become impossible by construction.
- Event payloads are provider data and fall under retention.
