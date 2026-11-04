# Initialize-era clients

presa serves MCP 2026-07-28 by default. The older revisions, 2025-11-25 and
2025-06-18, start with an `initialize` handshake and keep a session; some
hosts still speak only those (Codex 0.165.0 sends `initialize` with
2025-06-18; see [hosts.md](../hosts.md)). List them in `:versions` to serve
them too:

```clojure
(require '[presa.client :as client]
         '[presa.protocol :as protocol]
         '[presa.server :as server])

(def srv (server/server {:name "both" :version "1"
                         :versions protocol/versions   ; 2026-07-28, 2025-11-25, 2025-06-18
                         :tools [{:name "era" :description "The caller's revision"
                                  :handler (fn [_ ctx] (:version ctx))}]}))

(def c (client/connect (client/in-process srv) {:versions ["2025-06-18"]}))

(client/text (client/call-tool c "era"))
;; => "2025-06-18"

(client/close c)
```

`initialize` answers with the requested revision when the server serves
it, else with the newest initialize-era one it serves. What differs from
2026-07-28 on such a session:

| | 2026-07-28 | initialize era |
|---|---|---|
| version and capabilities | in each request's `_meta` | once, in `initialize` |
| `ping`, `logging/setLevel` | removed (-32601) | served (`logging/setLevel` with `:logging`) |
| `resultType`, `ttlMs`, `cacheScope`, result `_meta.serverInfo` | on results | absent |
| input from the client | `input_required` results | requests from server to client, inside the call |
| subscriptions | `subscriptions/listen` | list changes for the whole session with `:list-changed`; `resources/subscribe` with `:subscribe` |
| tools whose schemas are not objects | listed | hidden, and refused |
| HTTP errors | statuses by code | 200 |
| HTTP session | none | `Mcp-Session-Id`, GET stream, DELETE |
| Tasks | with `:tasks` | not available; tools run synchronously |

A handler written once serves both: `server/input-required` becomes an
`input_required` result or a request to the client, and `log!` follows the
request's level or the session's.
