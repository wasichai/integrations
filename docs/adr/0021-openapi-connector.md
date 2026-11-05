# ADR-0021: The OpenAPI connector maps a document in code, declares only what the mapping provides, and reads YAML

**Status**: accepted · 2026-11-05

## Context

Phase 5 ([#15](https://github.com/wasichai/integrations/issues/15), [#40](https://github.com/wasichai/integrations/issues/40))
asks for a generic OpenAPI connector: an app should expose a REST API as resources without writing a connector. An
OpenAPI 3 document describes operations, parameters, schemas and security schemes, but not what a "record" is, which
operation lists them, how pages continue, or which field says a record changed. The SDK and the platform add
constraints:

- `ManifestValidator` refuses a connector that implements a capability interface it does not declare, or declares one
  it does not implement, and the platform runs only the auth flows `AuthMethod` names, sending an API key in a header.
- A capability is `TESTED` only when tests exercise it (rule 3); a mapping written by an app is not tested here.
- Most published OpenAPI documents are YAML. The SDK brings Jackson 3 (`jackson-databind`), and the version catalog
  already pins the Jackson 3 BOM; YAML needs `jackson-dataformat-yaml` (and its `snakeyaml-engine`).

## Decision

- **Configured in code, per bean.** `OpenApiConfig` holds the connector id, the document (a string the app reads from
  a resource), the security scheme name, an optional base url (else `servers[0].url`, which must be absolute and have no
  variables) and an explicit `OpenApiResource` per resource: list and get `operationId`s, optional create, update and
  delete, the id field, where the list keeps its items, the pagination style, and the updated-at field plus the
  updated-since query parameter for incremental reads. Nothing is inferred from naming conventions. Tenants never
  upload a document: the connector runs in-process with the app's trust (ADR-0003).
- **Checked at creation.** `OpenApiConnector.of(config)` checks the mapping against the document (operations exist;
  list takes no path parameter and get, update and delete exactly one, the id; every query parameter the mapping
  sends is declared and every required one is sent; the id and updated-at fields exist with the right type; create and
  update take a JSON body; refs resolve) and throws one `IllegalArgumentException` naming every problem, so the
  application fails to start instead of a sync failing later.
- **Only what the mapping provides.** `DISCOVERY` and `READ` always; `CHANGE_FEED` when a resource has an
  updated-since parameter; `WRITE` when a resource maps a create, update or delete. The validator checks interfaces,
  so `of` returns one of four small classes that delegate to one engine and implement exactly those interfaces. No
  `AUTHENTICATION` (an OpenAPI document does not say which operation names the account), no webhooks, no actions,
  no upsert. Within `WRITE`, a resource without a mapped operation refuses it with `Permanent`, as GitHub refuses
  deletes (ADR-0019).
- **Maturity is the app's to raise.** Capabilities are `IMPLEMENTED` by default (`OpenApiConfig.maturity`): the
  connector's code is tested here against a petstore document and its stub, a given mapping is not. An app sets
  `TESTED` once its own tests cover its mapping with the contract kit.
- **Auth from `securitySchemes`.** `apiKey` in a header becomes `ApiKey(name, prefix = null)`, `http` bearer
  `ApiKey("Authorization", "Bearer ")`, `oauth2` with an `authorizationCode` flow `OAuth2AuthorizationCode` (scopes from
  the flow unless the config narrows them, PKCE on unless turned off). API keys in a query or cookie, HTTP basic,
  other OAuth flows and OpenID Connect are refused: the platform cannot send the first, and the others have no user
  yet.
- **Schemas.** Fields come from the get operation's 2xx JSON response schema, else from the list's item schema; local
  `$ref`s (`#/...`) and `allOf` are resolved, external refs refused. `string` maps to `TEXT`, with `enum` to `CHOICE`,
  `date` to `DATE`, `date-time` to `DATETIME`; `integer` and `number` to `NUMBER`; `boolean` to `BOOLEAN`; an array of
  string enums to `MULTI_CHOICE`; anything else (nested objects, other arrays, `oneOf`/`anyOf`) to `JSON`. OpenAPI 3.1
  type arrays use their non-null type. The id field and `readOnly` properties are read-only; `required` comes from the
  create body. A value that does not fit its type is read as `JSON`, never dropped; a write that does not fit is
  refused before any call.
- **Pagination.** `None`; `Page` (page number plus an optional limit; a page shorter than the limit, or an empty one,
  ends the list); `Cursor` (a dotted response field into a query parameter); `NextUrl` (a dotted response field or the
  `Link` header). A next url is followed only under the base url: the platform adds the credentials to any url.
- **Change feed.** A pass pages through every record updated at or after its `since`, which stays fixed for the pass;
  when it ends, the next `since` is the newest updated-at seen. The boundary instant is read again (at least once; the
  engine drops repeats by content hash). The updated-since parameter must therefore be inclusive; an exclusive one
  could miss an edit in the same instant. A record edited during a pass can shift pages (reconciliation catches it).
  Deletions are not reported.
- **Writes.** Create sends the given fields with the operation's JSON media type and needs the created record in the
  answer (no id, no guess: a retry could create a second one). An update with an expected version reads and compares
  the updated-at field first, the narrowed race of ADR-0009; a 204 answer gives no new version. Deleting a record that
  is already gone (404/410) is done.
- **Errors by status only**, since a document does not say what an error body means: 401 `AuthExpired`, 403
  `PermissionDenied`, 404/410 `NotFound`, 409/412 `Conflict`, 429 `RateLimited` with `Retry-After`, 5xx `Transient`,
  any other 4xx `Permanent` with the body (first 2,000 characters) in `details` only.
- **YAML.** `jackson-dataformat-yaml`, versioned by the Jackson 3 BOM the catalog already pins, is an `implementation`
  dependency of this module only. A document starting with `{` is read as JSON, anything else as YAML; Swagger 2.0 is
  refused.

## Consequences

- An app gets reads, a change feed and writes for a well-shaped REST API with a few lines of config, and a misnamed
  operation or parameter stops the start with a message naming it.
- Nested resources (`/owners/{ownerId}/pets`), composite ids, query-parameter or cookie API keys, provider error
  codes, webhooks and account lookup need a connector of their own.
- A 404 from the get operation reads as a deleted record: the document cannot tell a missing record from a wrong path,
  so the mapping must be right (the start-up check covers names, not behaviour).
- `snakeyaml-engine` joins the classpath of apps that use this connector, and only theirs.
