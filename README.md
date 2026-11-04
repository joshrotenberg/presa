# presa

A Clojure library for building [Model Context Protocol](https://modelcontextprotocol.io)
servers and clients, for Babashka and the JVM from the same source.

presa targets MCP `2026-07-28`, the stateless revision: no initialize handshake,
the protocol version and client capabilities in each request's `_meta`,
`server/discover`, and `resultType` on every result. Initialize-era clients
(`2025-11-25` and `2025-06-18`) are an opt-in, which Codex needs today: see
[docs/hosts.md](docs/hosts.md) for what Claude Code and Codex send.

presa is pre-release: there is no published version, and the API will change.

## Goals

- **Babashka first.** Every namespace loads in Babashka and on the JVM. The core
  depends only on what Babashka bundles (`clojure.data.json`), and the HTTP
  transport on http-kit, which Babashka also bundles.
- **Plain data.** Requests, responses, tools, resources and prompts are maps; a
  server is a function from a request to a response.
- **Transports outside the core.** stdio (newline-delimited JSON-RPC) and
  Streamable HTTP over http-kit.
- **A client** that calls any MCP server over stdio or HTTP and turns its tools
  into functions.
- **Conformance** measured with the official MCP conformance runner.

## Layout

| Namespace | Role |
|---|---|
| `presa.protocol` | spec revisions, `_meta` keys, error codes, log levels |
| `presa.jsonrpc` | JSON-RPC 2.0 messages and newline framing |
| `presa.server` | a server as data; dispatch for discovery, initialize, tools, resources, prompts, completion; pagination, caching hints, logging and progress from handlers |
| `presa.content` | content blocks: text, image, audio, embedded resources, resource links |
| `presa.uri-template` | `{var}` and `{+var}` URI templates for resource templates |
| `presa.connection` | one client's connection to a server: session, concurrency, cancellation |
| `presa.client` | calling a server: negotiation, requests, timeouts, progress and log callbacks |
| `presa.transport.stdio` | stdio: serve a server, or start a server subprocess as a client |
| `presa.input` | input requests (elicitation, sampling, roots) and the capabilities they need |
| `presa.request-state` | signed `requestState` tokens for multi round-trip requests |
| `presa.schema` | JSON Schema subset validation for tool arguments and output |
| `presa.transport.http` | Streamable HTTP: a server over http-kit, a client over `java.net.http` |

[docs/roadmap.md](docs/roadmap.md) lists what is done and what is next.

## A tools server over stdio

```clojure
(ns my.server
  (:require [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.stdio :as stdio]))

(def srv
  (server/server
   {:name "my-server"
    :version "0.1.0"
    ;; 2026-07-28 alone is the default; add the initialize-era
    ;; revisions so Codex can connect too.
    :versions protocol/versions
    :tools [{:name "echo"
             :description "Return the given text."
             :input-schema {"type" "object"
                            "properties" {"text" {"type" "string"}}
                            "required" ["text"]}
             :handler (fn [{:strs [text]} _context] text)}]}))

(defn -main [& _]
  (stdio/serve! srv)
  (shutdown-agents))
```

A handler gets the call's arguments (a map with string keys) and a context
map (`:version`, `:client-info`, `:client-capabilities`, `:meta`,
`:request-id`). It returns a string (text content), a map (structured
content), a full result map with `"content"`, or `(server/tool-error msg)`; a
handler that throws becomes an `isError` result. `stdio/serve!` runs each
request on its own thread and binds `*out*` to `*err*` in handlers, so
printing cannot corrupt the protocol stream.

### A client

```clojure
(require '[presa.client :as client]
         '[presa.transport.stdio :as stdio])

(def c (client/connect (stdio/command ["bb" "--config" "/path/to/presa/bb.edn"
                                       "-m" "presa.example.hello"])))
(client/info c)                                    ; {:version "2026-07-28" ...}
(client/list-tools c)                              ; every page
(client/call-tool c "add" {"numbers" [1 2 3]})     ; {"structuredContent" {"sum" 6} ...}
((get (client/tool-fns c) "echo") {"text" "hi"})
(client/close c)
```

`client/in-process` connects to a server map in the same process, which is
how presa's own tests drive servers. `connect` tries `server/discover`
first and falls back to `initialize` when the server does not answer as a
2026-07-28 server. Requests take `:timeout-ms` (a timeout cancels the
request on the server), `:on-progress` and `:log-level`; `connect` takes
`:on-log`.

## Over HTTP

```clojure
(require '[presa.transport.http :as http])

(def running (http/serve! srv {:port 3000}))   ; {:url "http://127.0.0.1:3000/mcp" ...}
(def c (client/connect (http/url (:url running))))
((:stop! running))
```

The server follows the 2026-07-28 Streamable HTTP rules: POST only,
`MCP-Protocol-Version`, `Mcp-Method` and `Mcp-Name` checked against the
body, JSON responses that turn into event streams when a handler reports
progress or logs, and only loopback origins unless `:allowed-origins` says
otherwise. Initialize-era clients get an `Mcp-Session-Id`.
`http/handler` returns the ring handler for embedding in another http-kit
server.

## Asking the client for input

A tool, resource or prompt handler can ask the client for input before it
finishes:

```clojure
(require '[presa.input :as input])

{:name "greet"
 :handler (fn [_ ctx]
            (if-let [{:strs [content]} (server/input-response ctx "who")]
              (str "Hello, " (get content "name"))
              (server/input-required
               {"who" (input/elicit "Who should I greet?"
                                    {"type" "object" "properties" {"name" {"type" "string"}}})})))}
```

On 2026-07-28 this is an `input_required` result; the client answers on a
new request with the server's signed `requestState`, and presa checks both.
On an initialize-era session presa sends the request to the client and
calls the handler again. A presa client answers with its `:handlers`:

```clojure
(client/connect transport {:handlers {"elicitation/create"
                                      (fn [_] {"action" "accept" "content" {"name" "Ada"}})}})
```

## Conformance

[conformance/](conformance/README.md) runs the official MCP conformance
runner against a fixture server. All 37 required 2026-07-28 server
scenarios pass.

## The example server

[`examples/presa/example/hello.clj`](examples/presa/example/hello.clj) has two
tools, `echo` and `add`, and serves every revision presa knows. It runs from
any directory with

```sh
bb --config /path/to/presa/bb.edn -m presa.example.hello
```

or on the JVM from the repository root with `clojure -M:example`.

Register it with Claude Code (`--scope user` for every project; the default
is the current project only):

```sh
claude mcp add presa-hello -- bb --config /path/to/presa/bb.edn -m presa.example.hello
```

or in a project's `.mcp.json`:

```json
{"mcpServers": {"presa-hello": {"command": "bb",
                                "args": ["--config", "/path/to/presa/bb.edn", "-m", "presa.example.hello"]}}}
```

Register it with Codex:

```sh
codex mcp add presa-hello -- bb --config /path/to/presa/bb.edn -m presa.example.hello
```

or in `~/.codex/config.toml`:

```toml
[mcp_servers.presa-hello]
command = "bb"
args = ["--config", "/path/to/presa/bb.edn", "-m", "presa.example.hello"]
```

Claude Code names the tools `mcp__presa-hello__echo` and
`mcp__presa-hello__add`; Codex names them `mcp__presa_hello__echo` and
`mcp__presa_hello__add`.

## Development

```sh
bb test          # tests under Babashka
bb jvm-test      # the same tests on the JVM (clojure -M:test)
bb conformance   # the official conformance runner against the fixture
```

## License

MIT
