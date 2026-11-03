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
- [ ] Resources, resource templates, `resources/read`, resource not found
- [ ] Prompts: list and get
- [ ] `completion/complete`
- [ ] Pagination with opaque cursors
- [ ] Caching hints (`ttlMs`, `cacheScope`)
- [ ] Content helpers: image, audio, resource links, embedded resources
- [ ] Logging (`notifications/message`, `logLevel`) and progress
      (`notifications/progress`) from handlers
- [ ] Cancellation (`notifications/cancelled`)

## 2. Multi round-trip requests

- [ ] `input_required` results with elicitation, sampling and roots requests
- [ ] `requestState` that the server can trust (signed)
- [ ] Capability checks against the client's declared capabilities

## 3. Streamable HTTP

- [ ] Server over http-kit: JSON and SSE responses, protocol headers,
      Origin and Host checks against DNS rebinding
- [ ] Initialize-era sessions over HTTP (`Mcp-Session-Id`)

## 4. Conformance

- [ ] A fixture server with the runner's scenario tools, resources and prompts
- [ ] A script that runs the official runner's server leg against it, with a
      recorded baseline

## 5. Client

- [ ] In-process, stdio and HTTP connections
- [ ] Version negotiation: `server/discover`, falling back to `initialize`
- [ ] Tools, resources, prompts and completion calls, with pagination
- [ ] Answering `input_required` with caller-supplied elicitation, sampling and
      roots functions
- [ ] Turning a server's tools into functions
- [ ] The runner's client leg

## 6. Later

- [ ] `subscriptions/listen`
- [ ] Tasks extension (`io.modelcontextprotocol/tasks`)
- [ ] OAuth 2.1 resource server and client flows
- [ ] Full JSON Schema validation of tool arguments
