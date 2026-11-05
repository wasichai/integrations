# ADR-0023: A token answer without a token that names an error is a refused grant

**Status**: accepted · 2026-11-05

## Context

The platform's token client (`TokenClient`) reads every OAuth token request: the code exchange of the OAuth callback,
the refresh before or during a call, and the client-credentials grant. Until now only a `400`/`401` with an `error`
field (RFC 6749 §5.2) was a refused grant (`TokenRefusedException`): a refused refresh marks the connection
`NEEDS_REAUTH`/`FAILING`, a refused code sends the browser back with `error=token_refused`. A `2xx` without
`access_token` was a malformed answer (`Permanent`).

Some providers refuse inside a `200`. Slack answers a refused code or refresh with
`{"ok": false, "error": "invalid_refresh_token"}` ([ADR-0020](0020-slack-connector.md), consequences); GitHub's token
endpoint answers `200` with `{"error": "bad_verification_code", ...}`. So a dead refresh token of such a provider
failed every call as `Permanent` and never asked a person to reconnect
([#66](https://github.com/wasichai/integrations/issues/66)). The core must not know which providers do this (rule 2).

## Decision

- A `2xx` token answer **without** a non-blank `access_token` that carries a non-blank string `error` is a refused
  grant: `TokenRefusedException(error)`, handled exactly like a `400 invalid_grant` on every path (refresh ⇒
  `NEEDS_REAUTH`/`FAILING`, callback ⇒ `error=token_refused`, connect-time ⇒ `400`).
- A `2xx` without `access_token` and without such an `error` stays `Permanent` ("token response without
  access_token"): the answer is malformed, not a refusal.
- A `2xx` **with** an `access_token` is a grant whatever else it carries.
- The `error` field is read the same way for non-`2xx` answers: only a non-blank string counts; a `400`/`401` with any
  other `error` shape falls back to the status mapping (`HttpErrors.classify`).
- No manifest hint. The `error` name is RFC 6749's own field and is enough for every shipped connector; a hint for a
  provider that refuses under another field name waits for that provider (rule 8).

## Consequences

- Slack's refused refreshes and codes, and GitHub's refused codes, behave like any refused grant, with no connector
  code. ADR-0020's consequence about `NEEDS_REAUTH` no longer holds.
- A provider whose `2xx` token answer carries an `error` without a token for a reason that a retry would fix would now
  ask for a reconnect. None of the shipped connectors' documented token endpoints does that: Notion, Salesforce, Jira
  and the OpenAPI connector's OAuth refuse with a `4xx` status, which this decision does not touch; Slack and GitHub
  refuse as above. Their tests do not parse token answers (the core does), so none changes.
- Client misconfiguration reported inside a `200` (Slack's `bad_client_secret`) is a refusal too, as a `400
  invalid_client` already was.
