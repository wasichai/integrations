# User guide

For the people who connect accounts and set up synchronizations in an app that includes Wasichai Integrations and the
React packages of [wasichai/integrations-ui](https://github.com/wasichai/integrations-ui) (`@wasichai/integrations` and
`@wasichai/integration-sync`). The screens are described in general terms; every action maps to a REST call in
[api.md](../architecture/api.md), which an app without the UI can use directly.

## Who can do what

Any member of the organization can browse connectors, connections, syncs, runs, events, conflicts and the health page.
Registering provider apps, connecting and deleting accounts, testing connections, managing webhooks, creating and
running syncs, resolving conflicts, replaying events and retrying jobs need an administrator (`ADMIN`) or the
`MANAGE_ORGANIZATION` permission. The UI hides those actions from other members; the server refuses them anyway.

## 1. Find a connector

The **Connectors** page lists what the app has installed (connectors are installed on the server, not from the UI).
Each connector shows its capabilities with an honest maturity label: **Tested** (implemented and tested against a
provider stub), **Implemented** (works, no contract tests yet), **Declared** (announced, not available yet). Today the
installable connectors are Notion and Salesforce; others are planned, not available.

A connector's page shows its overview (version, documentation, authentication methods, rate limit, webhooks, required
provider permissions, resources), its capabilities, and, for connectors that sign in with OAuth, its provider app.

## 2. Register the provider app (OAuth connectors)

Notion and Salesforce sign in with OAuth, which needs your organization's own app at the provider:

1. At the provider, create the app (a Notion public integration, a Salesforce connected app) and set its redirect uri
   to the one your administrator gives you: `<app public url>/api/integrations/oauth/callback`.
2. On the connector's **Provider app** section, enter the app's client id and client secret, and extra scopes if you
   need more than the connector's defaults. The secret is write-only: it is never shown again; leave it empty to keep
   the stored one.

Until the provider app is registered, the catalog marks the connector "App not registered" and OAuth connect is not
offered. Deleting a provider app is refused while connections to that connector exist.

## 3. Connect an account

Choose **Connect** on the connector's page and pick a method:

- **OAuth (sign in at the provider)**: you are sent to the provider to approve access, then back to the **Connections**
  page, which says whether it worked. Errors such as "authorization expired or already used" mean starting again from
  the connector; "the provider did not accept the authorization code" usually means a wrong client id or secret in the
  provider app.
- **API key** or **Username and password**: entered in the dialog and checked against the provider before anything is
  stored (when the connector can identify the account). Notion accepts an internal integration token as an API key.
- **OAuth (client credentials)**, for connectors that offer it: uses the provider app; no browser redirect.

Credentials are stored encrypted and never shown again. Connecting the same provider account again updates the existing
connection instead of adding a second one, which is also how you repair a connection that "needs reconnecting".

## 4. Look at a connection

**Connections** lists every connection with its status (Active, Needs reconnecting, Disabled, Error) and health (No
data, Healthy, Degraded, Failing). **Test** calls the provider and updates both. **Delete** revokes access at the
provider when it can, and is refused while a sync uses the connection: delete or change those syncs first.

A connection's page has:

- **Resources**: what the provider offers (a Notion data source, a Salesforce object) and, for one you pick, its fields
  with type, required, read-only, external id and options. These are the names you map in a sync.
- **Webhooks**: endpoints that let the provider push changes. Create one (with the signing secret the provider shows,
  if it shows one), copy its URL into the provider's webhook settings. For Notion, the provider then sends a
  verification token: reveal it here and paste it back in Notion. An endpoint is "Awaiting verification" until its first
  accepted delivery.
- **Events**: what arrived through webhooks, newest first, with its status (Received, Processed, Ignored, Failed) and
  payload. **Replay** processes an event again, which is safe.

## 5. Map: create a sync

**Syncs** → **New sync** opens a wizard that saves a draft:

1. **General**: a name, the direction (A to B, B to A, both ways), what happens when a record is deleted (flag as a
   conflict, ignore, delete on the other side too), and optionally how often to run (at least 60 s) and to reconcile (at
   least 300 s). Without a schedule a sync runs on events and by hand.
2. **Side A** and **Side B**: a connection and a resource on each.
3. **Pair existing records** (optional): a field on each side whose equal values mean "the same record", so the first
   run links existing records instead of creating duplicates.
4. **Fields**: each pair of fields that travels, **who writes it** (both, only A, only B), **when both change** (a
   person decides, the side that changed wins, A wins, B wins, last change wins) and, under advanced transforms, how the
   value is converted (copy, fixed value, default value, option equivalence, date and time, number, conditional).
   Nothing is mapped by name automatically.

**Save draft** checks the shape; **Validate** checks the mapping against both systems' current fields (that they exist,
are writable where written, and fit through the transform). See the [sync guide](sync.md) for every rule.

Some settings exist only in the API so far, such as `externalIdField`, which makes interrupted creates recoverable
([sync guide](sync.md#create-modes-and-externalidfield)).

## 6. Activate and run

On the sync's page, **Activate** validates once more and starts it; an invalid sync stays a draft and shows why.
Editing an active sync sends it back to draft. **Pause** stops it without forgetting anything.

**Run now** queues an incremental run (what changed since the last one); **Reconcile** reads both sides whole to catch
anything that slipped through (missed webhooks, deletions, drift). Only one run of a sync is queued or running at a time.
The first run of a sync reads everything.

## 7. Monitor

- The sync's page shows its status, the open conflicts, and the **Runs** table: status (Queued, Running, Done, Partial,
  Failed), trigger (Manual, Scheduled, Event, Initial, Reconcile), when it ran, and counts (read, created, updated,
  unchanged, conflicts, failed). **Partial** means some records failed on their own while the rest went through.
- **Health** shows connections with problems (including a pause after the provider's rate limit) and the work queue by
  status; jobs that used up their attempts ("Dead") can be retried there once the cause is fixed. A permanent error is
  marked as such: it fails the same way until fixed.

When a run fails because a connection needs reconnecting, reconnect the account (step 3), then run again.

## 8. Resolve conflicts

A conflict is never settled silently. **Conflicts** lists them (open or resolved); each shows the sync, the fields and
when it was detected. **Resolve** opens a dialog:

- for fields changed on both sides: for each field, keep A's value, B's value or type another value; the dialog shows
  what both sides last agreed on. The chosen values are written to both sides and the pair syncs again.
- for a record deleted on one side: **Stop pairing** forgets the pair; nothing is deleted or recreated by it.

A record that changed again while you were resolving makes the server refuse the resolution: look at it again.
