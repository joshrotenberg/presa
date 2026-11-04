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
- [ ] The runner's client leg: joshrotenberg/presa#5

## 6. Done since

- [x] The conformance server leg at 2025-11-25 and 2025-06-18
- [x] `subscriptions/listen`, with `list-changed!` and `resource-updated!`
- [x] Basic JSON Schema validation of tool arguments and output (presa.schema)
- [x] CI: tests on Babashka and the JVM, conventions, conformance lanes
      against baselines, a weekly canary

## Backlog

The open work is filed as issues, labelled by priority (`p1` to `p3`) and
area: <https://github.com/joshrotenberg/presa/issues>.

| Issue | Priority |
|---|---|
| joshrotenberg/presa#5 client conformance leg and its CI lane | p1 |
| joshrotenberg/presa#6 Tasks extension | p2 |
| joshrotenberg/presa#7 OAuth 2.1 resource server | p2 |
| joshrotenberg/presa#8 OAuth 2.1 client flows | p2 |
| joshrotenberg/presa#9 changing a running server's catalog | p2 |
| joshrotenberg/presa#10 limits on sizes, streams and notifications | p2 |
| joshrotenberg/presa#19 guides beyond the README | p2 |
| joshrotenberg/presa#20 release process | p2 |
| joshrotenberg/presa#11 to #18, #21, #22 | p3 |
