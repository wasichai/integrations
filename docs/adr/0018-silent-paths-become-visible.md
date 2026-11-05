# ADR-0018: Deletions that cannot propagate, unchecked accounts and unreadable credentials are made visible

**Status**: accepted · 2026-11-05 · refines [ADR-0005](0005-secret-management.md) and
[ADR-0015](0015-sync-implementation-decisions.md), which stay as they are

## Context

[#60](https://github.com/wasichai/integrations/issues/60) found paths where the platform gave up without telling anyone:

- A deletion under `deletePolicy: PROPAGATE` that the direction does not let flow (a record deleted in B of an `A_TO_B`
  definition) left the link `ORPHANED`, counted as skipped, with no conflict and no log line.
- A connector without an `Authenticator` stores credentials unchecked, its test always succeeded, and with no
  `providerAccountId` the same account could be connected twice; nothing in the API said so.
- A sealed secret no configured key opens threw `CredentialUnreadableException`, which nothing mapped: an unexpected
  `500` on API calls, and a sync job retried as if the failure were transient.

## Decision

- **A `PROPAGATE` deletion that may not flow is handled as `FLAG`.** The link becomes `ORPHANED`, a `DELETED` conflict
  is opened and counted in the run's `conflicts`, and a warning is logged. The definition asked for deletions to
  travel; this one cannot, so a person decides, the same way and with the same resolution (unlink) as under `FLAG`. A
  new conflict kind was rejected: what happened (a linked record is gone) and what resolving does are the same.
  `IGNORE` stays silent by choice: the user asked for it.
- **No new provider checks are invented.** `ConnectionView` and `ConnectionTestView` gain `accountChecked` (additive):
  `false` when the connector cannot ask who the account is. A test opens the stored credentials for every connector,
  so `ok: true, accountChecked: false` means that they can be read and nothing more.
- **Unreadable credentials are an authorization problem.** Wherever a connection's credentials are opened (every call,
  a refresh, a test) a `CredentialUnreadableException` marks the connection `NEEDS_REAUTH`/`FAILING` with `lastError`
  "credentials cannot be decrypted with the configured keys; reconnect" and becomes `ConnectorException.AuthExpired`,
  which already maps to a test with `ok: false`, a `409` on API calls and a permanent job failure. The key id is
  logged, never answered.
- **Health stays driven by tests, refreshes and credentials.** A failed sync run does not change a connection's
  status or health; a run fails for reasons that are not the connection's (a mapping, one record), and its error is on
  the run and the definition's status. Provider config `options` stay stored and answered for the app's own use; no
  connector reads them, and they are documented as such rather than removed.

## Consequences

- A one-way `PROPAGATE` definition can now gather `DELETED` conflicts that were silent before.
- A connection whose key was removed must be connected again; one with `accountChecked: false` has no account to
  reconnect in place, so it is deleted and connected anew.
- Unreadable provider app secrets and webhook endpoint secrets are not covered here: they are saved again, not
  reconnected.
