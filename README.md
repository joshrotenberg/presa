# presa

[![ci](https://github.com/joshrotenberg/presa/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/joshrotenberg/presa/actions/workflows/ci.yml)
[![conformance](https://github.com/joshrotenberg/presa/actions/workflows/conformance.yml/badge.svg?branch=main)](https://github.com/joshrotenberg/presa/actions/workflows/conformance.yml)
[![conformance canary](https://github.com/joshrotenberg/presa/actions/workflows/canary.yml/badge.svg)](https://github.com/joshrotenberg/presa/actions/workflows/canary.yml)
[![release](https://img.shields.io/github/v/release/joshrotenberg/presa?sort=semver)](https://github.com/joshrotenberg/presa/releases)
[![Clojars Project](https://img.shields.io/clojars/v/io.github.joshrotenberg/presa.svg)](https://clojars.org/io.github.joshrotenberg/presa)
[![MCP](https://img.shields.io/badge/MCP-2026--07--28-blue)](https://modelcontextprotocol.io)
[![Babashka](https://img.shields.io/badge/babashka-1.13.225%2B-green)](https://babashka.org)
[![JVM](https://img.shields.io/badge/JVM-21%20%7C%2025-green)](https://clojure.org)
[![license](https://img.shields.io/badge/license-MIT-lightgrey)](LICENSE)

A Clojure library for building [Model Context Protocol](https://modelcontextprotocol.io)
servers and clients, for Babashka and the JVM from the same source.

presa targets MCP `2026-07-28`, the stateless revision: no initialize handshake,
the protocol version and client capabilities in each request's `_meta`,
`server/discover`, and `resultType` on every result. Initialize-era clients
(`2025-11-25` and `2025-06-18`) are an opt-in, which Codex needs today: see
[docs/hosts.md](docs/hosts.md) for what Claude Code and Codex send.

presa is at 0.x: the API may change between minor versions until 1.0.

## Using presa

presa is on [Clojars](https://clojars.org/io.github.joshrotenberg/presa),
and each release is also a git tag (see [RELEASING.md](RELEASING.md)):

<!-- x-release-please-start-version -->
```clojure
;; deps.edn or bb.edn, from Clojars
{:deps {io.github.joshrotenberg/presa {:mvn/version "0.1.2"}}}

;; or from the tag; :git/sha is the tag's commit (see below)
{:deps {io.github.joshrotenberg/presa {:git/tag "v0.1.2" :git/sha "..."}}}
```
<!-- x-release-please-end -->

`git ls-remote https://github.com/joshrotenberg/presa.git refs/tags/<tag>`
gives the sha. Full JSON Schema validation on the JVM
(`presa.schema.networknt`) also needs
`com.networknt/json-schema-validator {:mvn/version "3.0.0"}`, which presa does
not pull in; pass `presa.schema.networknt/validator` as the server's
`:validator`.

## Goals

- **Babashka first.** Every namespace except the optional
  `presa.schema.networknt` loads in Babashka and on the JVM. The core
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
| `presa.server` | a server as data; dispatch for discovery, initialize, tools, resources, prompts, completion; input requests, subscriptions, the Tasks extension, scopes, changing a running server, pagination, caching hints, logging, progress and `:on-event` instrumentation |
| `presa.server.*` | internal to `presa.server`: `core` (errors, results, notifications), `catalog` (definitions), `access` (scopes), `shape` (results by revision, pagination, wire forms), `methods`, `tasks`, `subscriptions`, `dispatch` (revision resolution, routing) |
| `presa.content` | content blocks: text, image, audio, embedded resources, resource links |
| `presa.uri-template` | `{var}` and `{+var}` URI templates for resource templates |
| `presa.connection` | one client's connection to a server: session, concurrency, cancellation |
| `presa.client` | calling a server: negotiation, requests, timeouts, progress and log callbacks, input handlers, caching, `listen`, tasks, `tool-fns` |
| `presa.transport.stdio` | stdio: serve a server, or start a server subprocess as a client |
| `presa.input` | input requests (elicitation, sampling, roots) and the capabilities they need |
| `presa.request-state` | signed `requestState` tokens for multi round-trip requests |
| `presa.schema` | JSON Schema subset validation for tool arguments and output |
| `presa.schema.networknt` | full JSON Schema 2020-12 validation (JVM only, optional dependency) |
| `presa.transport.http` | Streamable HTTP: a server over http-kit, a client over `java.net.http` |
| `presa.transport.http.*` | internal to `presa.transport.http`: `server` (http-kit), `client` (`java.net.http`), `headers` (MCP header values) |
| `presa.proxy` | one server in front of several MCP servers |
| `presa.part` | a server composed from parts (tools, resources, prompts, instructions, `:close!`): combine with or without precedence, prefix, proxy another server, serve, call in process |
| `presa.bb` | parts from a bb.edn: tasks marked `:mcp/tool` as tools, files as resources, what a project directory offers |
| `presa.tasks` | the Tasks extension's task store and wire shapes |
| `presa.auth`, `presa.auth.jwt` | OAuth 2.1 resource server: metadata, bearer challenges, JWT verification (RS256, ES256, HS256, JWKS) |
| `presa.auth.client` | OAuth 2.1 client: discovery, registration, PKCE code flow, client credentials, JWT-bearer (workload identity) and enterprise-managed (`id-jag`) grants, DPoP, step-up |
| `presa.auth.dpop` | DPoP (RFC 9449): ES256 key pairs and proof JWTs for the OAuth client |

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
| [Authorization](docs/guides/authorization.md) | the OAuth 2.1 resource server and client, JWT verification, scopes |
| [Parts](docs/guides/parts.md) | servers composed from parts: combining, precedence, proxying, serving, calling in process, parts from a bb.edn |
| [The proxy](docs/guides/proxy.md) | one server in front of several, what it forwards, input from initialize-era backends |
| [Initialize-era clients](docs/guides/initialize-era.md) | serving 2025-11-25 and 2025-06-18, and what differs |
| [Logging, progress and instrumentation](docs/guides/observability.md) | `log!`, `progress!`, `:on-event` |
| [A complete example](docs/guides/example.md) | the notebook server, its REPL walkthrough and client, from a host |

Every Clojure block in the guides that is not marked no-run runs as part of
the test suite, and so does the notebook's REPL walkthrough.

Reference: [docs/wire-2026-07-28.md](docs/wire-2026-07-28.md) (the
revision on the wire), [docs/tasks-extension.md](docs/tasks-extension.md),
[docs/oauth-client.md](docs/oauth-client.md) (the client side of
authorization), [docs/hosts.md](docs/hosts.md) (what real hosts send),
[docs/roadmap.md](docs/roadmap.md).

## Conformance

[conformance/](conformance/README.md) runs the official MCP conformance
runner against a fixture server and a client harness. All 37 required
2026-07-28 server scenarios pass, as do nine of the ten Tasks extension
scenarios (the tenth, `tasks-status-notifications`, is a runner stub that
always skips), the 2025-11-25 and 2025-06-18 server suites, and every client
scenario at 2026-07-28 and 2025-11-25, including the scored authorization ones
(25 and 14) and the DPoP, workload identity, enterprise-managed and client
credentials extensions.

## The example servers

[`examples/presa/example/hello.clj`](examples/presa/example/hello.clj) has two
tools, `echo` and `add`, and serves every revision presa knows:

```sh
bb --config /path/to/presa/bb.edn -m presa.example.hello   # or, from the repository, clojure -M:example
claude mcp add presa-hello -- bb --config /path/to/presa/bb.edn -m presa.example.hello
codex mcp add presa-hello -- bb --config /path/to/presa/bb.edn -m presa.example.hello
```

[`examples/presa/example/notebook.clj`](examples/presa/example/notebook.clj) is
a notebook that uses most of presa together: structured tools, elicitation,
sampling, tasks, resources and a template, a prompt, completion,
subscriptions, logging, both transports and OAuth. It comes with a REPL
walkthrough and a command-line client; [the guide](docs/guides/example.md)
goes through it.

```sh
bb --config /path/to/presa/bb.edn -m presa.example.notebook   # or clojure -M:notebook
bb --config /path/to/presa/bb.edn -m presa.example.notebook-client
claude mcp add notebook -- bb --config /path/to/presa/bb.edn -m presa.example.notebook
codex mcp add notebook -- bb --config /path/to/presa/bb.edn -m presa.example.notebook
```

[`examples/presa/example/clojars.clj`](examples/presa/example/clojars.clj) is a
server for a real API, [Clojars](https://clojars.org): search, artifact
details, versions, dependencies of any version, groups, users and recent
releases, with structured results.

```sh
bb --config /path/to/presa/bb.edn -m presa.example.clojars   # or clojure -M:clojars
claude mcp add clojars -- bb --config /path/to/presa/bb.edn -m presa.example.clojars
codex mcp add clojars -- bb --config /path/to/presa/bb.edn -m presa.example.clojars
```

## Development

```sh
bb test                          # tests under Babashka
bb jvm-test                      # the same tests on the JVM, plus the JVM-only schema tests (clojure -M:test)
bb conformance 2026-07-28        # the conformance runner's server leg against the fixture, at a revision
bb conformance client 2026-07-28 # its client leg against the client harness
bb lint                          # no em dashes, conventional commits since origin/main
clojure -T:build jar             # the jar a release deploys to Clojars
```

## License

MIT
