# ADR-0016: Jira finds its site on use, writes rich text as plain paragraphs, and claims webhooks only as implemented

**Status**: accepted · 2026-11-04

## Context

Jira Cloud ([#37](https://github.com/wasichai/integrations/issues/37)) differs from Notion and Salesforce in three
ways that the SDK does not cover directly:

- An OAuth 2.0 (3LO) token works only through `https://api.atlassian.com/ex/jira/{cloudId}`. The token response names
  no site; `GET /oauth/token/accessible-resources` lists every site the user granted the app, possibly several, and
  `Authenticator.connectionMetadata` sees only the token response, without http. `AccountInfo.metadata` is not stored.
  An API token connection (Basic, email + token) talks to the site itself, and a Basic connection stores no metadata.
- Rich text fields in REST API v3 are Atlassian Document Format (ADF) trees; the SDK has text, not documents.
- Atlassian documents that a dynamic webhook of an OAuth 2.0 app carries a JWT signed with the app's client secret,
  but not its claims, and publishes no signed sample delivery. Admin webhooks with a secret sign the raw body
  (`X-Hub-Signature: sha256=<hex>`).

## Decision

- **Site.** `connectionMetadata` stores only `route=gateway` for OAuth connections. The cloud id is resolved from
  accessible-resources on first use and cached per connection: the site pinned by `JiraConfig.cloudId`, else the only
  site with Jira scopes; several unpinned sites are refused, never guessed. The account is the cloud id. API token
  connections (no `route`) use `JiraConfig.siteUrl`; the account is that url. The SDK and the core stay unchanged: a
  per-connection site choice needs a platform change, made when a second connector needs post-connect metadata.
- **Rich text.** ADF is read as plain text (one line per block, text of every node) and written as one paragraph per
  line. Marks, lists, tables and media are lost when the platform writes such a field; the manifest description says
  so. Jira has no conditional edit, so `update` compares `updated` before writing, the same narrowed race as Notion
  (ADR-0009).
- **Webhooks.** A delivery is verified with the endpoint's secret (the admin pastes the OAuth client secret or the
  admin webhook's secret): `X-Hub-Signature` HMAC when present, else a bearer JWT whose `HS256` signature is checked
  and whose `exp`, when present, must be in the future. No other claim is checked because none is documented. Our tests
  sign their own deliveries, so `WEBHOOKS` is `IMPLEMENTED`, not `TESTED`. Deliveries are hints: the issue is read
  again, payload order is never trusted. Registering, refreshing (they expire after 30 days) and deleting dynamic
  webhooks are actions, as are transitions.

## Consequences

- One deployment serves one Jira site per API token configuration, and users whose grant reaches several sites need
  a pinned cloud id.
- Mapping a rich text field as a target drops its formatting; mapping it as a source is lossless for the text.
- `WEBHOOKS` moves to `TESTED` once a delivery captured from a real app backs the tests.
