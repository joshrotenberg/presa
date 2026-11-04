# What Claude Code and Codex send as MCP clients

Recorded 2026-11-03 against:

- Claude Code 2.1.317 (`claude --version`)
- Codex CLI 0.165.0 (`codex --version`)

Both were run against `dev/host_probe.clj`, a stdio server that logs every
line it receives and sends, registered for one run only, so no host
configuration changed:

```sh
# Claude Code: a config file passed on the command line
claude -p --strict-mcp-config --mcp-config probe.json \
  --allowedTools mcp__probe__probe_echo "Call the probe_echo tool with text 'hi'"

# Codex: config overrides on the command line
codex exec --skip-git-repo-check \
  -c 'mcp_servers.probe.command="bb"' \
  -c 'mcp_servers.probe.args=["/abs/path/dev/host_probe.clj"]' \
  -c 'mcp_servers.probe.env={PROBE_LOG="/abs/path/codex.log",PROBE_MODE="echo"}' \
  "Call the probe_echo tool from the probe MCP server with text 'hi'"
```

`probe.json` is `{"mcpServers":{"probe":{"command":"bb","args":["/abs/path/dev/host_probe.clj"],"env":{...}}}}`.

## Summary

| | Claude Code 2.1.317 | Codex 0.165.0 |
|---|---|---|
| First message | `server/discover` with 2026-07-28 `_meta` | `initialize`, `protocolVersion` `2025-06-18` |
| Server answers `server/discover` | uses 2026-07-28, `_meta` on every request, no `initialize` | (never sends it) |
| Server rejects `server/discover` (-32601) | falls back to `initialize` with `2025-11-25` | (never sends it) |
| Server answers `initialize` with `2025-06-18` | | uses 2025-06-18 |
| Server answers `initialize` with `2026-07-28` | | sends `notifications/initialized`, then 2026-07-28 `_meta` on every request |
| Server rejects `initialize` (-32601) | | drops the server: its tools are not listed |

A server that serves only 2026-07-28 works with Claude Code and not with
Codex. To serve both, a server has to answer `initialize` as well, which in
presa is the initialize-era opt-in (`:versions` including `2025-06-18`).

## Claude Code, server answers `server/discover`

Long `_meta` values are cut with `...`.

```
<< {"jsonrpc":"2.0","id":"server-discover-probe-1","method":"server/discover","params":{"_meta":{
     "io.modelcontextprotocol/protocolVersion":"2026-07-28",
     "io.modelcontextprotocol/clientInfo":{"name":"claude-code","title":"Claude Code","version":"2.1.317",...},
     "io.modelcontextprotocol/clientCapabilities":{"roots":{"listChanged":true},"elicitation":{"form":{},"url":{}}}}}}
>> {"jsonrpc":"2.0","id":"server-discover-probe-1","result":{"resultType":"complete","supportedVersions":["2026-07-28"],...}}
<< {"method":"tools/list","jsonrpc":"2.0","id":0,"params":{"_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28",...}}}
<< {"method":"tools/call","params":{"name":"probe_echo","arguments":{"text":"hi"},"_meta":{
     "io.modelcontextprotocol/protocolVersion":"2026-07-28",...,
     "claudecode/toolUseId":"toolu_...","progressToken":1}},"jsonrpc":"2.0","id":1}
```

Every request carries the protocol version, client info and client
capabilities in `_meta`. The `server/discover` id is a string; later ids are
integers starting at 0. Claude Code accepted `tools/list` and `tools/call`
results without `resultType`.

## Claude Code, server rejects `server/discover`

```
<< {"jsonrpc":"2.0","id":"server-discover-probe-1","method":"server/discover","params":{"_meta":{...}}}
>> {"jsonrpc":"2.0","id":"server-discover-probe-1","error":{"code":-32601,"message":"Method not found"}}
<< {"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{"roots":{"listChanged":true},"elicitation":{"form":{},"url":{}}},"clientInfo":{"name":"claude-code",...}},"jsonrpc":"2.0","id":0}
>> {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"probe","version":"0"}}}
<< {"jsonrpc":"2.0","method":"notifications/initialized"}
<< {"method":"tools/list","jsonrpc":"2.0","id":1}
<< {"method":"tools/call","params":{"name":"probe_echo","arguments":{"text":"hi"},"_meta":{"claudecode/toolUseId":"toolu_...","progressToken":2}},"jsonrpc":"2.0","id":2}
```

After `initialize`, requests carry no protocol version in `_meta`.

## Codex, server answers `initialize` with the requested version

```
<< {"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{"experimental":{"codex/auth-change":{}},"elicitation":{"form":{},"url":{}}},"clientInfo":{"name":"codex-mcp-client","title":"Codex","version":"0.165.0"}}}
>> {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"probe","version":"0"}}}
<< {"jsonrpc":"2.0","method":"notifications/initialized"}
<< {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"progressToken":0}}}
<< {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"_meta":{"callId":"exec-...","x-codex-turn-metadata":{...},"threadId":"...","progressToken":1},"name":"probe_echo","arguments":{"text":"hi"}}}
```

`notifications/initialized` and `tools/list` arrive within a millisecond of
the `initialize` response, so a server has to settle the session before it
reads the next line.

## Codex, server answers `initialize` with `2026-07-28`

```
>> {"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2026-07-28",...}}
<< {"jsonrpc":"2.0","method":"notifications/initialized"}
<< {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{
     "io.modelcontextprotocol/protocolVersion":"2026-07-28",
     "io.modelcontextprotocol/clientInfo":{"name":"codex-mcp-client","title":"Codex","version":"0.165.0"},
     "io.modelcontextprotocol/clientCapabilities":{"experimental":{"codex/auth-change":{}},"elicitation":{"form":{},"url":{}}},
     "progressToken":0}}}
```

Codex knows the 2026-07-28 request shape but reaches it only through
`initialize`; it never sends `server/discover`. presa does not answer
`initialize` with `2026-07-28`, since that revision has no `initialize`.

## Codex, server rejects `initialize`

```
<< {"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18",...}}
>> {"jsonrpc":"2.0","id":0,"error":{"code":-32601,"message":"Method not found"}}
```

Nothing else is sent, and the model reported no `probe_echo` tool.

## The presa example server in both hosts

`examples/presa/example/hello.clj` serves `protocol/versions`. Run the same
way as the probe (`claude -p --strict-mcp-config --mcp-config ...` and
`codex exec -c 'mcp_servers.presa-hello...'`), each host listed both tools
and called them:

- Claude Code 2.1.317 connected through `server/discover` and listed
  `mcp__presa-hello__add` and `mcp__presa-hello__echo`. `add` with
  `[1, 2, 3.5]` returned `{"sum":6.5}`, and `echo` returned its text.
- Codex 0.165.0 connected through `initialize` at `2025-06-18` and listed
  `mcp__presa_hello__add` and `mcp__presa_hello__echo`. `add` returned
  `{"content":[{"type":"text","text":"{\"sum\":6.5}"}],"structuredContent":{"sum":6.5},"isError":false}`.

Not checked: Claude Code's `initialize` fallback against presa itself (presa
answers `server/discover` whenever it serves 2026-07-28), and a server that
serves only initialize-era revisions, which answers Claude Code's
`server/discover` probe with -32022 rather than -32601.

## The notebook example in both hosts

`examples/presa/example/notebook.clj`, run the same way on 2026-11-03 with
the prompt "list the names of its tools, create a note titled 'Groceries'
with body 'coffee and bread' tagged 'home', then search notes for 'coffee'":

- Claude Code 2.1.317 (`claude -p --strict-mcp-config --mcp-config
  notebook.json --allowedTools mcp__notebook__create_note
  mcp__notebook__search_notes`) listed the six tools, created `n3`, and
  the search returned `n1` (Shopping) and `n3` (Groceries).
- Codex 0.165.0 (`codex exec -c 'mcp_servers.notebook.command="bb"' -c
  'mcp_servers.notebook.args=[...]'`) did the same, with the same ids.

Not checked from the hosts: elicitation, sampling, tasks and
subscriptions, which the notebook's tests and walk-throughs cover with
presa's client.
