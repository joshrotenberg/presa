# Transports

presa's protocol handling is pure functions over request and response
maps; transports carry them. `presa.connection` is what stdio and the
in-process transport build on: one client's session, each request on its
own thread, and cancellation. The HTTP server keeps sessions itself.

## stdio

`presa.transport.stdio/serve!` serves a server on stdin and stdout until
stdin closes, then waits for requests in flight. Options:

| Option | Default | Meaning |
|---|---|---|
| `:in`, `:out` | stdin, stdout | a Reader and a Writer, for tests |
| `:log` | a line on stderr | `(fn [& parts])` for diagnostics |
| `:max-line-bytes` | 2000000 | a longer line gets -32600 and is skipped |

`presa.transport.stdio/command` is the client side: it starts a server
subprocess and speaks to it. Options `:env`, `:dir`, `:stderr` (`:inherit`,
or a function of each line) and `:close-timeout-ms`.

<!-- no-run -->
```clojure
(client/connect (stdio/command ["bb" "-m" "my.server"] {:dir "/path/to/project"}))
```

## Streamable HTTP

`presa.transport.http/serve!` runs an http-kit server; `handler` returns the
ring handler, to mount in another http-kit server.

```clojure
(require '[presa.client :as client]
         '[presa.server :as server]
         '[presa.transport.http :as http])

(def srv (server/server {:name "web" :version "1"
                         :tools [{:name "ping" :description "Pong" :handler (fn [_ _] "pong")}]}))

(def running (http/serve! srv {:port 0}))   ; 0 picks a free port

(def c (client/connect (http/url (:url running))))

(client/text (client/call-tool c "ping"))
;; => "pong"

(client/close c)
((:stop! running))
```

What the server enforces, after snodo and the conformance runner:

- POST only, on one path (`:path`, default `/mcp`); GET and DELETE only for
  initialize-era sessions.
- `Content-Type: application/json` (415 otherwise) and an `Accept` that
  lists `application/json` and `text/event-stream` (406).
- On 2026-07-28, `MCP-Protocol-Version`, `Mcp-Method` and, for `tools/call`,
  `prompts/get`, `resources/read` and the task methods, `Mcp-Name` must match
  the body (400 with -32020). `Mcp-Param-*` headers must match the arguments
  a tool's schema mirrors with `x-mcp-header`.
- Errors get the status the runner expects (-32601 is 404, -32602 400,
  -32603 500); initialize-era clients get 200, as they treat other statuses
  as transport failures.
- A response is JSON until the handler sends progress or log messages, then
  an event stream.

Options of `handler` and `serve!`:

| Option | Default | Meaning |
|---|---|---|
| `:host`, `:port` (`serve!` only) | `127.0.0.1`, 0 | where to listen |
| `:path` | `/mcp` | the endpoint |
| `:allowed-origins` | loopback only | a set or predicate of other origins to accept (403 otherwise; guards against DNS rebinding) |
| `:allowed-hosts` | any | a set or predicate of `Host` values to accept |
| `:max-body-bytes` | 2000000 | a larger body gets 413 |
| `:keepalive-ms` | 15000 | comments on idle subscription streams, which also find clients that left |
| `:auth` | none | a resource-server config; see [authorization.md](authorization.md) |
| `:cancellation` | none | `:subject` lets a posted cancel stop the same subject's request |
| `:log` | none | `(fn [& parts])` for diagnostics |

`presa.transport.http/url` is the client transport. It mirrors the protocol
headers (base64-wrapping values that are not plain ASCII), sends
`Mcp-Param-*` for annotated arguments, leaves out tools whose annotations
are invalid, keeps initialize-era sessions and their GET stream, and
resumes a stream that ended early with `Last-Event-ID`. `:headers` adds
headers to every request, such as `Authorization`; `:token-provider` does
OAuth (see [authorization.md](authorization.md)); `:connect-timeout-ms`
(default 10000) and `:log` are the others.

## Initialize-era sessions

Over stdio the connection is the session. Over HTTP, `initialize` answers
with an `Mcp-Session-Id` that later requests carry; GET opens the session's
event stream, where the server sends requests and notifications outside
any request; DELETE ends the session. See [initialize-era.md](initialize-era.md).
