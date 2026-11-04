# Event model

Every inbound change, from a webhook or a poll, is stored as an Event Envelope before anything acts on it.

```json
{
  "eventId": "6f1c…",
  "tenantId": "a3b0…",
  "connectionId": "91d2…",
  "connector": "notion",
  "providerEventId": "evt_…",
  "resourceType": "page",
  "resourceId": "external-id",
  "eventType": "record.updated",
  "occurredAt": "2026-11-04T10:15:00Z",
  "receivedAt": "2026-11-04T10:15:02Z",
  "schemaVersion": "1",
  "correlationId": "c0ff…",
  "causationId": null,
  "source": "webhook",
  "payload": {},
  "metadata": {}
}
```

## Event types

`record.created`, `record.updated`, `record.deleted`, `record.restored`, `action.completed`, `webhook.received`
(verified but not mapped), `sync.started`, `sync.completed`, `sync.failed`, `conflict.detected`.

## Identities kept apart

- **Internal event id** (`eventId`): ours, always present, the key other rows reference.
- **Provider event id** (`providerEventId`): the provider's delivery id when it sends one; the dedupe key. Without it,
  the hash of the raw body is the dedupe key.
- **Logical record identity**: (`connectionId`, `resourceType`, `resourceId`). Many events point at one record; a link
  in the sync engine pairs two logical identities.
- **Correlation id**: the chain a piece of work belongs to (request, run). **Causation id**: the event or write that
  caused this one; the sync engine sets it on events it causes, which feeds loop prevention.

## Ordering and trust

- No ordering is assumed. `occurredAt` is nullable because not every provider sends a reliable timestamp, and
  deliveries can arrive late, twice or never.
- A webhook event is a **signal**: it enqueues an incremental read of the resource, and the engine acts on the state it
  reads, not on the payload. That makes duplicates and reordering harmless and keeps one code path for webhooks and
  polling.
- The native payload is kept unchanged (`payload`) for diagnosis and replay; normalization adds fields, it never
  replaces the provider's data.

## Processing status

`RECEIVED` → `QUEUED` → `PROCESSED` | `IGNORED` (no definition interested) | `FAILED` (job dead). A failed event can be
replayed (`POST /api/integrations/events/{id}/replay`), which enqueues the same read again; it is safe because reads are
idempotent and writes are guarded by links and hashes.
