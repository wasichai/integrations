# ADR-0019: GitHub connects by user token only, polls issues by fresh queries, and refuses deletes

**Status**: accepted · 2026-11-05

## Context

GitHub ([#38](https://github.com/wasichai/integrations/issues/38)) is reached through REST API v3 on github.com or a
GitHub Enterprise Server (GHES), and differs from the shipped connectors in ways the SDK does not settle:

- GitHub offers three kinds of credentials: OAuth app tokens and GitHub App user tokens (both from the web flow at
  `/login/oauth/authorize`), personal access tokens, and GitHub App installation tokens. An installation token is
  minted by signing an RS256 JWT with the app's private key and posting it to `/app/installations/{id}/access_tokens`.
  The platform runs only the flows `AuthMethod` names, and a connector never holds a secret (ADR-0005).
- The issues API pages by page number with a `Link` header and filters by `since` on `updated_at` (second precision).
  Sorted by `updated`, an issue edited while a pass pages moves to the end and shifts every later page: a pass that
  follows `Link` can skip an issue it never sees again.
- The issues API also returns pull requests. The REST API cannot delete an issue (only GraphQL's `deleteIssue`, for
  repository admins). GitHub has no conditional `PATCH`.
- GitHub documents its webhook signature (`X-Hub-Signature-256`) with a published secret, payload and signature.

## Decision

- **Auth.** `OAuth2AuthorizationCode` on the configured web host (scope `repo` by default, PKCE on: GitHub documents it,
  and a server without it ignores the extra parameters; no revocation url, GitHub's is not RFC 7009) covers OAuth apps
  and GitHub App user tokens, whose expiry and refresh token the platform already handles. Personal access tokens,
  classic or fine-grained, are an `ApiKey` sent as `Authorization: Bearer`. **Installation tokens are not supported**:
  they need a private-key JWT flow in the platform, added when a connector needs it, not guessed here. The account is
  the user id (a login can be renamed).
- **Host.** `GitHubConfig(webUrl, apiUrl)` names one host per connector bean, `https://ghe.example.com` and
  `https://ghe.example.com/api/v3` for GHES, as Jira's `siteUrl` does (ADR-0016). A GHES on a private network also needs
  `wasichai.integrations.http.allow-private-addresses`. A `Link` url or a list cursor is followed only when it is under
  the configured api: the platform adds the token to any url a connector asks for.
- **Resources and records.** A repository (`owner/name`) is the resource, its issues the records, the issue number the
  record id, `updated_at` the version. Repositories with issues disabled are left out of discovery. Pull requests are
  left out of reads and the feed. Reads treat 404 and 410 as a deleted issue only after checking the repository is still
  reachable (lost access is an error, never a mass deletion), and 301 (transferred) as gone.
- **Change feed.** Every call is a fresh query: `since` = the newest `updated_at` seen minus one second, oldest first.
  The boundary second is read again (at least once; the engine drops repeats by content hash) and an issue edited
  meanwhile is found by a later query instead of shifting pages. Only when a whole page sits within one second does
  `since` not move; then the pass pages by number, where a concurrent edit can still shift a page (accepted: it needs a
  hundred issues edited in one second plus an edit during the pass; reconciliation catches it).
- **Writes.** `create` posts the issue and, when `state` or `state_reason` is given, edits it right after (GitHub takes
  them on edit only); if that edit fails, the error names the created number. `update` reads and compares `updated_at`
  before `PATCH`: the narrowed race of ADR-0009, wider here because two edits within one second share a version.
  `delete` is refused with `Permanent`, never faked by closing: a sync with `PROPAGATE` deletes fails visibly.
- **Webhooks.** `X-Hub-Signature-256` HMAC with the endpoint's secret, compared in constant time; the SHA-1 header is
  not accepted. A signed `ping` is answered as a handshake, not stored. `X-GitHub-Delivery` is the provider event id,
  the same on a redelivery, so the platform drops repeats. Deliveries are hints; those about pull requests or anything
  but an issue are kept with no record to fetch. Tests check GitHub's published sample, so `WEBHOOKS` is `TESTED`.
- **Rate limits.** 403 or 429 is `RateLimited` when `retry-after` is present (that wait), when
  `x-ratelimit-remaining` is `0` (until `x-ratelimit-reset`), or when the message names a rate limit (one minute, as
  GitHub advises); any other 403 is `PermissionDenied`. The manifest hint is 1.3 requests per second (5,000 an hour).

## Consequences

- An organization that only allows GitHub Apps with installation tokens cannot connect yet.
- One deployment serves one GitHub host; github.com and a GHES side by side need a platform change.
- GitHub drops labels, assignees and milestones silently when the user lacks push access; the next read shows what
  was stored, the connector cannot tell.
- Mapping a GitHub issue as a delete target fails rather than closing it; closing stays an explicit `state` write.
