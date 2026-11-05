# ADR-0024: Salesforce Change Data Capture is a bounded pull over the Pub/Sub API, in its own module

**Status**: accepted · 2026-11-05 · supersedes [ADR-0014](0014-salesforce-change-capture.md) on "CDC comes later":
polling stays the default and the backstop; its replay id rules (stored, never computed; an expired one triggers
reconciliation) are kept and made concrete here

## Context

Issue [#14](https://github.com/wasichai/integrations/issues/14) asks for Salesforce Change Data Capture. CDC events are
delivered by the [Pub/Sub API](https://developer.salesforce.com/docs/platform/pub-sub-api/overview): gRPC at
`api.pubsub.salesforce.com:7443`, service `eventbus.v1.PubSub`, a bidirectional `Subscribe` stream driven by
`FetchRequest`s (`topic_name`, `replay_preset` `LATEST`/`EARLIEST`/`CUSTOM`, `replay_id`, `num_requested`), events
Avro-encoded with a schema fetched by id (`GetSchema`), and authentication by the `accesstoken`, `instanceurl` and
`tenantid` (org id) call headers. Events are retained 72 hours; a replay id is opaque.

The platform constrains how this fits:

- `ChangeFeed.changes(context, resource, checkpoint, pageSize)` is pull-based: the sync engine calls it in a run, stores
  the returned checkpoint per definition side, and asks again while `hasMore`.
- When `changes` throws `ConnectorException.Permanent` on a non-null checkpoint, the engine resets that side once per
  run: it reads it from `null` (a full read), counts `cursorResets`, and runs the reconcile checks (missing links,
  drift) over what it read (`SyncEngine.feed`, tested in `SyncMonitoringTest`). That is exactly "reconcile needed".
- A connector never sees a secret: `ProviderHttp.exchange` adds the credentials to HTTP calls. gRPC is not HTTP the
  platform can carry, and the Pub/Sub API wants the token in its own headers.

## Decision

- **A separate module.** `integrations-connector-salesforce-pubsub` holds `SalesforcePubSubConnector`, the proto, gRPC,
  protobuf and Avro. `integrations-connector-salesforce` stays dependency-free (only the SDK), so polling users pull
  none of it. The new connector delegates authentication, discovery, reads, writes and upserts to
  `SalesforceConnector` and keeps its id `salesforce`: an app registers one of the two beans and existing connections
  keep working. The base connector gains one public method, `readMany(context, resource, ids)`.
- **Opt-in per object.** `SalesforcePubSubConfig(channels = mapOf("Account" to "/data/AccountChangeEvent"))`: an object
  with a channel reads changes by CDC, any other object by polling. Channels must start with `/data/` (a standard
  `<Object>ChangeEvent`, a custom `<Object>__ChangeEvent`, `/data/ChangeEvents` or a custom channel `/data/X__chn`);
  CDC needs the entitlement and the objects selected in Setup, which the platform cannot check.
- **A bounded pull, not a long-lived subscriber.** Each `changes()` call opens `Subscribe`, sends one `FetchRequest` for
  up to `batchSize` (≤ 100, the API's limit) events, takes what arrives within `drain` (5 s default) or stops when the
  batch is full, cancels the call, and returns. A full batch answers `hasMore = true`. This fits the pull-based feed,
  the engine's per-definition run lock and its cursor storage without a new lifecycle (who owns a stream, where events
  wait between runs, what happens on a restart or on two nodes). Latency stays the scheduler's interval plus at most
  `drain`; correctness does not depend on it. A long-lived subscriber per connection would push events into a run, which
  the platform has no place for today; it needs its own ADR when latency matters. The client uses grpc-kotlin's
  `ClientCalls` over the generated grpc-java descriptors: everything suspends on gRPC callbacks, no thread waits.
- **Checkpoint.** JSON with `channel`, `replay` (the replay id as received, base64, never computed), `since` (when the
  full read that started the feed began) and `full` (the polling checkpoint while that read pages). A checkpoint
  without `channel` is a polling one.
- **Start and restart.** A `null` checkpoint means a full read through the polling feed (SOQL by `SystemModstamp`).
  Then the stream subscribes from `EARLIEST` and skips events committed before `since` minus five minutes of clock skew
  (the full read has them; anything later is fetched again, which the engine's hashes make harmless). It does not
  subscribe from `LATEST`: a bounded pull from `LATEST` loses whatever happens between two pulls until an event names a
  replay id. `since` is dropped once an event at or after it arrives. The `replay` stored is the last
  `latest_replay_id` the server sent, else the last event's replay id; a pull without events keeps the old one.
- **Events are hints.** From the `ChangeEventHeader` only `entityName`, `changeType`, `recordIds` and
  `commitTimestamp` are read; the payload's fields (and `changedFields`) are not. `CREATE`, `UPDATE` and `UNDELETE` ids
  are fetched by id (`readMany`, one SOQL `Id IN (...)` query per 200 ids) and become `Change.Upserted`; `DELETE`
  becomes `Change.Deleted` at the commit timestamp. The last change per record in a batch wins. An id the query does
  not return (deleted since, or not visible to the user) is logged as a count, not reported: its `DELETE` event
  follows, and polling would not see it either. Events of another object on a shared channel are counted and skipped;
  that object's own feed reads them.
- **Reconcile signal.** The connector throws `ConnectorException.Permanent` with `details["reason"]`:
  `replay_id_refused` (the stored replay id was refused), `reconcile` (a `GAP_*` event, an unknown change type, an event
  without record ids or with an id that is not one), `channel_changed` (the configured channel differs from the
  checkpoint's) or `feed_changed` (a polling checkpoint for a CDC object, or the reverse). The engine turns it into the
  cursor reset, full read and reconcile described above; no new SDK type was needed. A gap event committed before
  `since` is covered by the full read that set it and skipped, so the reset that a gap caused does not repeat. Every
  other failure is kept away from `Permanent` so a misconfiguration fails the run instead of silently degrading into a
  full read every run: `UNAUTHENTICATED` renews the token once and then is `AuthExpired`; `RESOURCE_EXHAUSTED` is
  `RateLimited`; `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INTERNAL`, `ABORTED`, `UNKNOWN`, `CANCELLED` are `Transient`;
  `NOT_FOUND` is `NotFound`; anything else (`PERMISSION_DENIED`, an unknown topic, no entitlement) is
  `PermissionDenied`. An event that cannot be decoded is an `IllegalStateException` (retried as unexpected).
- **SDK 1.1: `ProviderHttp.credentialHeaders(refresh)`.** The smallest addition that lets a non-HTTP transport
  authenticate: the platform returns the connection's credential headers (and with `refresh = true` renews an OAuth
  token once, as `exchange` does on a 401). Its default throws `UnsupportedOperationException`, so other
  implementations compile unchanged. The connector hands the token straight to the gRPC call headers and never keeps
  or logs it (`PubSubAuth.toString` is masked). The org id comes from the identity url kept at connect time
  (`/id/<org>/<user>`), else from `userinfo`. The platform's rate limiter, host checks and HTTP metrics do not see gRPC
  calls; the endpoint comes from the app's configuration, never from user input.
- **Proto.** `src/main/proto/pubsub_api.proto` reproduces only `Subscribe`, `GetSchema` and their messages from
  [Salesforce's published file](https://github.com/forcedotcom/pub-sub-api/blob/main/pubsub_api.proto), with its
  package and service names (the wire names) and our own `java_package`.
- **Dependencies** (catalog, this module only): `grpc-okhttp` (no Netty copy; much smaller than `grpc-netty-shaded`),
  `grpc-protobuf`, `grpc-stub`, `grpc-kotlin-stub` and `protobuf-java` at the Spring Boot 4.1.2 BOM's versions (gRPC
  1.84.1, grpc-kotlin 1.5.0, protobuf 4.36.2); `org.apache.avro:avro` 1.12.2; build-time only the
  `com.google.protobuf` Gradle plugin 0.10.0, `protoc` 4.36.2 and `protoc-gen-grpc-java` 1.84.1, pinned to the runtime
  lines. Generated sources are excluded from ktlint. Tests add `grpc-inprocess`.
- **Maturity.** `CHANGE_FEED` stays `TESTED`: both paths have contract tests against deterministic stubs (an
  in-process gRPC server with Avro events built from the documented `ChangeEventHeader`), as every connector here is
  tested. No test ran against a real org.

## Uncertain points (verify against a real org before relying on them)

- **Field numbers** were reproduced from memory of the published `pubsub_api.proto` (`FetchRequest` 1–5,
  `FetchResponse` 1–4, `ConsumerEvent` 1–2, `ProducerEvent` 1–4, `SchemaRequest` 1, `SchemaInfo` 1–3,
  `ReplayPreset` `LATEST = 0, EARLIEST = 1, CUSTOM = 2`). A mismatch would show at once as empty or garbled fields.
- **The refusal of an expired or invalid replay id** is matched as status `INVALID_ARGUMENT`, `FAILED_PRECONDITION`,
  `NOT_FOUND` or `OUT_OF_RANGE` on a `CUSTOM` subscription whose `error-code` trailer contains `replayid` (Salesforce
  documents codes such as `sfdc.platform.eventbus.grpc.subscription.fetch.replayid.corrupted`) or whose description
  contains "replay" ("The Replay ID validation failed..."). If Salesforce answers otherwise, the run fails with
  `PermissionDenied` naming the status: visible, not silent, but needing a person.
- **`GAP_OVERFLOW`** is assumed to carry no record ids; either way every `GAP_*` event leads to a reconcile.
- `latest_replay_id` on an event-less response is stored as the position, as Salesforce documents for keepalives.

## Consequences

- An org with CDC reads only the records that changed instead of a SOQL window per run, and sees deletions without the
  `deleted` resource's window. Each run still opens one gRPC call per CDC object and side and waits up to `drain`
  when fewer events than a batch are waiting.
- A restart (expired replay id, gap, channel change) costs one full read of the object, then a pass over the retained
  events since it began.
- A connector that needs another transport can now borrow credentials; the guide says it must not keep them.
