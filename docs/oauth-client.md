# MCP client authorization (OAuth 2.1) reference

This is a working reference for the client side of MCP authorization as the
official conformance runner's client leg tests it, written to guide an OAuth
client in presa (joshrotenberg/presa#8). `presa.auth.client` now implements
it, including DPoP and the JWT-bearer and enterprise-managed grants; the
[authorization guide](guides/authorization.md) describes what it does. It assumes `docs/wire-2026-07-28.md`
(cited as `wire §N`) and does not repeat it: request `_meta`, HTTP headers,
`server/discover`, and how the runner launches the client (`wire §19.2`) are
described there.

No copy of the MCP authorization specification prose was available. Every
fact below cites one of these sources:

| Tag | Source |
|---|---|
| `runner` | `@modelcontextprotocol/conformance@0.2.0-alpha.12`, `snodo:conformance/node_modules/@modelcontextprotocol/conformance/dist/index.js`. Cited by scenario and check id, or by minified helper name (list below) |
| `sdk` | `snodo:conformance/node_modules/@modelcontextprotocol/sdk/dist/esm/server/auth/middleware/bearerAuth.js` (SDK 1.32.1), the `requireBearerAuth` the runner imports as `he` |
| `oc` | snodo `integrations/oauth/lib/snodo/oauth/client.ex` (`Snodo.OAuth.Client`) |
| `oc:<file>` | `snodo:integrations/oauth/lib/snodo/oauth/client/<file>` |
| `harness` | `snodo:conformance/client.exs` |
| `results` | `snodo:conformance/results/2026-10-22-client-alpha.12-checks.json`, snodo's observed checks |
| `snodo:<path>` | another file under `~/Code/github.com/joshrotenberg/snodo/` |
| `presa:<path>` | a file in this repository |

Runner helpers referenced by name: `J` (the mock authorization server), `X`
(the mock MCP resource server), `Y` (its token verifier), `Z` (an express
listener on `http://localhost:<port>`), `Qa` (the RFC 8707 resource checks),
`eo`/`to` (the four `metadata-*` scenarios), `as` (the iss rejection
verdict), `mo` (token endpoint auth method detection), `It` (the 2026-07-28
request gate, `wire §2`), `Ac` (spawns the client), `Nc` (runs one scenario),
`Pc` (scores it).

The runner is the strongest source for what is scored; snodo is the only
source for client behavior the runner leaves open. snodo passes every required
`auth/*` scenario on alpha.12 in both lanes (`snodo:conformance/README.md:246-256`,
`:298-302`). RFC section numbers below are the ones the runner's
`specReferences` name (runner constant `q`), unless marked otherwise.

## 1. What is scored

The 2026-07-28 requirements list 25 `auth/*` client scenarios
(`snodo:conformance/requirements/2026-07-28.yaml:80-104`); 2025-11-25 lists
the first 14 of them (`requirements/2025-11-25.yaml:80-93`):

| Scenario | 2025-11-25 | 2026-07-28 | Client may exit non-zero |
|---|---|---|---|
| `auth/metadata-default`, `-var1`, `-var2`, `-var3` | yes | yes | no |
| `auth/basic-cimd` | yes | yes | no |
| `auth/scope-from-www-authenticate`, `-from-scopes-supported`, `-omitted-when-undefined`, `-step-up` | yes | yes | no |
| `auth/scope-retry-limit` | yes | yes | yes |
| `auth/token-endpoint-auth-basic`, `-post`, `-none` | yes | yes | no |
| `auth/pre-registration` | yes | yes | no |
| `auth/resource-mismatch` | no | yes | yes |
| `auth/offline-access-scope`, `-not-supported` | no | yes | no |
| `auth/authorization-server-migration` | no | yes | no |
| `auth/iss-supported`, `iss-not-advertised` | no | yes | no |
| `auth/iss-supported-missing`, `iss-wrong-issuer`, `iss-unexpected`, `iss-normalized` | no | yes | yes |
| `auth/metadata-issuer-mismatch` | no | yes | yes |

The 2026-07-28-only scenarios have `source.introducedIn: "2026-07-28"`
(runner constant `A`), so they are not applicable at 2025-11-25. The last
column is the scenario's `allowClientError` flag.

A scenario fails when any check is FAILURE or WARNING, when the client times
out (default 30000 ms), or when the client exits non-zero and the scenario
does not set `allowClientError` (runner `Pc`: `o>0||s>0||c||l&&!r`). A
WARNING therefore fails a scenario as surely as a FAILURE; several scope
checks report a missed SHOULD as WARNING. INFO checks never fail one.

Unscored extension scenarios run in both lanes (section 6).

## 2. The runner's servers

Each scenario starts its own listeners on `http://localhost:<port>` (runner
`Z.start`, `listen(0)`); the client is given the MCP URL
`http://localhost:<mcp>/mcp`. Examples below use `<mcp>` = 50001 and
`<as>` = 50002.

**MCP resource server** (runner `X`):

- `POST /mcp` only. Every POST passes the auth middleware first. The default
  middleware is the SDK's `requireBearerAuth` (`he`) with the scenario's
  `requiredScopes` (only when `includeScopeInWwwAuth`) and, when the PRM is in
  the challenge, `resourceMetadataUrl` (runner `X`). Scenarios with custom
  middleware (`scope-step-up`, `scope-retry-limit`,
  `authorization-server-migration`) let `initialize` and `notifications/*`
  through without a token; the default middleware does not, so on the
  2025-11-25 lane `initialize` itself gets the 401.
- At 2026-07-28 an authenticated request goes to the stateless gate `It`
  (`wire §2`), which answers `server/discover` with `capabilities: {"tools":
  {}}`; the route answers `tools/list` with one tool `test-tool`
  (`inputSchema: {"type": "object"}`), `tools/call` of it with
  `content: [{"type": "text", "text": "test"}]`, and everything else 404
  -32601. At 2025-11-25 it is an SDK `McpServer` with the same tool over a
  stateless streamable HTTP transport.
- `GET <prmPath>` serves the protected resource metadata (default path
  `/.well-known/oauth-protected-resource/mcp`):
  `{"resource": R, "authorization_servers": [AS]}` plus `scopes_supported`
  when configured. `R` is `http://localhost:<mcp>/mcp`, or
  `http://localhost:<mcp>` (no path, no trailing slash) when `prmPath` is the
  root `/.well-known/oauth-protected-resource`, or the scenario's
  `prmResourceOverride` (runner `X`).

**Token verifier** (runner `Y`): a token is valid when it starts with
`test-token` or `cc-token`; its scopes are those registered by the
authorization server when it was minted. Each accepted request emits
`valid-bearer-token`; any other token emits `invalid-bearer-token` (FAILURE)
and a 401.

**Authorization server** (runner `J`), with options per scenario:

- `GET <metadataPath>` (default `/.well-known/oauth-authorization-server`)
  emits `authorization-server-metadata` and returns:

```json
{
  "issuer": "http://localhost:50002",
  "authorization_endpoint": "http://localhost:50002/authorize",
  "token_endpoint": "http://localhost:50002/token",
  "registration_endpoint": "http://localhost:50002/register",
  "response_types_supported": ["code"],
  "grant_types_supported": ["authorization_code", "refresh_token"],
  "code_challenge_methods_supported": ["S256"],
  "authorization_response_iss_parameter_supported": true,
  "token_endpoint_auth_methods_supported": ["none"]
}
```

  `registration_endpoint` is dropped with `disableDynamicRegistration`;
  `scopes_supported` and `client_id_metadata_document_supported` appear only
  when configured; the iss flag is dropped when `issParameterSupported` is
  null. With a `routePrefix` (for example `/tenant1`) the issuer and the three
  endpoints gain the prefix. With `isOpenIdConfiguration` it adds `jwks_uri`,
  `subject_types_supported`, `id_token_signing_alg_values_supported`. The
  issuer is `metadataIssuer` when set, else `<base><routePrefix>` (runner
  `J`, `ae()`).
- `GET <prefix>/authorize` records the query, emits `authorization-request`,
  `pkce-code-challenge-sent`, `pkce-s256-method-used`, then answers **302**
  to `redirect_uri` with `code=test-auth-code`, the `state` echoed if sent,
  and `iss` per `issInRedirect`: `correct` (the issuer, default), `wrong`
  (`https://evil.example.com`), `normalized` (issuer plus `/`), or `omit`. It
  parses `redirect_uri` with `new URL(...)`, so `redirect_uri` must be an
  absolute URL (runner `J`).
- `POST <prefix>/token` (form or JSON body) emits `token-request` and, for
  `authorization_code`, `pkce-code-verifier-sent` and
  `pkce-verifier-matches-challenge`. It does not check `code`,
  `redirect_uri`, or `client_id`; scenario hooks may check client auth. It
  answers `{"access_token": "test-token-<ms>", "token_type": "Bearer",
  "expires_in": 3600, "scope": "..."}` with `scope` only when non-empty. The
  token's scopes are the `scope` of the last authorization request unless a
  hook says otherwise. **No scenario issues a refresh token** (runner `J`).
- `POST <prefix>/register` emits `client-registration` and, at 2026-07-28
  only, `sep-837-application-type-present`; answers 201
  `{"client_id": "test-client-id", "client_secret": "test-client-secret",
  "client_name": ..., "redirect_uris": [...]}` plus
  `token_endpoint_auth_method` when the scenario's hook sets one; hooks may
  change the id and drop the secret (runner `J`).

## 3. The flow end to end

### 3.1 The unauthenticated request and the 401

The client's first MCP request (`server/discover` at 2026-07-28, `initialize`
at 2025-11-25) goes out without `Authorization`. The answer:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json
WWW-Authenticate: Bearer error="invalid_token", error_description="Missing Authorization header", resource_metadata="http://localhost:50001/.well-known/oauth-protected-resource/mcp"

{"error": "invalid_token", "error_description": "Missing Authorization header"}
```

Header shapes the runner sends:

| Source | Header |
|---|---|
| `sdk` default | `Bearer error="invalid_token", error_description="<msg>"`, then `, scope="<required scopes>"` when the scenario puts scope in the challenge, then `, resource_metadata="<url>"` when it puts the PRM there |
| `scope-step-up`, `scope-retry-limit` 401 | `Bearer scope="<s>", resource_metadata="<url>"` |
| `scope-step-up` 403 | `Bearer scope="mcp:write", resource_metadata="<url>", error="insufficient_scope"` |
| `scope-retry-limit` 403 | `Bearer error="insufficient_scope", scope="mcp:admin", resource_metadata="<url>", error_description="Scope upgrade will never succeed"` |
| `authorization-server-migration` 401 | `Bearer scope="mcp:basic", resource_metadata="<url>"` |
| `dpop`, `dpop-nonce` 401 | `DPoP resource_metadata="<url>"` (or `DPoP error="use_dpop_nonce", ...`) |

So parameter order varies, `scope` may come first, `error` may come last, and
the body is a non-JSON-RPC object. A parser must read RFC 7235 auth-params
in any order with quoted-string values. snodo's `Snodo.Client.Challenge`
lowercases scheme and parameter names, keeps a repeated parameter's first
value, splits `scope` on spaces, and picks the `bearer` challenge, else the
first one (`snodo:lib/snodo/client/challenge.ex:1-28`, `:75-84`).

A 401 always goes to the token provider; a 403 only when its challenge
`error` is `insufficient_scope`; any other status is the response
(`snodo:lib/snodo/client/http.ex:879-890`).

### 3.2 Protected resource metadata discovery (RFC 9728)

Order, as snodo implements it (`oc:discovery.ex:48-55`, `:109-126`):

1. The challenge's `resource_metadata` URL, if present. Only that URL is
   fetched, whatever its path (`metadata-var3` uses
   `/custom/metadata/location.json`).
2. Else the path-based well-known URL: origin +
   `/.well-known/oauth-protected-resource` + the server path without a
   trailing slash (`http://localhost:50001/.well-known/oauth-protected-resource/mcp`).
3. Else the root `/.well-known/oauth-protected-resource`. For a server URL
   with no path, only this one.

The first 200 with a JSON object body wins; any other status (or a non-JSON
body) moves on; a transport error stops (`oc:discovery.ex:154-163`).

What the runner enforces about order (runner `eo`, `to`):

| Scenario | PRM location | In challenge | AS metadata location | Trap |
|---|---|---|---|---|
| `metadata-default` | `/.well-known/oauth-protected-resource/mcp` | yes | `/.well-known/oauth-authorization-server` | GET of root PRM emits `prm-priority-order` FAILURE |
| `metadata-var1` | `/.well-known/oauth-protected-resource/mcp` | no | `/.well-known/openid-configuration` | none |
| `metadata-var2` | `/.well-known/oauth-protected-resource` | no | `/.well-known/oauth-authorization-server/tenant1` (issuer `.../tenant1`) | GET of root `/.well-known/oauth-authorization-server` emits `authorization-server-metadata-wrong-path` FAILURE |
| `metadata-var3` | `/custom/metadata/location.json` | yes | `/tenant1/.well-known/openid-configuration` (issuer `.../tenant1`) | same as var2 |

So the header URL must be used when present (default), the path-based URL
must be tried when it is absent (var1), the root URL must be the fallback
(var2), and for an issuer with a path the root AS metadata URL must never be
requested (var2, var3). Requesting the path-based PRM before the root one is
the RFC 9728 order; var2 does not itself penalize the reverse order (the
path-based URL is just a 404 there).

**Resource check.** The PRM `resource` must name this server before any
authorization starts (`resource-mismatch`, RFC 8707 and RFC 9728 section 3.3
via `q.RFC_9728_RESOURCE_IDENTITY`). snodo accepts it when scheme, host, and
port match (case-insensitive) and its path is `/`, equals the server path, or
is a prefix of it at a segment boundary; otherwise
`{:resource_mismatch, requested, configured}` and the flow stops
(`oc:discovery.ex:89-102`, `:165-171`). `metadata-var2` needs that prefix
rule or an equivalent: the server is `http://localhost:50001/mcp` and the
PRM's resource is `http://localhost:50001`.

**The resource the client sends** in the authorization and token requests is
the PRM's `resource` value verbatim (`oc:flow.ex:54-58`, `:342`, `:505`). The
runner's `resource-parameter-matches-prm` compares strings exactly and its
failure text names the usual cause: a URL parser that appends `/` to a
pathless identifier (runner `Qa`). In var2 the value is
`http://localhost:50001`; `http://localhost:50001/` fails. presa's
`presa.auth/canonical-resource` already removes a lone trailing slash
(`presa:src/presa/auth.clj:18-33`) but the safest rule is not to rewrite the
PRM value at all.

### 3.3 Authorization server metadata discovery (RFC 8414, OIDC)

The issuer is the first entry of `authorization_servers`
(`oc:discovery.ex:81-82`). URLs, in order (`oc:discovery.ex:128-150`; the
runner's own AS-side scenario builds the same three, runner
`createWellKnownUrl`):

| Issuer | URLs tried |
|---|---|
| `http://localhost:50002` (no path) | `/.well-known/oauth-authorization-server`, then `/.well-known/openid-configuration` |
| `http://localhost:50002/tenant1` | `/.well-known/oauth-authorization-server/tenant1`, then `/.well-known/openid-configuration/tenant1`, then `/tenant1/.well-known/openid-configuration` |

A trailing slash on the issuer path is trimmed before building; a query or
fragment makes the issuer invalid (`oc:discovery.ex:128-150`).

Checks on the document, in snodo's order:

1. `issuer` must equal the issuer used to build the URL, exact string
   comparison with no normalization (RFC 8414 section 3.3); otherwise
   `{:issuer_mismatch, expected, found}` (`oc:discovery.ex:173-176`).
   `metadata-issuer-mismatch` serves a document whose issuer is
   `https://attacker.example.com` with endpoints under `/mismatched-as`; the
   client must not register, authorize, or request a token
   (`sep-2468-client-validate-metadata-issuer`).
2. `token_endpoint` must be present (`oc:discovery.ex:178-183`).
3. For the code flow, `code_challenge_methods_supported` must list `S256`,
   checked before registering or authorizing (`oc:flow.ex:176-184`). Every
   runner AS lists it, so this is untested.

Discovery runs again on every authorization, so a changed
`authorization_servers` is noticed (`oc:discovery.ex:7-9`); section 3.12
depends on that.

### 3.4 Client identification

snodo's order (`oc:flow.ex:189-215`):

1. **Client ID metadata document** when the AS metadata has
   `client_id_metadata_document_supported: true` and the client has a
   metadata URL: `client_id` is that `https` URL, no secret, auth method
   `none`. `basic-cimd` requires the exact value
   `https://conformance-test.local/client-metadata.json` (runner constant
   `io`); any other client ID is a WARNING. The context does not carry it, so
   the harness must hard-code it (`harness:42`). The runner never fetches it
   except in `offline-access-scope`'s teardown, where the fetch fails and the
   check is INFO.
2. **Pre-registered** `client_id`/`client_secret` from configuration
   (`pre-registration`, section 4).
3. A **stored registration** for this issuer, reused only while its
   `redirect_uri` still matches (`oc:flow.ex:217-226`). Keying the store by
   issuer is what makes migration re-register (section 3.12).
4. **Dynamic client registration** (RFC 7591) at `registration_endpoint`;
   no endpoint means `:no_client_identity` (`oc:flow.ex:228-258`).

Note the order puts CIMD ahead of a configured client ID. In the runner no
scenario both advertises CIMD and supplies a client ID, so either order
passes.

The DCR request body (`oc:flow.ex:260-283`):

```json
{
  "client_name": "presa-conformance",
  "redirect_uris": ["http://127.0.0.1:50003/callback"],
  "grant_types": ["authorization_code", "refresh_token"],
  "response_types": ["code"],
  "token_endpoint_auth_method": "none",
  "application_type": "native"
}
```

What the runner checks in it:

- `application_type` is `"native"` or `"web"`, at 2026-07-28 only
  (`sep-837-application-type-present`, FAILURE otherwise). snodo sends
  `native`, or `web` for an `https` redirect (`oc:flow.ex:285-286`).
- `grant_types` includes `refresh_token`, in `offline-access-scope` only
  when the client registers instead of using CIMD
  (`sep-2207-client-metadata-grant-types`, WARNING otherwise).
- In the `metadata-*` scenarios `client-registration` is a required check:
  a client that skips DCR there fails "Expected Check Missing" (runner `to`
  `getChecks`).

`token_endpoint_auth_method` in the request: the configured method, else
`none` when the AS lists it, else the secret method per section 3.9
(`oc:client_auth.ex:34-43`). The runner ignores it; what matters is the
response's method (section 3.9).

### 3.5 Scope selection

snodo (`oc:flow.ex:291-309`), as one ordered, de-duplicated list:

1. the challenge's `scope` when it is non-empty, else the PRM's
   `scopes_supported`;
2. on step-up, the newly challenged scopes;
3. the scopes the current token was granted (SEP-2350 union);
4. configured extra scopes;
5. `offline_access` when the client wants refresh tokens, the grant is the
   code flow, and the **AS metadata's** `scopes_supported` lists it
   (SEP-2207).

The `scope` parameter is omitted entirely when the list is empty
(`oc:flow.ex:311-312`).

| Scenario | Challenge scope | PRM `scopes_supported` | AS `scopes_supported` | Required in the authorization request |
|---|---|---|---|---|
| `scope-from-www-authenticate` | `mcp:basic` | none | none | contains `mcp:basic`, else WARNING |
| `scope-from-scopes-supported` | none | `mcp:basic mcp:read mcp:write` | none | contains all three, else WARNING |
| `scope-omitted-when-undefined` | none | none | none | `scope` absent or blank, else WARNING |
| `scope-step-up` | `mcp:basic`, then `mcp:write` on 403 | `mcp:profile` | none | 1st contains `mcp:basic`; 2nd contains `mcp:write` and (2026-07-28) `mcp:basic` |
| `offline-access-scope` | none | `mcp:basic` | `mcp:basic offline_access` | `offline_access` gives SUCCESS, its absence INFO |
| `offline-access-not-supported` | none | `mcp:basic mcp:read` | `mcp:basic mcp:read` | must not contain `offline_access` (FAILURE) |

So the challenge wins over `scopes_supported` (step-up's PRM lists only
`mcp:profile`), and `offline_access` is keyed to the AS metadata, never the
PRM, and never added unconditionally.

### 3.6 PKCE and the authorization request

PKCE (RFC 7636, `S256` only): verifier = 32 random bytes, base64url without
padding (43 characters); challenge = base64url(SHA-256(ASCII verifier))
without padding (`oc:pkce.ex:12`, `:16-17`). `state` = 32 random bytes,
base64url (`oc:flow.ex:320`).

Before the URL leaves the client, snodo stores the pending authorization
under `state`: verifier, redirect URI, resource, issuer, scopes, client ID,
creation time (ten-minute TTL) (`oc:flow.ex:317-333`, `:20`).

The authorization URL is the `authorization_endpoint` with these query
parameters (form-encoded, appended with `&` if the endpoint already has a
query) (`oc:flow.ex:334-345`, `:603-606`):

```
GET /authorize?response_type=code
  &client_id=test-client-id
  &redirect_uri=http%3A%2F%2F127.0.0.1%3A50003%2Fcallback
  &code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM
  &code_challenge_method=S256
  &state=Hn3w...
  &resource=http%3A%2F%2Flocalhost%3A50001%2Fmcp
  &scope=mcp%3Abasic HTTP/1.1
Host: localhost:50002
```

Runner checks: `pkce-code-challenge-sent` (present),
`pkce-s256-method-used` (exactly `S256`), `resource-parameter-in-authorization`
(present), `resource-parameter-valid-uri` (parses as a URL, no fragment),
plus the scope checks of section 3.5 (runner `J`, `Qa`).

### 3.7 Following the authorization URL

The runner's AS needs no user: `GET /authorize` answers at once with

```http
HTTP/1.1 302 Found
Location: http://127.0.0.1:50003/callback?code=test-auth-code&state=Hn3w...&iss=http%3A%2F%2Flocalhost%3A50002
```

The client must turn that `Location` into authorization response parameters.
Two ways work:

- **Loopback listener** (snodo, RFC 8252 section 7.3 per
  `oc:loopback.ex:3-5`): bind `127.0.0.1` on an OS-assigned port with path
  `/callback` before building the URL, register that URI, and have the
  harness's browser stand-in GET the authorization URL with redirects
  followed, so the 302 lands on the listener. snodo's `follow/1` does exactly
  this with `:httpc` `autoredirect: true` and expects the final 200 from the
  listener (`harness:121-132`).
- **Read `Location` directly**: GET the authorization URL with redirects
  disabled, take the `Location` header, and parse its query. snodo's
  `:authorize` contract allows this: the function may return
  `{:ok, redirect_url}` instead of `:ok` (`oc:client.ex:91-96`,
  `oc:flow.ex:412-413`). The `redirect_uri` still has to be an absolute URL
  the AS can parse, but nothing has to listen on it.

The second needs no server socket and suits a Babashka harness; the first is
what a real desktop client needs. Either way, keep the `:authorize` step a
function supplied by the application, so the harness can stand in for the
browser. Bound the wait: snodo's harness sets a 20 s authorization timeout
under the runner's 30 s scenario timeout (`harness:106`).

### 3.8 Validating the authorization response (RFC 9207)

snodo's order (`oc:flow.ex:441-450`):

1. `state` present and equal, in constant time, to the value this run
   issued; then the pending entry is fetched and deleted, and refused when
   older than ten minutes (`:452-472`).
2. `iss` (`:481-497`, RFC 9207 section 2 per the runner): if present, it
   must equal the AS metadata `issuer` exactly, whether or not the AS
   advertised support; if absent, the response is refused when the AS
   metadata has `authorization_response_iss_parameter_supported: true`, and
   accepted otherwise. `iss` is checked before `error`, since RFC 9207 covers
   error responses too.
3. `error` present means `{:authorization_denied, error, nil}` (`:474-477`).
4. `code` present.

No URL normalization of `iss`: `iss-normalized` sends the issuer with a
trailing `/` and requires rejection (`sep-2468-client-no-normalization`; its
failure text names scheme or host case folding, default-port elision,
trailing slash, and percent-encoding normalization). Compare the decoded
query value to the issuer string with `=`.

The four rejecting scenarios pass only if the client fetched the AS metadata,
reached the authorization endpoint, and then made **no token request**
(runner `as`). A client that refuses earlier (for example, never fetches the
metadata) still fails those checks with a "property not reached" FAILURE.

### 3.9 The token request and client authentication

Authorization code exchange (`oc:flow.ex:499-509`):

```http
POST /token HTTP/1.1
Host: localhost:50002
Content-Type: application/x-www-form-urlencoded
Accept: application/json
Authorization: Basic dGVzdC1jbGllbnQtaWQ6dGVzdC1jbGllbnQtc2VjcmV0

grant_type=authorization_code&code=test-auth-code&redirect_uri=http%3A%2F%2F127.0.0.1%3A50003%2Fcallback&code_verifier=dBjf...&resource=http%3A%2F%2Flocalhost%3A50001%2Fmcp&client_id=test-client-id
```

Runner checks: `token-request`, `pkce-code-verifier-sent`,
`pkce-verifier-matches-challenge` (against the challenge of the most recent
authorization request), `resource-parameter-in-token`,
`resource-parameter-consistency` (byte-equal to the authorization request's
`resource`), `resource-parameter-matches-prm` (runner `J`, `Qa`).

**Choosing the method** (`oc:client_auth.ex:19-27`, `:133-147`):

1. a configured method;
2. else the method the registration response named;
3. else `private_key_jwt` when a private key is configured;
4. else, with a secret: `client_secret_basic` if listed in
   `token_endpoint_auth_methods_supported`, else `client_secret_post` if
   listed, else `none` if listed, else `client_secret_basic`. An absent list
   means `["client_secret_basic"]` (RFC 8414 default);
5. else `none`.

**Applying it** (`oc:client_auth.ex:48-82`):

| Method | Form fields | Header |
|---|---|---|
| `none` | `client_id` | none |
| `client_secret_basic` | `client_id` (snodo adds it; harmless) | `Authorization: Basic base64(urlencode(id) ":" urlencode(secret))` (RFC 6749 section 2.3.1) |
| `client_secret_post` | `client_id`, `client_secret` | none |
| `private_key_jwt` | `client_id`, `client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer`, `client_assertion` | none |

The runner detects the method from the request (runner `mo`): an
`Authorization` header starting `Basic ` is `client_secret_basic`, else a body
`client_secret` is `client_secret_post`, else `none`; Basic must base64-decode
to something with a `:`.

| Scenario | AS lists | Registration answers | Must use |
|---|---|---|---|
| default (all others) | `["none"]` | id + secret `test-client-secret`, no method | not checked |
| `token-endpoint-auth-basic` | `["client_secret_basic"]` | id + secret + `client_secret_basic` | Basic header |
| `token-endpoint-auth-post` | `["client_secret_post"]` | id + secret + `client_secret_post` | body secret, no Basic |
| `token-endpoint-auth-none` | `["none"]` | id, no secret, `none` | neither |
| `pre-registration` | `["client_secret_basic"]`, no DCR | n/a | Basic with `pre-registered-client:pre-registered-secret` |

The `private_key_jwt` assertion (`oc:client_auth.ex:154-176`): header
`{"alg": <from key or configured>, "kid": <if the JWK has one>}`, claims
`iss` = `sub` = client ID, `aud` = the AS issuer, random `jti`, `iat`,
`exp` = `iat` + 300. The runner accepts `aud` as the AS base URL with or
without a trailing slash (`client-credentials-jwt`).

**The token response** (`oc:flow.ex:540-586`): 200 with a string
`access_token` and `token_type` equal to `bearer` case-insensitively
(anything else is refused); `expires_in` gives `expires_at`; `scope` absent
means the requested scopes were granted (RFC 6749 section 5.1). Store per
resource: access token, expiry, refresh token, granted scopes, issuer,
resource, and the `resource_metadata` URL the challenge named (so a later
flow can find the document without a challenge)
(`snodo:integrations/oauth/lib/snodo/oauth/client/token_store.ex`).

### 3.10 The retried MCP request

The transport sends the same JSON-RPC message again with
`Authorization: Bearer test-token-<ms>`. Each accepted request emits
`valid-bearer-token`; snodo's observed runs show three per scenario at
2026-07-28 (`server/discover`, `tools/list`, `tools/call test-tool`)
(`results`). Later requests carry the stored token; snodo refreshes an
expired one first (with a 30 s leeway) when it has a refresh token
(`oc:client.ex:213-228`, `:128`).

Each request gets at most two attempts: one with the current token (or none),
and one after the provider answers the challenge. A second refusal is a
-32000 transport error with `cause: {:unauthorized, status, challenge}`; a
third attempt is never made (`snodo:lib/snodo/client/http.ex:836-863`,
`snodo:lib/snodo/client/token_provider.ex:14-22`).

### 3.11 Step-up (403 `insufficient_scope`) and the retry limit

On a 403 whose challenge is `insufficient_scope`, snodo
(`oc:flow.ex:137-146`):

- no challenged scope: error `:step_up_without_scope`;
- every challenged scope already granted: error
  `{:step_up_refused, scopes}`, no authorization (this is what bounds a
  server that answers every request with 403);
- otherwise a new authorization for challenged + granted scopes (section
  3.5), with discovery rerun.

`scope-step-up`: `tools/list` needs `mcp:basic`, `tools/call` needs
`mcp:write`. The second authorization request must contain `mcp:write`
(`scope-step-up-escalation`) and, at 2026-07-28 and later, `mcp:basic` too
(`sep-2350-scope-union-on-reauth`, via runner `Oe(specVersion, A)`).

`scope-retry-limit`: the 401 and every 403 name `mcp:admin`, the token gets
`mcp:admin`, and every authenticated request is refused with 403 (after three,
410 `test_complete`). Scored on the number of authorization requests: 1 to 3
is SUCCESS, 0 or more than 3 FAILURE (`scope-retry-limit`;
`scope-retry-auth-attempt` INFO per attempt). snodo makes one: the 403 names
nothing the token lacks, so the step-up is refused (`results` shows one
`authorization-request`).

### 3.12 Authorization server migration (SEP-2352)

`authorization-server-migration` runs AS1 and AS2. The PRM names AS1 until
the first authenticated MCP request succeeds; from then on it names AS2, and
any token not starting `test-token-as2-` gets 401 `Bearer scope="mcp:basic",
resource_metadata=...` with `error_description` "authorization server has
changed". AS1 registers clients as `as1-client-id-LEAKED-IF-SEEN-AT-AS2`.

The client must, on that 401: re-fetch the PRM, see the new issuer, fetch
AS2's metadata, register at AS2, authorize and exchange there, and retry.
Checks (all at teardown):

- `sep-2352-reregister-on-as-change`: a registration request reached AS2;
- `sep-2352-no-reuse-on-as-change` and `sep-2352-no-cross-as-credential-reuse`:
  the AS1 client ID never appeared at AS2's authorize endpoint, in the token
  request body's `client_id`, or in a Basic header.

snodo gets this from three things: discovery on every flow, a registration
store keyed by issuer, and dropping a stored token whose issuer differs from
the current AS (`oc:flow.ex:97-112`). A refresh token from AS1 must not be
sent to AS2 either; the same issuer check covers it (no runner AS issues
refresh tokens, so this is untested).

### 3.13 Refresh tokens

No runner AS returns `refresh_token` (runner `J` token response), so no
scored scenario exercises the refresh grant. What is scored is asking for one
correctly: `offline_access` only when the AS lists it, and `refresh_token` in
DCR `grant_types`. snodo's refresh, for reference (`oc:flow.ex:517-538`):
`grant_type=refresh_token&refresh_token=...&resource=...` with the same
client authentication; a response without `refresh_token` keeps the old one;
`invalid_grant` drops the stored token and authorizes again; any other
failure keeps it.

## 4. `MCP_CONFORMANCE_CONTEXT` for auth scenarios

The runner sets the variable only when the scenario returns a `context`, as
`{"name": <scenario>, ...context}` (runner `Ac`). Every scored auth scenario
except `pre-registration` sets none.

| Scenario | Context keys and values |
|---|---|
| `auth/pre-registration` | `client_id: "pre-registered-client"`, `client_secret: "pre-registered-secret"`, `issuer: <AS base URL>` |
| `auth/client-credentials-basic` | `client_id: "conformance-test-client"`, `client_secret: "conformance-test-secret"` |
| `auth/client-credentials-jwt` | `client_id: "conformance-test-client"`, `private_key_pem: <PKCS#8 PEM, P-256>`, `signing_algorithm: "ES256"` |
| `auth/enterprise-managed-authorization` | `client_id: "conformance-test-xaa-client"`, `client_secret: "conformance-test-xaa-secret"`, `idp_client_id: "conformance-test-idp-client"`, `idp_id_token`, `idp_issuer`, `idp_token_endpoint` |
| `auth/wif-jwt-bearer` | `client_id: "conformance-wif-workload"`, `valid_jwt`, `wrong_audience_jwt`, `expired_jwt` |

`pre-registration` and `client-credentials-basic` carry the same keys, so the
grant cannot be inferred from the context; snodo's harness selects
`client_credentials` by scenario name (`harness:43`, `:113`). snodo ignores
the `issuer` key and finds the AS through the PRM (`harness:99-119`). The
CIMD URL is not in any context (section 3.4).

## 5. Scenario reference

### 5.1 Checks common to the code-flow scenarios

| Check id | Emitted by | SUCCESS requires |
|---|---|---|
| `prm-pathbased-requested` | `X`, any GET of the scenario's PRM path (the name is historical) | always SUCCESS when emitted; required in `metadata-default` and `-var1`; `resource-mismatch` fails "property not reached" without it |
| `authorization-server-metadata` | `J`, GET of the metadata path | always SUCCESS when emitted; required in `metadata-*` |
| `client-registration` | `J`, POST `/register` | always SUCCESS; required in `metadata-*` |
| `sep-837-application-type-present` | `J`, POST `/register`, 2026-07-28 only | `application_type` is `native` or `web` |
| `authorization-request` | `J`, GET `/authorize` | always SUCCESS; required in `metadata-*` |
| `pkce-code-challenge-sent` | same | `code_challenge` present |
| `pkce-s256-method-used` | same | `code_challenge_method` is `S256` |
| `token-request` | `J`, POST `/token` | always SUCCESS; required in `metadata-*` |
| `pkce-code-verifier-sent` | same, `authorization_code` only | `code_verifier` present |
| `pkce-verifier-matches-challenge` | same | S256 of the verifier equals the last challenge |
| `valid-bearer-token` / `invalid-bearer-token` | `Y`, each MCP POST with a token | token starts `test-token` or `cc-token` |
| `resource-parameter-in-authorization`, `-in-token`, `-valid-uri`, `-consistency`, `-matches-prm` | `Qa`, at teardown, `metadata-*` and `token-endpoint-auth-*` only | section 3.2, 3.6, 3.9 |
| `incoming-request`, `outgoing-response`, `incoming-auth-request`, `outgoing-auth-response` | request logging | INFO |

### 5.2 Scored scenarios

| Scenario | What its servers do | Scenario-specific checks and requirements |
|---|---|---|
| `metadata-default` | PRM path-based, in challenge; AS metadata at root RFC 8414 | `prm-priority-order` FAILURE if root PRM is fetched; the five required checks; `resource-parameter-*` |
| `metadata-var1` | PRM path-based, not in challenge; AS metadata only at root OIDC (with `jwks_uri` etc.) | path-based fallback; RFC 8414 URL 404s, then OIDC; required checks; `resource-parameter-*` |
| `metadata-var2` | PRM at root only, resource `http://localhost:<mcp>`; issuer `.../tenant1`, metadata at `/.well-known/oauth-authorization-server/tenant1`, endpoints under `/tenant1` | root PRM fallback; prefix resource check; send the pathless resource verbatim; `authorization-server-metadata-wrong-path` FAILURE on root AS metadata |
| `metadata-var3` | PRM at `/custom/metadata/location.json`, in challenge; issuer `.../tenant1`, metadata at `/tenant1/.well-known/openid-configuration` | use the header URL; third AS URL variant; same root trap |
| `basic-cimd` | AS advertises `client_id_metadata_document_supported: true`; DCR still available | `cimd-client-id-used`: `client_id` exactly `https://conformance-test.local/client-metadata.json`, else WARNING; FAILURE if no authorization request |
| `scope-from-www-authenticate` | challenge `scope="mcp:basic"`; server enforces `mcp:basic` | `scope-from-www-authenticate` (WARNING if missing) |
| `scope-from-scopes-supported` | no scope in challenge; PRM lists `mcp:basic mcp:read mcp:write` | `scope-from-scopes-supported` (WARNING) |
| `scope-omitted-when-undefined` | no scope anywhere | `scope-omitted-when-undefined` (WARNING) |
| `scope-step-up` | custom middleware, section 3.11 | `scope-step-up-initial`, `scope-step-up-escalation` (WARNING), `sep-2350-scope-union-on-reauth` (2026-07-28, WARNING); FAILURE if a round is missing |
| `scope-retry-limit` | every authenticated request 403 `mcp:admin` | `scope-retry-limit`: 1 to 3 authorization requests |
| `token-endpoint-auth-basic`, `-post`, `-none` | AS lists exactly one method; DCR answers it | `token-endpoint-auth-method` (section 3.9 table); `resource-parameter-*` |
| `pre-registration` | no `registration_endpoint`; AS lists `client_secret_basic` | `pre-registration-auth`: Basic with the context's credentials |
| `resource-mismatch` | PRM `resource` is `https://evil.example.com/mcp` | `resource-mismatch-rejected`: PRM fetched and no authorization request; FAILURE "property not reached" if the PRM was never fetched |
| `offline-access-scope` | AS lists `mcp:basic offline_access` and CIMD; PRM lists `mcp:basic` | `sep-2207-offline-access-requested` (SUCCESS or INFO; FAILURE only if no authorization request); `sep-2207-client-metadata-grant-types` (DCR: WARNING without `refresh_token`; CIMD: INFO) |
| `offline-access-not-supported` | AS lists `mcp:basic mcp:read` | `sep-2207-offline-access-not-requested`: FAILURE if `offline_access` is requested |
| `authorization-server-migration` | section 3.12 | `sep-2352-reregister-on-as-change`, `sep-2352-no-reuse-on-as-change`, `sep-2352-no-cross-as-credential-reuse` |
| `iss-supported` | advertised, correct `iss` | `sep-2468-client-compare-iss-supported`: a token request was made |
| `iss-not-advertised` | flag absent, no `iss` | `sep-2468-client-proceed-no-iss`: a token request was made |
| `iss-supported-missing` | advertised, no `iss` | `sep-2468-client-reject-missing-iss`: no token request (runner `as`) |
| `iss-wrong-issuer` | advertised, `iss=https://evil.example.com` | `sep-2468-client-compare-iss-supported` (same id as `iss-supported`, inverted): no token request |
| `iss-unexpected` | flag absent, `iss=https://evil.example.com` | `sep-2468-client-compare-iss-unadvertised`: no token request |
| `iss-normalized` | advertised, `iss` = issuer + `/` | `sep-2468-client-no-normalization`: no token request |
| `metadata-issuer-mismatch` | metadata issuer `https://attacker.example.com` | `sep-2468-client-validate-metadata-issuer`: metadata fetched, then no registration, authorization, or token request |

Every scenario also collects the section 5.1 checks it triggers, and a scored
scenario fails on any FAILURE among them. The non-`metadata` scenarios do not
add "Expected Check Missing" entries for the generic checks, so their own
check is the gate. snodo's alpha.12 observed check lists per scenario are in
`results` and match this table.

## 6. Unscored extension scenarios

These have `source.extensionId` and are `not_scored: extension` in both
requirement files (`requirements/2026-07-28.yaml:111-129`,
`requirements/2025-11-25.yaml:95-113`); snodo runs them in both lanes
(`snodo:conformance/README.md:277-283`, `:311-312`).

| Scenario | Extension | What it needs | snodo |
|---|---|---|---|
| `auth/client-credentials-basic` | `io.modelcontextprotocol/oauth-client-credentials` (SEP-1046) | `grant_type=client_credentials` with Basic `conformance-test-client:conformance-test-secret`; check `client-credentials-basic-auth` | passes |
| `auth/client-credentials-jwt` | same | `client_credentials` with a `private_key_jwt` assertion (ES256, `iss` = `sub` = client ID, `aud` = AS URL) signed by the context key; checks `client-credentials-jwt-verified` (plus `-iss`, `-sub`, `-assertion-type`, `-grant-type` on failure) | passes |
| `auth/enterprise-managed-authorization` | SEP-990 | RFC 8693 token exchange of `idp_id_token` at `idp_token_endpoint` for an ID-JAG (`requested_token_type=urn:ietf:params:oauth:token-type:id-jag`, `audience`, `resource`), then a JWT-bearer grant at the AS with the ID-JAG as `assertion` and Basic client auth; checks `complete-flow-token-exchange`, `complete-flow-jwt-bearer` | (presa: passes) |
| `auth/dpop`, `auth/dpop-nonce` | `io.modelcontextprotocol/auth/dpop` (SEP-1932, RFC 9449) | DPoP proof at the token endpoint, `Authorization: DPoP` with a fresh proof per request; the nonce variant adds `use_dpop_nonce` retries at AS and RS; checks `sep-1932-client-token-request-proof`, `-dpop-auth-scheme`, `-fresh-proof`, `-as-nonce`, `-rs-nonce` | (presa: passes all) |
| `auth/wif-jwt-bearer` | `io.modelcontextprotocol/auth/wif` (SEP-1933) | `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer` with `assertion=valid_jwt`, no DCR, `none` auth; no retry or grant fallback after an error; checks `wif-grant-type`, `wif-assertion-verified`, `wif-grant-fallback` (WARNING), `wif-no-retry` (WARNING) | (presa: passes) |

For the client credentials grant snodo runs PRM and AS discovery and client
identification as for the code flow, then posts `grant_type=client_credentials`,
`resource`, and `scope` (if any) with the client authentication, and obtains
the token on the first `token` call instead of waiting for a 401
(`oc:flow.ex:158-171`, `oc:client.ex:307-312`).

## 7. How snodo structures its client (design input)

Three layers:

1. **Transport hook** (`Snodo.Client.TokenProvider`,
   `snodo:lib/snodo/client/token_provider.ex:9-39`). Two callbacks, called in
   the requesting process:
   - `token(state, %{url: url})` before every request (and before opening a
     `subscriptions/listen` stream): `{:ok, token}`, `{:ok, nil}` to send
     without one, or `{:error, error}`.
   - `refresh(state, challenge, %{url, status, token})` after a 401, or a 403
     `insufficient_scope`: a new token or an error.

   The transport owns the `Authorization` header and refuses one in static
   headers when a provider is set (`snodo:lib/snodo/client/http.ex:597`),
   validates the returned token (non-empty, no whitespace, CR, LF, NUL), and
   never logs it (`http.ex:896-913`). The provider call is outside the
   per-attempt timeout, so a browser login lengthens the request
   (`token_provider.ex:29-33`).
2. **Provider process** (`oc`, a GenServer per MCP server). `token` returns
   the stored token if fresh, starts a refresh flow if it has a refresh token,
   else returns `nil` so the first request learns the challenge
   (`oc:client.ex:213-228`, `:309-312`). `refresh` returns a different stored
   token if one is fresh (another caller already refreshed), else starts a
   flow with trigger `{:challenge, status, challenge}` (`:230-243`). One flow
   runs at a time; callers that arrive meanwhile join its waiter list and all
   get its result (`:314-339`).
3. **Flow** (`oc:flow.ex`), one run per process: scheme check, PRM, issuer,
   AS metadata, then `obtain`: step-up on 403, refresh grant if a refresh
   token exists (falling back to a new authorization on `invalid_grant`), else
   a new grant (`oc:flow.ex:25-41`, `:114-131`). The flow returns
   `{:ok, nil}` without error when there was no challenge and no well-known
   PRM, so the request goes out bare and the server's challenge names the
   metadata (`:48-71`).

Stores are behaviours with in-memory defaults: tokens keyed by canonical
resource, registrations keyed by issuer (with the redirect URI they were
made for), pending authorizations keyed by `state`
(`oc:client.ex:445-463`, `oc:token_store.ex`, `oc:registration_store.ex`,
`oc:pending_authorization_store.ex`). Every flow URL must be `https` or
`http` to a loopback host; the flow's own HTTP client never follows
redirects and caps bodies at 256 KiB (`oc:http.ex:1-6`, `:87`;
`oc:client.ex:102-106`). Errors are `kind: :authorization`, code -32000, with
a `cause` naming the failed step (`oc:client.ex:111-115`,
`oc:flow.ex:633-738`).

## 8. Hooking into presa

This section is the design written before `presa.auth.client` existed. It
describes presa as it was then (no token provider hook, no challenge
parser, `jwt/sign` with a fixed `typ`), and its line references are to that
code. The authorization guide describes the current behaviour.

### 8.1 Transport

`presa.transport.http/url` (`presa:src/presa/transport/http.clj:569-740`)
takes static `:headers` merged over the computed ones in `post`
(`:641-645`). The OAuth hook belongs in `post` (`:641-677`):

1. Before building the request, ask the provider for a token and add
   `authorization: Bearer <t>` when it returns one.
2. After `.send`, check the status **before** the content-type dispatch.
   Today a 401 with `application/json` body `{"error": "invalid_token"}` falls
   into the JSON branch, fails `rpc/kind`, and is delivered as -32000
   `HTTP 401 with an unreadable body` (`:665-672`).
3. On 401, or 403 whose `WWW-Authenticate` challenge has
   `error="insufficient_scope"`: close the body, parse the challenge, call the
   provider's refresh with it, the status, and the refused token, then rebuild
   and resend the request once (the body is a string,
   `HttpRequest$BodyPublishers/ofString`, so resending is cheap).
4. On a second refusal, deliver `rpc/error id -32000` with data such as
   `{"status": 401}` and the challenge, never the token.

The same hook applies to `open-get` (`:599-613`, initialize-era session
streams) and the session `DELETE` in `:close!` (`:725-740`) for real servers;
the runner's resource server answers only `POST /mcp`, so neither is tested.
`list-tools!` (`:678-692`) goes through `post` and is covered.

`send!` runs each `post` in its own `future` (`:693-722`), so two requests
can be refused at once. The provider must serialize flows (one in flight,
others wait for its result), as snodo's GenServer does. A
`(locking ...)` around the flow plus "return the stored token if it differs
from the refused one" (`oc:client.ex:233-238`) is enough for presa.

A shape that mirrors snodo: `:token-provider {:token (fn [ctx]) :refresh
(fn [challenge ctx])}` in the `url` options, with `ctx` `{:url endpoint}`
and, for refresh, `:status` and `:token`. Refuse a static `authorization`
header when a provider is set.

### 8.2 Client

`presa.client/connect` probes with `server/discover` and treats any non-timeout
failure as "not a 2026-07-28 server" (`presa:src/presa/client.clj:275-300`),
then throws `:unsupported-version` when 2026-07-28 is the only allowed
revision (`:374-386`). With the hook in the transport, discover sees the real
result after the flow. Without a provider, an auth-protected server currently
surfaces as a version error, which is misleading.

### 8.3 Reusable presa pieces

- `presa.auth/canonical-resource` (`presa:src/presa/auth.clj:18-33`) for the
  configured server URL, though the PRM's `resource` should be sent verbatim
  (section 3.2).
- `presa.auth/challenge` (`:87-97`) builds challenges; there is no parser yet.
  The client needs one (section 3.1).
- `presa.auth.jwt/b64` (`presa:src/presa/auth/jwt.clj:24`) is base64url
  without padding, for PKCE and `state`.
- `presa.auth.jwt/sign` (`:129-144`) signs ES256 in the JWS (P1363) format,
  but always writes `"typ": "at+jwt"` in the header. That is wrong for a
  client assertion (RFC 7523 sets no `typ`); the runner does not check `typ`,
  so it would pass, but a client assertion signer should take the header as
  a parameter.

### 8.4 Harness

`presa:conformance/presa/conformance/client.clj` would gain the auth branch
snodo has (`harness:67-68`, `:99-132`):

- when `MCP_CONFORMANCE_SCENARIO` starts with `auth/`, build a provider with
  the server URL as resource, the fixed CIMD URL
  `https://conformance-test.local/client-metadata.json`, the context's
  `client_id`, `client_secret`, `private_key_pem`, `signing_algorithm`, and
  the client credentials grant for `auth/client-credentials-basic` and
  `auth/client-credentials-jwt`;
- an authorize function that GETs the authorization URL without following
  redirects and returns the `Location` (section 3.7);
- pass the provider to `http/url`, then run the existing flow unchanged.

Exit status matters: the scenarios without `allowClientError` (section 1)
need exit 0, which the current harness gives as long as `connect` succeeds,
since tool and listing failures are only logged
(`presa:conformance/presa/conformance/client.clj:61-63`, `attempt`). The rejecting
scenarios fail at `connect` and exit 1, which they allow.

## 9. HTTP client capabilities (Babashka)

Checked against Babashka 1.13.229:

| Need | Availability |
|---|---|
| Do not follow redirects; read `Location` | `java.net.http.HttpClient` defaults to `HttpClient$Redirect/NEVER`; presa's client builder does not change it (`presa:src/presa/transport/http.clj:576-579`). `(.firstValue (.headers resp) "location")` returns it. `HttpClient$Redirect` resolves in bb |
| Follow redirects to a loopback listener (alternative) | `.followRedirects HttpClient$Redirect/NORMAL` on a separate client |
| Loopback listener | `com.sun.net.httpserver.HttpServer` is **not** available in bb (`Unable to resolve classname`); http-kit's server is, and presa already uses it (`org.httpkit.server`) |
| Form POST | `BodyPublishers/ofString` with `content-type: application/x-www-form-urlencoded`; encode with `java.net.URLEncoder/encode s "UTF-8"` (form encoding, space as `+`) |
| Read status, headers, JSON body | `BodyHandlers/ofString` and `clojure.data.json` |
| PKCE, state | `java.security.SecureRandom`, `MessageDigest` SHA-256, `java.util.Base64` URL encoder without padding |
| Parse the context's PKCS#8 PEM | strip the armor, base64-decode, `PKCS8EncodedKeySpec` with `KeyFactory/getInstance "EC"`; resolves in bb |
| ES256 signature for `private_key_jwt` | `Signature/getInstance "SHA256withECDSAinP1363Format"` works in bb (64-byte output) |
| Parse the redirect query | `java.net.URI` `.getRawQuery`, split on `&` and `=`, `URLDecoder/decode` |

Nothing in the scored set needs TLS, cookies, or a browser; every runner URL
is `http://localhost:<port>`.

## 10. Gaps and disagreements

- **No spec prose.** MUST/SHOULD wording here comes from runner check
  descriptions and snodo docs; the MCP authorization page itself was not
  available.
- **Resource check rule.** snodo accepts a PRM `resource` that is a
  same-origin path prefix of the server URL. RFC 9728 section 3.3 (as the
  runner cites it) ties the `resource` to the identifier the well-known URL
  was built from, which is a different rule. Both pass every scenario: the
  runner only tests an unrelated host (`https://evil.example.com/mcp`). I did
  not resolve which the MCP specification requires.
- **Fallback after a header URL fails.** snodo fetches only the challenge's
  `resource_metadata` URL and does not fall back to well-known locations if
  it fails (`oc:discovery.ex:49`). No scenario tests that case.
- **Identity order.** snodo prefers CIMD over a configured client ID. No
  scenario offers both, so the right precedence is untested.
- **Issuer migration with a refresh token**, refresh grants in general, and
  token expiry are not exercised by any scored scenario.
- **`scope-retry-limit` semantics.** The runner scores only the count of
  authorization requests (at most 3); it does not distinguish a client that
  refuses to step up from one that retries a bounded number of times.
- **`iss-wrong-issuer` reuses the check id
  `sep-2468-client-compare-iss-supported`** with the opposite requirement from
  `iss-supported`. Matching by id across scenarios will confuse the two.
- **`offline-access-scope`** passes with or without `offline_access` (INFO
  when absent); SEP-2207 makes requesting it optional. snodo requests it by
  default (`oc:client.ex:84`).
- **DPoP challenge scheme.** The DPoP scenarios answer `DPoP
  resource_metadata=...` with no Bearer challenge. snodo's flow refuses a
  non-bearer scheme (`oc:flow.ex:92-95`). presa's provider runs the flow
  for any scheme, and uses DPoP when the authorization server lists ES256
  in `dpop_signing_alg_values_supported` (`presa.auth.dpop`).
- **Extension scenarios at a revision.** Extension scenarios are not on the
  spec timeline (runner `xc` returns false for `extensionId`), and the runner
  prints SKIPPED for an inapplicable scenario unless forced (runner `jc`).
  snodo's reports show them running in both lanes under `--requirements`; I
  did not trace the code path that runs them.
- **Express redirect body.** The 302 body is whatever express writes for
  `res.redirect`; nothing depends on it. A client reading `Location` should
  close the body without parsing it.
