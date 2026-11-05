# ADR-0020: Slack connects as the workspace's bot, polls new messages only, and maps `ok:false` codes

**Status**: accepted · 2026-11-05

## Context

Slack ([#39](https://github.com/wasichai/integrations/issues/39)) is reached through its Web API and differs from the
shipped connectors in ways the SDK does not settle:

- An app installs into a workspace through OAuth v2 (`/oauth/v2/authorize`, `oauth.v2.access`) and gets a bot token
  (`xoxb-`). The token response is RFC 6749-shaped (`access_token`, comma-separated `scope`), plus `team` and
  `enterprise` objects. With token rotation on, it also carries `refresh_token` and `expires_in` (twelve hours), and
  `oauth.v2.access` takes `grant_type=refresh_token`. Failures, including a refused code or refresh, answer HTTP 200
  with `{"ok": false, "error": "<code>"}`.
- Every Web API method answers HTTP 200 with `ok:false` and a documented error code on failure; rate limits answer 429
  with `Retry-After`. Lists page by `response_metadata.next_cursor` (empty at the end).
- `conversations.history` returns a channel's top-level messages (and thread replies also sent to the channel), newest
  first, filtered by `oldest`/`latest` ts strings. A message's `ts` is its id; an edit adds `edited.ts` but does not move
  the message; a deletion removes it, or leaves a `tombstone` when it had replies. Thread replies are only in
  `conversations.replies`.
- `chat.postMessage`, `chat.update` and `chat.delete` act as the bot; a bot token edits and deletes only its own
  messages. There is no conditional edit.
- The Events API signs every request: `X-Slack-Signature: v0=<hex hmac-sha256>` over
  `v0:{X-Slack-Request-Timestamp}:{raw body}` with the app's signing secret, and publishes a worked example. A new
  request url is checked by a signed `url_verification` whose `challenge` must be echoed. Retries carry
  `X-Slack-Retry-Num` and the same `event_id`.

## Decision

- **Auth.** `OAuth2AuthorizationCode` with `scopeSeparator = ","`, default bot scopes `channels:read`, `groups:read`,
  `channels:history`, `groups:history`, `chat:write`, the client secret in the body, PKCE off (the v2 flow authenticates
  the app by its secret) and no revocation url (`auth.revoke` is not RFC 7009). Token rotation needs no code: the
  platform's refresh is Slack's documented one. A bot token is an `ApiKey` sent as `Authorization: Bearer`.
- **Account.** `auth.test`: the account is the workspace, `team_id`, prefixed by `enterprise_id` on an Enterprise Grid
  (`E…:T…`), so reinstalling the app (a new bot token) is the same account. The team name is the display name.
- **Resources and records.** A channel (`C…`/`G…` id) the bot is a member of is the resource: history is readable in
  those only, so discovery lists `conversations.list` (public and private, archived left out) filtered by `is_member`.
  Records are the history's messages, the `ts` the id, `edited.ts` (or `ts`) the version. Thread replies are not records
  (only their parent's `reply_count` changes); tombstones are absent. A single message is read as the history between
  its ts and itself, inclusive, as Slack documents; an empty answer is a deleted message, while `channel_not_found` or
  `not_in_channel` fail, never a mass deletion. Only `text` is written; blocks, attachments and files are read-only.
- **Change feed.** A pass reads the history with `oldest` (inclusive), newest first, by cursor, keeping the newest ts
  seen; the next pass starts ten seconds before it (Slack promises no visibility order; repeats are dropped by content
  hash: at least once). A tombstone is a `Deleted` change. **Edits and deletions of older messages are invisible to
  polling**: they come as Events API hints (`message_changed`, `message_deleted`) or are caught by reconciliation.
- **Writes.** `create` posts the text with `chat.postMessage` (top level, as the bot; the answered `ts` is the id and
  version). `update` reads and compares the version before `chat.update` (the narrowed race of ADR-0009), then reads the
  message back for its new `edited.ts`, which `chat.update` does not answer. `delete` is `chat.delete`. Bodies are JSON
  with `charset=utf-8`; reads are `GET` with query arguments; the token travels only in the `Authorization` header.
- **Errors.** `ok:false` codes map to the taxonomy: `invalid_auth`, `not_authed`, `token_revoked`, `token_expired`,
  `account_inactive` → `AuthExpired`; `missing_scope`, `not_in_channel`, `cant_update_message`, `cant_delete_message`,
  `is_archived`, `restricted_action*` and similar → `PermissionDenied`; `channel_not_found`, `message_not_found`,
  `thread_not_found` → `NotFound`; `ratelimited` and HTTP 429 → `RateLimited` (`Retry-After`, else one minute);
  `internal_error`, `fatal_error`, `service_unavailable`, `request_timeout`, HTTP 5xx or a body that is not JSON →
  `Transient`; any other code → `Permanent` with the code, `needed`, warnings and messages in details only.
- **Webhooks.** The endpoint's secret is the app's signing secret, pasted at creation. A delivery is valid when the
  timestamp is within five minutes and the `v0=` HMAC matches in constant time. A signed `url_verification` is answered
  as a handshake echoing `challenge`, nothing stored. `event_id` is the provider event id, the same on a retry, so the
  platform drops repeats. A message event points at its message (`message.ts` for an edit, `deleted_ts` for a deletion,
  the parent for a thread reply); other events are kept with no record to fetch. Tests check Slack's published example,
  so `WEBHOOKS` is `TESTED`.
- **No actions.** Nothing in the scope needs one.

## Consequences

- A Slack refusal on the token endpoint is HTTP 200 with `ok:false`: the platform reads it as a token response without
  `access_token` (`Permanent`), not as a refused grant, so a revoked refresh token fails visibly on every call but does
  not mark the connection `NEEDS_REAUTH` by itself. Fixing it belongs in the platform's token client, provider-neutral.
- Polling alone misses edits and deletions of messages older than the last pass; a sync that needs them must subscribe
  the endpoint to `message.channels` / `message.groups` or reconcile.
- Thread replies, direct messages and multi-person DMs are not synced; the bot cannot edit or delete other people's
  messages, and a channel it has not joined is not offered.
- Slack limits `conversations.history` much harder for commercially distributed apps outside the Marketplace; such an
  app's reads are slow, surfacing as `RateLimited` waits, not errors.
- The handshake answer goes out with the platform's default content type; Slack is documented to accept a plain
  challenge body, but no real workspace has confirmed it.
