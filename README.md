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

| Namespace | Role | Status |
|---|---|---|
| `presa.protocol` | spec revisions, `_meta` keys, error codes | done |
| `presa.jsonrpc` | JSON-RPC 2.0 messages and newline framing | done |
| `presa.server` | request dispatch, discovery, initialize opt-in, tools | tools only; resources and prompts planned |
| `presa.transport.stdio` | stdio transport | server side; client side planned |
| `presa.client` | calling a server, in process, over stdio or over HTTP | planned |
| `presa.transport.http` | Streamable HTTP over http-kit | planned |

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

### The example server

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
```

## License

MIT
