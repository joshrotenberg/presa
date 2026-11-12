# Authorization

An MCP server over HTTP is an OAuth 2.1 resource server: it publishes
protected resource metadata (RFC 9728), requires a bearer token on every
request (RFC 6750), and answers a missing or bad token with a
`WWW-Authenticate` challenge that names the metadata, so a client can find
the authorization server. presa does this in `presa.auth`, following
snodo_oauth; it is not an authorization server.

## The resource server

```clojure
(require '[clojure.data.json :as json]
         '[presa.auth :as auth]
         '[presa.auth.jwt :as jwt]
         '[presa.client :as client]
         '[presa.server :as server]
         '[presa.transport.http :as http])

;; a key pair standing in for the authorization server's signing key
(def signing-keys (.generateKeyPair (doto (java.security.KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(def srv
  (server/server
   {:name "secure" :version "1"
    ;; per component: tools, prompts (by name), resources (by URI), templates
    :required-scopes {[:tool "admin"] ["mcp:admin"]}
    :tools [{:name "whoami" :description "Who is calling" :handler (fn [_ ctx] (get-in ctx [:auth :subject]))}
            {:name "admin" :description "Admins only" :handler (fn [_ _] "done")}]}))

(def port (let [probe (http/serve! srv)] ((:stop! probe)) (:port probe)))
(def resource (str "http://127.0.0.1:" port "/mcp"))

(def config
  (auth/resource-server
   {:resource resource
    :verify (jwt/verifier {:audience resource :keys {"k1" (.getPublic signing-keys)}})
    :authorization-servers ["https://auth.example.com"]
    :scopes-supported ["mcp:read" "mcp:admin"]
    :required-scopes ["mcp:read"]}))

(def running (http/serve! srv {:port port :auth config}))
```

The metadata document is served without a token at its well-known path,
derived from the resource:

```clojure
(auth/metadata-path resource)
;; => "/.well-known/oauth-protected-resource/mcp"

(get (json/read-str (slurp (str "http://127.0.0.1:" port "/.well-known/oauth-protected-resource/mcp")))
     "authorization_servers")
;; => ["https://auth.example.com"]
```

Every request to the endpoint needs a token:

| Request | Status | Challenge |
|---|---|---|
| no `Authorization: Bearer` | 401 | `Bearer scope="...", resource_metadata="..."` |
| a token the verifier refuses | 401 | `error="invalid_token"` |
| a token without `:required-scopes` | 403 | `error="insufficient_scope", scope="..."` |
| a malformed header | 400 | `error="invalid_request"` |

## Verifying tokens

`:verify` is a function of the token string that returns
`{:subject :client-id :scopes :claims}`, or `{:error kind :description s}`.
`presa.auth.jwt/verifier` checks JWS access tokens: RS256 and ES256 against
a JWKS URL (`:jwks-url`) or static `:keys`, HS256 with a `:secret`; then
`exp` and `nbf` (with `:leeway-s`), `:issuer` and `:audience`. The JWKS is
cached by `kid` and fetched again for an unknown `kid` or once it is older
than `:max-age-ms` (default an hour), one fetch at a time and at most once
per `:refresh-ms`; a refetch drops keys the document no longer lists, and a
failed one keeps the cached keys. Bring your own function for opaque tokens
or introspection.

## The caller in handlers

Handlers see the verified identity as `(:auth context)`, never the raw
token. `:required-scopes` on the server hides a component from callers
without its scopes and refuses calls to it with -32003, inside a 200 since
the request itself was authenticated. The same scopes apply to completing a
prompt's or template's arguments and to subscribing to a resource: a
caller hears `resources/updated` only for what it may read.
`:default-scopes` gives the scopes of components without an entry (default
none). `requestState` and tasks are scoped to the subject, so one caller
cannot resume another's, and an initialize-era HTTP session belongs to the
subject that opened it.

`presa.auth.jwt/verifier` requires `exp` in the tokens it accepts, as RFC
9068 does for access tokens, and refuses a token from its `exp` second on;
`:require-exp? false` drops the requirement. It refuses a DPoP-bound token
(one with `cnf.jkt`), since this resource server takes only Bearer and
cannot check the binding. A key that names its `alg` verifies only that
alg, and `use: "enc"` keys are skipped. Scopes come from `scope`, or from
`scp` as an array or a space-delimited string.
`:require-typ? true` also refuses a token whose header `typ` is not
`at+jwt`, as RFC 9068 asks; it is off by default because some issuers put
`typ: JWT` on their access tokens.

```clojure
(def token (jwt/sign {"sub" "ada" "aud" resource "scope" "mcp:read"
                      "exp" (+ 600 (quot (System/currentTimeMillis) 1000))}
                     {:alg "RS256" :kid "k1" :key (.getPrivate signing-keys)}))

(def c (client/connect (http/url (:url running) {:headers {"authorization" (str "Bearer " token)}})))

(client/text (client/call-tool c "whoami"))
;; => "ada"

(map #(get % "name") (client/list-tools c))
;; => ["whoami"]

(:code (ex-data (try (client/call-tool c "admin") (catch Exception e e))))
;; => -32003

(client/close c)
((:stop! running))
```

`http/handler`'s `:cancellation :subject` lets a posted
`notifications/cancelled` stop a request of the same subject.

## The client side

`presa.auth.client/provider` is a token provider for `http/url`. On a 401
(or a 403 `insufficient_scope`) it finds the server's protected resource
metadata and its authorization server's metadata, identifies the client (a
client ID metadata document, pre-registered credentials, or dynamic
registration), runs the authorization code flow with PKCE and the
resource indicator, checks the response's `iss`, exchanges the code, and
the request is sent again with the token. A later 403 that asks for more
scope runs it again for the union of scopes; a token from another
authorization server is never reused. When the access token expires, or a
request gets 401, the provider first tries the refresh token it stored
(the refresh grant, with the same client authentication and resource),
and runs the full flow only if that fails.

The client refuses an authorization server whose metadata does not list
`S256` in `code_challenge_methods_supported` (`:allow-unadvertised-pkce?
true` accepts metadata that leaves the field out), and one whose issuer or
endpoints are not `https`, loopback hosts aside. `:allow-http? true` accepts
plain http on any host, for a development authorization server in a
container network such as `http://keycloak:8080`; credentials and codes
then travel unencrypted. With pre-registered credentials, set `:issuer`:
the protected resource metadata must list that authorization server, and
the credentials go only to it, so an MCP server cannot steer them
elsewhere.

The browser step is yours: an authorizer gets the authorization URL and
returns the URL the authorization server redirected to.
`loopback-authorizer` listens on 127.0.0.1 and calls your function to open
a browser; `(:stop! authorizer)` closes the listener:

<!-- no-run -->
```clojure
(require '[presa.auth.client :as oauth])

(def authorizer (oauth/loopback-authorizer
                 (fn [url] (.exec (Runtime/getRuntime) (into-array ["open" url])))))

(def provider (oauth/provider {:resource "https://mcp.example.com/mcp"
                               :authorizer authorizer
                               :client-name "my-agent"}))

(client/connect (http/url "https://mcp.example.com/mcp" {:token-provider provider}))
```

`:grant :client-credentials` with `:client-id` and `:client-secret` (or
`:private-key-pem` for `private_key_jwt`) gets a token without a user.

When the authorization server lists ES256 in
`dpop_signing_alg_values_supported`, the provider binds its tokens to a
key with DPoP (RFC 9449): an ES256 key pair per provider, a proof in the
`DPoP` header of each token request, and on each request to the server
`Authorization: DPoP` with a fresh proof that carries the token's hash.
A `use_dpop_nonce` answer, from the authorization server or the MCP
server, is retried once with the nonce it gave. `:dpop false` turns this
off. `presa.auth.dpop` makes the proofs. DPoP is client-side only:
`presa.auth` accepts bearer tokens and does not check DPoP proofs.

Two more grants need no user. `:grant :jwt-bearer` with `:assertion`
(RFC 7523) trades a JWT for a token, as workload identity federation does;
the assertion is a string or a function of `{:issuer :resource
:token-endpoint}`. No client is registered, and a refused grant is final
for that provider. `id-jag` is such a function for enterprise-managed
authorization (SEP-990): it exchanges the user's ID token at the identity
provider for an ID-JAG (RFC 8693) addressed to the authorization server
the flow found.

<!-- no-run -->
```clojure
(oauth/provider {:resource "https://mcp.example.com/mcp"
                 :grant :jwt-bearer
                 :client-id "mcp-client" :client-secret "..."
                 :assertion (oauth/id-jag {:token-endpoint "https://idp.example.com/token"
                                           :id-token id-token
                                           :client-id "idp-client"})})
```
[docs/oauth-client.md](../oauth-client.md) is the reference. The
conformance runner's scored authorization scenarios (25 at 2026-07-28, 14 at
2025-11-25) and its DPoP, workload identity, enterprise-managed and client
credentials extension scenarios pass.
