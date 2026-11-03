# Roadmap

presa aims to be a complete MCP library for Clojure: servers and clients,
for Babashka and the JVM from the same source. snodo (Elixir) is the
reference for features and protocol behavior, and the official MCP
conformance runner is the measure. The wire details presa follows are in
[wire-2026-07-28.md](wire-2026-07-28.md); what real hosts send is in
[hosts.md](hosts.md).

Each milestone lands as its own branch with tests under bb and the JVM.

## 1. Server surface

- [x] Tools: list and call, text and structured results, `isError`
- [x] `server/discover`, per-request `_meta` validation, -32022
- [x] Initialize-era opt-in (`2025-11-25`, `2025-06-18`)
- [x] stdio transport, requests run concurrently
- [x] Resources, resource templates, `resources/read`, resource not found
- [x] Prompts: list and get
- [x] `completion/complete`
- [x] Pagination with opaque cursors
- [x] Caching hints (`ttlMs`, `cacheScope`)
- [x] Content helpers: image, audio, resource links, embedded resources
- [x] Logging (`notifications/message`, `logLevel`) and progress
      (`notifications/progress`) from handlers
- [x] Cancellation (`notifications/cancelled`)

## 2. Multi round-trip requests

- [x] `input_required` results with elicitation, sampling and roots requests
- [x] `requestState` that the server can trust (signed)
- [x] Capability checks against the client's declared capabilities

## 3. Streamable HTTP

- [x] Server over http-kit: JSON and SSE responses, protocol headers,
      Origin and Host checks against DNS rebinding
- [x] Initialize-era sessions over HTTP (`Mcp-Session-Id`)

## 4. Conformance

- [x] A fixture server with the runner's scenario tools, resources and prompts
- [x] A script that runs the official runner's server leg against it, with a
      recorded baseline

## 5. Client

- [x] In-process and stdio connections
- [x] HTTP connections
- [x] Version negotiation: `server/discover`, falling back to `initialize`
- [x] Tools, resources, prompts and completion calls, with pagination
- [x] Answering `input_required` with caller-supplied elicitation, sampling and
      roots functions
- [x] Turning a server's tools into functions
- [ ] The runner's client leg

## 6. Later

- [ ] The conformance server leg at 2025-11-25 and 2025-06-18
- [ ] Server-side `Mcp-Param-*` checks are done; request ids and `_meta`
      on notifications are not checked yet
- [ ] `subscriptions/listen`
- [ ] Tasks extension (`io.modelcontextprotocol/tasks`)
- [ ] OAuth 2.1 resource server and client flows
- [x] Basic JSON Schema validation of tool arguments and output (presa.schema)
- [ ] Full JSON Schema 2020-12 validation, as an optional library
