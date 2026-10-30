# presa

A Clojure library for building [Model Context Protocol](https://modelcontextprotocol.io)
servers and clients, for Babashka and the JVM from the same source.

presa targets MCP `2026-07-28`, the stateless revision: no initialize handshake,
the protocol version and client capabilities in each request's `_meta`,
`server/discover`, and `resultType` on every result. Initialize-era clients
(`2025-11-25` and `2025-06-18`) are planned as an opt-in.

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

## Layout (planned)

| Namespace | Role |
|---|---|
| `presa.protocol` | spec revisions, method names, error codes |
| `presa.jsonrpc` | JSON-RPC 2.0 messages and newline framing |
| `presa.server` | request dispatch, discovery, tools, resources, prompts |
| `presa.client` | calling a server, in process, over stdio or over HTTP |
| `presa.transport.stdio` | stdio server and client transport |
| `presa.transport.http` | Streamable HTTP over http-kit |

## Development

```sh
bb test          # tests under Babashka
bb test:jvm      # the same tests on the JVM (clojure -M:test)
```

## License

MIT
