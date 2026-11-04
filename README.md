# presa

A Clojure library for building [Model Context Protocol](https://modelcontextprotocol.io)
servers and clients, for Babashka and the JVM from the same source.

presa targets MCP `2026-07-28`, the stateless revision: no initialize handshake,
the protocol version and client capabilities in each request's `_meta`,
`server/discover`, and `resultType` on every result. Initialize-era clients
(`2025-11-25` and `2025-06-18`) are an opt-in, which Codex needs today: see
[docs/hosts.md](docs/hosts.md) for what Claude Code and Codex send.

presa is at 0.x: the API may change between minor versions until 1.0.

## Using presa

presa is released as git tags (see [RELEASING.md](RELEASING.md)):

<!-- x-release-please-start-version -->
```clojure
{:deps {io.github.joshrotenberg/presa {:git/tag "v0.0.0" :git/sha "..."}}}
```
<!-- x-release-please-end -->

`git ls-remote https://github.com/joshrotenberg/presa.git refs/tags/<tag>`
gives the sha.

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
| `presa.tasks` | the Tasks extension's task store and wire shapes |
| `presa.auth`, `presa.auth.jwt` | OAuth 2.1 resource server: metadata, bearer challenges, JWT verification (RS256, ES256, HS256, JWKS) |

[docs/roadmap.md](docs/roadmap.md) lists what is done and what is next.

## A first server

```clojure
(require '[presa.protocol :as protocol]
         '[presa.server :as server]
         '[presa.transport.stdio :as stdio])

(def srv
  (server/server
   {:name "greeter" :version "0.1.0"
    :versions protocol/versions          ; 2026-07-28 and the initialize-era revisions
    :tools [{:name "greet"
             :description "Greet someone by name."
             :input-schema {"type" "object" "properties" {"name" {"type" "string"}} "required" ["name"]}
             :handler (fn [{:strs [name]} _context] (str "Hello, " name "!"))}]}))

(defn -main [& _] (stdio/serve! srv) (shutdown-agents))
```

## Guides

| Guide | Covers |
|---|---|
| [Getting started](docs/guides/getting-started.md) | a server, a client in the same process, stdio, registering with Claude Code and Codex |
| [Tools, resources and prompts](docs/guides/components.md) | handlers and their results, schemas, templates, completion, pagination, changing a running server, caching hints |
| [The client](docs/guides/client.md) | connecting, calls, progress, logs, timeouts, handlers, caching |
| [Transports](docs/guides/transports.md) | stdio, Streamable HTTP and its options, sessions |
| [Asking the client for input](docs/guides/input.md) | elicitation, sampling, roots, signed request state, both eras |
| [Subscriptions](docs/guides/subscriptions.md) | `subscriptions/listen`, list changes, resource updates |
| [Tasks](docs/guides/tasks.md) | the Tasks extension: long-running tools, server and client |
| [Authorization](docs/guides/authorization.md) | the OAuth 2.1 resource server, JWT verification, scopes |
| [Initialize-era clients](docs/guides/initialize-era.md) | serving 2025-11-25 and 2025-06-18, and what differs |
| [Logging, progress and instrumentation](docs/guides/observability.md) | `log!`, `progress!`, `:on-event` |

Every code block in the guides runs as part of the test suite.

Reference: [docs/wire-2026-07-28.md](docs/wire-2026-07-28.md) (the
revision on the wire), [docs/tasks-extension.md](docs/tasks-extension.md),
[docs/hosts.md](docs/hosts.md) (what real hosts send),
[docs/roadmap.md](docs/roadmap.md).

## Conformance

[conformance/](conformance/README.md) runs the official MCP conformance
runner against a fixture server and a client harness. All 37 required
2026-07-28 server scenarios pass, as do the ten Tasks extension scenarios,
the 2025-11-25 and 2025-06-18 server suites, and every non-auth client
scenario.

## The example server

[`examples/presa/example/hello.clj`](examples/presa/example/hello.clj) has two
tools, `echo` and `add`, and serves every revision presa knows:

```sh
bb --config /path/to/presa/bb.edn -m presa.example.hello   # or, from the repository, clojure -M:example
claude mcp add presa-hello -- bb --config /path/to/presa/bb.edn -m presa.example.hello
codex mcp add presa-hello -- bb --config /path/to/presa/bb.edn -m presa.example.hello
```

## Development

```sh
bb test          # tests under Babashka
bb jvm-test      # the same tests on the JVM (clojure -M:test)
bb conformance   # the official conformance runner against the fixture
bb lint          # no em dashes, conventional commits since origin/main
```

## License

MIT
