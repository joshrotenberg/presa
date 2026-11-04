# MCP Tasks extension wire reference (SEP-2663, revision 2026-07-28)

This is a working reference for implementing the Tasks extension
(`io.modelcontextprotocol/tasks`, SEP-2663) on top of the 2026-07-28 base
revision in presa. It assumes `docs/wire-2026-07-28.md` (cited as `wire §N`)
and does not repeat it: envelope, request `_meta`, `resultType`, MRTR,
`subscriptions/listen`, error codes, and HTTP admission are described there.

No copy of the SEP-2663 prose or of a Tasks JSON schema was available. Every
fact below cites one of these sources:

| Tag | Source |
|---|---|
| `T` | snodo `extensions/tasks/lib/snodo/extensions/tasks.ex` |
| `ext:<file>` | `snodo:extensions/tasks/lib/snodo/extensions/tasks/<file>` |
| `ext-test:<file>` | `snodo:extensions/tasks/test/<file>` |
| `README` | `snodo:extensions/tasks/README.md` |
| `snodo:<path>` | another file under `~/Code/github.com/joshrotenberg/snodo/` |
| `fixture` | `snodo:conformance/support/tasks.ex` |
| `schema` | the pinned core 2026-07-28 schema (`snodo:interop/schema_validation/schema.json`, commit `5f5440bb`) |
| `runner` | `@modelcontextprotocol/conformance@0.2.0-alpha.12`, `snodo:conformance/node_modules/@modelcontextprotocol/conformance/dist/index.js`. Cited by scenario and check id, or by minified helper name |

Runner helpers referenced by name: `Tt` (stateless client), `bt` (builds
request `_meta`), `yt`/`vt` (request headers), `K` (polls `tasks/get` every
200 ms until terminal, 10 s limit), `fa` (polls every 200 ms until a given
status or terminal, limit passed by caller), `da` (probe params), `qe`/`He`
(wire schema validation).

The schema is the strongest source for shapes, but it defines nothing for
Tasks (section 1). So here the runner is the strongest source for what is
scored, and snodo is the only source for everything else. snodo reports all
35 Tasks-specific runner assertions passing on alpha.12, with the
notification check skipped upstream and all nine generic wire-schema checks
passing (`snodo:conformance/README.md:169-176`). snodo pins the Tasks
specification at commit `0d0a6bd4` (`snodo:conformance/results/2026-07-28-tasks-alpha.11-summary.json`);
that text is not vendored.

## 1. What the core schema says

- The pinned core schema has no `$defs` for tasks: no `Task`,
  `CreateTaskResult`, `DetailedTask`, `GetTaskRequest`, or
  `notifications/tasks`. The only mention is the example key
  `"io.modelcontextprotocol/tasks"` in the `ServerCapabilities.extensions`
  description (`schema $defs.ServerCapabilities`).
- `$defs.ResultType` is a plain string. Its description names only
  `complete` and `input_required`; `task` comes from the extension (wire §3).
- `$defs.SubscriptionFilter` has no `taskIds` key, but it is an open object
  (no `additionalProperties: false`), so an extra `taskIds` validates.
- `$defs.MissingRequiredClientCapabilityError` requires
  `data.requiredCapabilities` (a `ClientCapabilities`). That matters in
  section 9.
- The runner's validator knows `task` as an extension result type
  (`runner He = new Set(["task"])`). A result with `resultType: "task"` is not
  validated as `CallToolResult`. A `tasks/*` result has no method definition,
  so it is validated only as a generic `JSONRPCResultResponse`, where `Result`
  requires a string `resultType` (runner `qe`). alpha.11 lacked this and failed
  eight wire-schema checks on snodo's `CreateTaskResult`
  (`snodo:conformance/README.md:172-176`).

## 2. Capability negotiation

### 2.1 Server

The server advertises the extension in `server/discover` with empty settings:

```json
"capabilities": {
  "tools": {"listChanged": true},
  "extensions": {"io.modelcontextprotocol/tasks": {}}
}
```

(`fixture:203-208`, `snodo:conformance/support/fixture.ex:399-405`). There must be
no v1-style `capabilities.tasks` key (runner `tasks-capability-negotiation`,
check `tasks-extension-advertised`), and no `tools/list` task-support
decoration (`README:79-80`).

### 2.2 Client

There is no session in 2026-07-28. A client opts in per request by putting
the extension in that request's `_meta` client capabilities:

```json
"_meta": {
  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
  "io.modelcontextprotocol/clientCapabilities": {
    "elicitation": {},
    "extensions": {"io.modelcontextprotocol/tasks": {}}
  }
}
```

- The runner's "session" is only a default `_meta` its client stamps on each
  request (runner `Tt`, `bt`). Its per-request opt-in check sends
  `_meta.io.modelcontextprotocol/clientCapabilities` explicitly in `params`; the
  runner merges it shallowly, so it replaces the default capabilities object
  for that request (runner `tasks-capability-negotiation`, check
  `tasks-per-request-meta-opt-in`). A server that evaluates capabilities per
  request, as wire §2 already requires, passes this with no extra logic.
- There is no other opt-in: no `task` param and no Tasks-specific `_meta` key
  (runner `tasks-dispatch-and-envelope` description, "The client does NOT send
  a `task` hint param").
- snodo's negotiation succeeds only when both client and server settings are
  `{}`; anything else is -32602 `The Tasks extension defines no settings`
  (`T:97-104`). snodo negotiates before method resolution, so non-empty client
  settings fail every request that declares them, not only task requests
  (`snodo:lib/snodo/server.ex:489-501`, `snodo:lib/snodo/extension/registry.ex:108-124`).
  That strictness is snodo's choice; I found no runner check for it.
- snodo's extension hooks (middleware, `Mcp-Name` policy, `taskIds` filter)
  run only while the server advertises the extension
  (`registry.ex:501-509`, `576-589`).

### 2.3 Not declared

| Request | Server advertises Tasks, client did not declare it | Source |
|---|---|---|
| `tasks/get`, `tasks/update`, `tasks/cancel` | -32021 | runner `tasks-capability-negotiation`, check `sep-2663-tasks-methods-non-declaring`; `registry.ex:438-469`; `T:209-213` |
| `tools/call` of a tool whose policy is "required" | -32021 | runner `tasks-required-task-error`; `T:323-327` |
| `tools/call` of an optional or sync tool | runs synchronously, ordinary `CallToolResult` | runner check `sep-2663-server-rejects-undeclared-client`; `T:315-321` |
| `subscriptions/listen` with `notifications.taskIds` | -32021 | `T:714-716`, `ext-test:tasks_subscription_acceptance_test.exs:101-122` |

If the server does not advertise Tasks at all, snodo answers the `tasks/*`
methods with -32601 (`registry.ex:449-469`).

The error, identical in every case (`T:758-769`):

```json
{"jsonrpc": "2.0", "id": 5, "error": {
  "code": -32021,
  "message": "Missing required client capability",
  "data": {"requiredCapabilities": {"extensions": {"io.modelcontextprotocol/tasks": {}}}}}}
```

HTTP status 400 (wire §12).

## 3. `tools/call` and task creation

### 3.1 Request

An ordinary `tools/call` (wire §5.2). Task creation is server-directed: the
server decides, per tool and per call, from its own registration and the
request's capabilities (runner `tasks-lifecycle` description). A v1-style
`params.task` (`{"ttl": 60000, "pollInterval": 100}`) must be accepted and
ignored. It must not turn a sync tool into a task (runner
`tasks-dispatch-and-envelope`, check `sep-2663-legacy-task-param-ignored`;
`README:78-79`).

### 3.2 snodo's decision (design input)

The policy is application configuration, not wire data (`T:19-23`,
`T:456-468`). Per tool name it is `:sync` (the default for an unlisted tool),
`:optional`, `:required`, `{mode, task_options}`, or a function of
`(params, context)` that returns one of those:

| Policy | Client declared Tasks | Client did not |
|---|---|---|
| sync | run synchronously | run synchronously |
| optional | create a task | run synchronously |
| required | create a task | -32021 |

`T:315-339`. The order inside snodo's `tools/call` path:

1. Core `tools/call` validation (name, arguments, `inputResponses`,
   `requestState` shapes; wire §5.2, §10.3).
2. Policy decision (`T:232-239`). A raising policy is -32603.
3. -32021 for required tools without the declaration (`T:323-327`).
4. For a task: the same lookup, authorization, and argument checks as a
   direct call, without running it (`T:349-355`;
   `snodo:guides/extensions.md:21-23`). An unknown tool is -32602
   `Unknown tool: <name>`. Invalid arguments get the direct call's
   `isError: true` result synchronously, and no task is created
   (`README:151-154`).
5. Build and size-check the work input (-32602 `Task input is larger than
   <n> bytes`, default 262144 bytes) (`T:584-599`).
6. Create atomically and start the worker (`T:357-373`, `T:388-394`). At a
   capacity limit: -32603 `Task capacity exhausted`, `data: {"retryable":
   true, "limit": "max_jobs"|"max_tasks"|"max_active_tasks_per_scope"}`,
   nothing stored (`T:771-783`, `README:283-310`).
7. Return the `CreateTaskResult`.

### 3.3 `CreateTaskResult`

A flat `Result & Task`. There is no nested `task` wrapper (runner
`tasks-lifecycle`, check `sep-2663-result-type-task-on-create`):

```json
{"jsonrpc": "2.0", "id": 3, "result": {
  "resultType": "task",
  "taskId": "pS3xq0cLrM7f2n1kHf8WbQZ7uD0aYt4e",
  "status": "working",
  "statusMessage": "Task accepted",
  "createdAt": "2026-11-03T18:04:05.123Z",
  "lastUpdatedAt": "2026-11-03T18:04:05.123Z",
  "ttlMs": 3600000,
  "pollIntervalMs": 100,
  "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "snodo-conformance", "version": "0.1.0"}}
}}
```

| Field | Rule | Source |
|---|---|---|
| `resultType` | `"task"` | runner `sep-2663-result-type-task-on-create`; `ext:task.ex:129-134` |
| `taskId` | non-empty string, required at top level. snodo: 24 random bytes, base64url without padding (32 chars), at most 256 bytes | runner same check; `T:307-313`, `T:548-560` |
| `status` | required; `"working"` at creation in snodo | runner same check; `ext:task.ex:40` |
| `createdAt`, `lastUpdatedAt` | strings the runner tests against `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}`. snodo writes UTC ISO 8601 at millisecond precision | runner `ua`; `ext:task.ex:154-159` |
| `ttlMs` | key must be present; `null` or a positive integer | runner `tasks-wire-fields`, check `tasks-wire-field-renames`; `ext:task.ex:267-277` |
| `pollIntervalMs` | optional; a positive integer when present | same check |
| `statusMessage` | optional string. snodo: `"Task accepted"` at creation | `ext:task.ex:275`, `T:448` |
| `_meta` | snodo stamps `serverInfo` as on any core result | `snodo:lib/snodo/protocol/v2026_07_28.ex:503-506` |

Must be absent: `task`, `result`, `error`, `inputRequests`
(runner `sep-2663-result-type-task-on-create`), `requestState` (runner
`tasks-request-state-removal`, check `tasks-create-result-no-request-state`;
`tasks-mrtr-composition`), and the v1 names `ttl` and `pollInterval` (runner
`tasks-wire-field-renames`). Durations are integer milliseconds; no fractional
values (same check).

snodo's defaults are `ttlMs` 3600000 and `pollIntervalMs` 250; the fixture
sets 3600000 and 100 (`T:440-454`, `fixture:191-192`). snodo emits
`"ttlMs": null` when the TTL is unset, meaning never reaped (`ext:task.ex:273`,
`README:412-420`).

Creation is strongly consistent: a `tasks/get` sent immediately after the
`CreateTaskResult` arrives must resolve the task (runner
`tasks-dispatch-and-envelope`, check `sep-2663-durable-create-strong-consistency`).
snodo commits the task and its work descriptor before returning (`README:186-187`,
`README:374-375`).

### 3.4 Immediate-result shortcut

For a task-supporting tool, the server MAY skip the task and return an
ordinary `CallToolResult` with `resultType: "complete"`. The runner accepts
either response for `slow_compute` with `seconds: 0` (runner
`tasks-dispatch-and-envelope`, check `tasks-immediate-result-shortcut`). snodo
never takes the shortcut when the client declared Tasks (`T:317-321`).

### 3.5 `resultType: "complete"` everywhere else

Every response on the tools and tasks surface except a `CreateTaskResult`
carries `resultType: "complete"`: sync `tools/call`, `tasks/get`, and the
`tasks/update` and `tasks/cancel` acknowledgements (runner
`tasks-dispatch-and-envelope`, check
`tasks-result-type-complete-on-non-task-responses`; `T:154-164`). A sync
result must not carry `taskId` or `resultType: "task"` (runner
`tasks-lifecycle`, check `tasks-sync-tool-call`).

## 4. Task methods

Three methods, each a 2026-07-28 request with `params` required (`T:66`,
`T:77-95`, `snodo:lib/snodo/extension/method.ex`). `tasks/list` and
`tasks/result` are removed: -32601 (runner `tasks-dispatch-and-envelope`, checks
`sep-2663-tasks-result-removed-method-not-found`, `tasks-removed-tasks-list`;
`README:78`). The runner probes them with `{"taskId":
"tasks-conformance-isolation-probe"}` and `{}` (runner `da`), so -32601 must
win over any params check.

### 4.1 Statuses

`working`, `input_required`, `completed`, `failed`, `cancelled`; no others in
snodo or the runner (`ext:task.ex:12`; runner `K` treats `completed`, `failed`,
`cancelled` as terminal).

```text
working        -> input_required | completed | failed | cancelled
input_required -> working | completed | failed | cancelled
completed | failed | cancelled -> immutable
```

(`README:368-372`). Any event on a terminal task leaves it unchanged
(`ext:transition.ex:41-44`), so a late worker result cannot resurrect a
cancelled task.

### 4.2 `tasks/get`

Params: `{"taskId": string, "_meta": {...}}`.

Result is the DetailedTask: the base fields of section 3.3 with
`resultType: "complete"`, plus exactly one status-specific payload
(`ext:task.ex:137-151`, `279-291`):

| `status` | Extra field | Must be absent |
|---|---|---|
| `working` | none | `result`, `error` |
| `input_required` | `inputRequests`: non-empty map, key to `{"method", "params"}` | `result`, `error` |
| `completed` | `result`: the full `CallToolResult` | `error` |
| `failed` | `error`: `{"code": int, "message": string, "data"?}` | `result` |
| `cancelled` | none | `result`, `error` |

`requestState` must be absent for every status (runner
`tasks-request-state-removal`, check `tasks-get-detailed-no-request-state`).
snodo enforces the payload table as a struct invariant (`ext:task.ex:311-355`).

Completed:

```json
{"jsonrpc": "2.0", "id": 9, "result": {
  "resultType": "complete",
  "taskId": "pS3xq0cLrM7f2n1kHf8WbQZ7uD0aYt4e",
  "status": "completed",
  "createdAt": "2026-11-03T18:04:05.123Z",
  "lastUpdatedAt": "2026-11-03T18:04:07.130Z",
  "ttlMs": 3600000,
  "pollIntervalMs": 100,
  "result": {
    "resultType": "complete",
    "content": [{"type": "text", "text": "Completed lifecycle-create"}],
    "isError": false,
    "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "snodo-conformance", "version": "0.1.0"}}
  }
}}
```

- `result.content` must be a non-empty array (runner `tasks-lifecycle`, check
  `sep-2663-tasks-get-status-completed`).
- `result._meta` must not carry the v1 `io.modelcontextprotocol/related-task`
  key; the root `taskId` already identifies the task (runner `tasks-wire-fields`,
  check `tasks-no-related-task-meta-on-inlined-result`). snodo never emits it.
- snodo stores the result exactly as the core dialect shaped it for a direct
  call, so the inlined result carries its own `resultType: "complete"` and
  `serverInfo` `_meta` (`T:410-422`). The runner neither requires nor rejects
  either.
- snodo shapes `tasks/*` results in the extension and does not stamp
  `serverInfo` `_meta` on the outer result (`registry.ex:294-304`,
  `snodo:lib/snodo/server.ex:620-622`). The schema says SHOULD on every
  response (`$defs.ResultMetaObject`); presa can add it.

Failed:

```json
{"resultType": "complete", "taskId": "Zk2...", "status": "failed",
 "statusMessage": "Tool raised an exception",
 "createdAt": "...", "lastUpdatedAt": "...", "ttlMs": 3600000, "pollIntervalMs": 100,
 "error": {"code": -32603, "message": "Tool raised an exception"}}
```

`error.code` must be a number, `error.message` a string, and `result` absent
(runner `tasks-lifecycle`, check `sep-2663-tasks-get-status-failed`). snodo uses
the JSON-RPC error's message as `statusMessage` and never leaks the exception
detail (`T:401-402`, `snodo:lib/snodo/router.ex:973`,
`ext-test:tasks_extension_acceptance_test.exs:240-244`).

Input required:

```json
{"resultType": "complete", "taskId": "Hq9...", "status": "input_required",
 "statusMessage": "Task accepted",
 "createdAt": "...", "lastUpdatedAt": "...", "ttlMs": 3600000, "pollIntervalMs": 100,
 "inputRequests": {
   "confirm-delete": {"method": "elicitation/create", "params": {
     "message": "Delete mrtr-input.txt?",
     "requestedSchema": {"type": "object",
                         "properties": {"confirm": {"type": "boolean"}},
                         "required": ["confirm"]}}}}}
```

`inputRequests` must be a non-null object with at least one entry, and each
entry must carry `method` (runner `tasks-mrtr-input`, check
`sep-2663-tasks-get-status-input-required`). Entries use the bare
`InputRequest` shape of wire §10.1.

`statusMessage` values are snodo's: `"Task accepted"` at creation and kept
through input rounds, absent on completed, the error message on failed,
`"Cancellation requested"` on cancelled (`T:448`, `ext:task.ex:62-109`,
`250-265`, `ext:transition.ex:175-178`).

### 4.3 `tasks/update`

Params: `{"taskId": string, "inputResponses": {key: response}, "_meta": {...}}`.
`inputResponses` is required and must be an object (`T:111-121`). The values
are bare results as in wire §10.3.

```json
{"jsonrpc": "2.0", "id": 12, "method": "tasks/update", "params": {
  "taskId": "Hq9...",
  "inputResponses": {"confirm-delete": {"action": "accept", "content": {"confirm": true}}},
  "_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28",
            "io.modelcontextprotocol/clientCapabilities": {"elicitation": {}, "extensions": {"io.modelcontextprotocol/tasks": {}}}}}}
```

Result: an empty acknowledgement.

```json
{"jsonrpc": "2.0", "id": 12, "result": {"resultType": "complete"}}
```

- The ack must carry `resultType: "complete"` and none of `taskId`, `status`,
  `result`, `error`, `inputRequests`. The new state is read with the next
  `tasks/get` (runner `tasks-mrtr-input`, check `tasks-mrtr-tasks-update-resumes`).
- Keys that match no pending request are ignored and still acked. The runner
  sends `{"unknown-key": {"ignored": true}}` and expects the ack
  (runner check `tasks-result-type-complete-on-non-task-responses`;
  `ext:transition.ex:72-92`, `README:359-360`).
- An update for a terminal task is acked with no change
  (`ext:transition.ex:41-44`).
- snodo does not check a response against the request it answers (an
  `ElicitResult` for an elicitation and so on); it only requires each value
  to be an object (`ext:event.ex:212-220`). Validating content is left to the
  tool (`README:341-342`).

### 4.4 `tasks/cancel`

Params: `{"taskId": string, "_meta": {...}}`. Result: the same empty ack
`{"resultType": "complete"}` with no task fields (runner `tasks-lifecycle`,
check `sep-2663-cancel-ack-empty-result`).

- The task settles to `cancelled`, seen on the next `tasks/get`. The runner
  cancels a running `slow_compute` and polls with `K` to terminal; snodo sets
  the status atomically and then stops the worker (`ext:runner.ex:254-268`).
- Cancel on a terminal task returns the same ack and changes nothing
  (runner check `tasks-cancel-terminal-idempotent-ack`;
  `ext:transition.ex:41-44`). -32602 is reserved for unknown task ids
  (runner `tasks-lifecycle` description).

### 4.5 Validation order for `tasks/*`

snodo's order, which satisfies every runner probe:

1. HTTP admission and headers, including `Mcp-Name` (section 7; wire §13.2).
2. Core request `_meta` (wire §2), then extension settings negotiation
   (section 2.2).
3. Method lookup: no route is -32601 (`registry.ex:129-139`).
4. Capability: server advertises Tasks and the request did not declare it:
   -32021 (`registry.ex:438-469`). The runner sends `tasks/update` with
   `{"taskId": "gate-test", "inputResponses": {}}` and expects -32021, so this
   precedes params checks and lookup (runner check
   `sep-2663-tasks-methods-non-declaring`).
5. Params (`T:106-121`, `T:658-679`):
   - `taskId` missing, not a string, or empty: -32602 `A non-empty taskId is required`.
   - `taskId` over 256 bytes: -32602 `taskId is longer than 256 bytes`.
   - `tasks/update` without an object `inputResponses`: -32602 `tasks/update requires an inputResponses object`.
   - an empty key or a non-object value: -32602 `inputResponses is invalid`.
   These run before lookup (`ext-test:tasks_extension_acceptance_test.exs:121-137`).
6. Authorize and look up. Unknown, expired, or another scope's task: -32602
   `Unknown or inaccessible taskId` (`T:521-546`, `T:785`). The runner sends
   `tasks/get` for `tasks-conformance-nonexistent-12345` and expects -32602
   (runner check `sep-2663-tasks-get-invalid-task-id-32602`). snodo answers
   unknown ids on `tasks/update` and `tasks/cancel` the same way
   (`ext-test:tasks_extension_acceptance_test.exs:442-449`).
7. Act and ack.

HTTP statuses: -32601 404, -32602 400, -32021 400, -32020 400, -32603 500
(wire §12). The runner's client reads the JSON-RPC error and does not check
the HTTP status in the tasks scenarios (runner `Tt`).

## 5. Execution semantics

### 5.1 Tool errors vs protocol errors

- A tool that ran and reported an error: `status: "completed"` with
  `result.isError: true` (runner `tasks-lifecycle`, check
  `sep-2663-tool-error-uses-completed-status`). `failed` is reserved for
  protocol errors.
- A protocol-level failure (a raise, an internal error, an invalid result):
  `status: "failed"` with an inlined JSON-RPC `error` and no `result`
  (runner check `sep-2663-tasks-get-status-failed`; `T:396-422`).
- In snodo a worker that returns `input_required` or another task handle as its
  final result fails with -32603 `Task worker returned a continuation; use
  Tasks.await_input/3 or finish MRTR before creating a task`
  (`T:430-438`). A non-JSON result fails with -32603 `Task worker returned a
  non-JSON result` (`ext-test:tasks_extension_acceptance_test.exs:325-345`).

### 5.2 TTL

- A task must stay readable through `tasks/get` for its whole `ttlMs`; a
  server must not expire it early. The runner reads the task 500 ms after it
  reaches terminal (runner `tasks-wire-fields`, check `tasks-no-early-ttl-expiry`).
- snodo measures TTL from `createdAt`, active or not. Once `createdAt +
  ttlMs` passes on the store clock, every read and request mutation treats the
  task as unknown (-32602), before the reaper deletes it; `null` is never
  reaped. The reaper runs every 60 s by default (`ext:store.ex:22-26`,
  `README:412-424`). The README notes the protocol permits either making an
  expired task unavailable or deleting it.

### 5.3 Detached execution

The worker outlives the request that created it. snodo runs it with a
detached context: protocol version, capabilities, negotiated extensions, and
the projected principal survive; transport handles, request id, progress
token, request `_meta`, and request cancellation do not
(`ext:execution_context.ex:1-31`). So a task cannot send progress or log
notifications on the creating request's stream.

## 6. MRTR and Tasks

The two input lifecycles are separate (`README:335-343`;
`snodo:guides/interactive-operations.md:215-217`).

### 6.1 Input inside a task

1. The worker parks on an input request under a key. The task becomes
   `input_required` and `inputRequests` gains `{key: request}`
   (`ext:task.ex:62-86`). Keys are unique for the task's lifetime; reusing one
   with a different request is an error (`ext:task.ex:70-71`, `README:361-364`).
2. The client reads it with `tasks/get` (section 4.2) and answers with
   `tasks/update` (section 4.3).
3. Matched keys leave `inputRequests`. With none left the status returns to
   `working`, else it stays `input_required` (`ext:task.ex:90-109`).
4. The worker resumes and ends terminal or parks again.

Partial fulfillment is required: with two pending keys, an update answering
one is acked, the task stays `input_required`, the answered key is gone, and
the other remains; answering the second completes the task (runner
`tasks-mrtr-input`, check `tasks-mrtr-partial-fulfillment`).

There is no `requestState` anywhere in task input: not on the
`CreateTaskResult`, not on `tasks/get` for any status, and `tasks/update`
takes none (runner `tasks-request-state-removal`; it creates a `slow_compute`
with `seconds: 60` and checks both absences on a `working` task).

snodo does not check task input requests against the creating request's
client capabilities (no -32021 path in `ext:runner.ex` or `T:287-305`). Whether
the spec requires that is not visible in the sources. The runner declares
`elicitation` in every scenario that parks (runner `tasks-mrtr-input`
bootstrap).

### 6.2 Synchronous MRTR before task creation

A tool may gather input with ordinary MRTR (wire §10) and create the task only
on the final round (runner `tasks-mrtr-composition`, check
`sep-2663-mrtr-synchronous-before-task-creation`):

Round 1, `tools/call` `test_tool_with_task` with `arguments: {}`:

```json
{"resultType": "input_required",
 "inputRequests": {"user_name": {"method": "elicitation/create", "params": {
   "message": "What is your name?",
   "requestedSchema": {"type": "object", "properties": {"name": {"type": "string"}}, "required": ["name"]}}}},
 "requestState": "tasks-composition-round-1"}
```

It must be an `InputRequiredResult` and carry no `taskId`.

Round 2, the client retries with `inputResponses: {"user_name": {"action":
"accept", "content": {"name": "Alice"}}}` and the echoed `requestState` (runner
`Ui`). The response must be a `CreateTaskResult` (`resultType: "task"`, top
level `taskId`) with no `requestState` and no `inputRequests`. The task must
complete, and `result.content[0].text` must contain `Alice`.

snodo implements this with a per-call policy: `:sync` when `inputResponses` is
empty, `:required` otherwise (`fixture:210-212`). The worker reads the round-2
`inputResponses` from the original request params (`T:271-277`,
`fixture:126-162`). The fixture's `requestState` is a fixed unsigned string; a
real tool would sign it (wire §10.4).

## 7. HTTP headers for task methods

- `Mcp-Method` is required on every POST and must equal the body method
  (wire §13.5).
- `tasks/get`, `tasks/update`, and `tasks/cancel` also require `Mcp-Name`
  equal to `params.taskId` (runner `tasks-request-headers` description, citing
  SEP-2663 "Streamable HTTP: Routing Headers"; `T:215-228`).
- snodo applies the same value rules as other mirrored headers: case-insensitive
  name, trimmed value, a repeated header is an error, and the value may be
  `=?base64?...?=` wrapped (`snodo:lib/snodo/transport/streamable_http.ex:439-541`;
  wire §13.5, §13.6). When `params.taskId` is absent, snodo skips the comparison
  but still requires the header, and the dialect reports -32602
  (`streamable_http.ex:456-461`, `488`).
- Failures are 400 -32020.
- The runner's client always sends `MCP-Protocol-Version`, `Mcp-Method`, and,
  for `tools/call`/`prompts/get`/`resources/read` and the three task methods,
  `Mcp-Name` from `name`/`uri`/`taskId`. It sends no `Mcp-Name` for
  `tasks/result` or `tasks/list`. Per-call extra headers replace these,
  matched case-insensitively (runner `vt`, `yt`).

What `tasks-request-headers` sends and expects:

| Check | Request | Expect |
|---|---|---|
| `tasks-headers-tolerate-mcp-method-on-tools-call` | `tools/call` `greet` `{"name": "sep-2243"}` with `Mcp-Method: tools/call` | `resultType: "complete"`, `content[0].text == "Hello, sep-2243!"` |
| `sep-2663-routing-headers-accepted-on-tasks-get` | `tasks/get` with `Mcp-Method: tasks/get`, `Mcp-Name: <taskId>` for a `slow_compute` `seconds: 60` task | result with that `taskId` and a `status` |
| `tasks-headers-reject-mismatched-method` | `tools/call` `greet` with `Mcp-Method: tasks/get` (and `Mcp-Name: greet`) | error -32020 |
| `sep-2663-server-rejects-mismatched-mcp-name-on-tasks-get` | `tasks/get` with `Mcp-Name: sep-2663-wrong-task-id-for-routing-header-mismatch` | error -32020 |

The scenario then cancels its task.

## 8. Status notifications

- Method `notifications/tasks`, delivered on a `subscriptions/listen` stream
  (wire §11), not on the `tools/call` response stream (runner
  `tasks-status-notifications` description and skip reason).
- Optional: "Servers MAY emit" (same description). When sent, params carry
  `taskId` and `status`; terminal ones MAY inline `result` or `error` in the
  DetailedTask shape.
- The runner's scenario emits one SKIPPED check, `tasks-status-notifications`,
  pending a rewrite against `subscriptions/listen`. It sends no traffic, so it
  adds no `wire-schema-valid` check. snodo's whole-scenario report counts
  SKIPPED as not passed (wire §17).

snodo's shape, for design reference only:

- The client opts in with `notifications.taskIds` on `subscriptions/listen`.
  The list must hold non-empty strings of at most 256 bytes, at most 100 of
  them, else -32602; it requires the Tasks declaration, else -32021
  (`T:170-182`, `T:681-716`).
- Unknown or inaccessible ids are dropped silently and duplicates removed.
  The acknowledgement's `notifications` echoes only the accepted ids, omitting
  `taskIds` when none are left (`T:700-756`;
  `ext-test:tasks_subscription_acceptance_test.exs:35-60`).
- Notification params are the DetailedTask fields without `resultType`, plus
  `_meta` holding the event metadata and `io.modelcontextprotocol/subscriptionId`
  (`T:185-203`, `ext:task.ex:146-151`):

```json
{"jsonrpc": "2.0", "method": "notifications/tasks", "params": {
  "taskId": "pS3xq0cLrM7f2n1kHf8WbQZ7uD0aYt4e",
  "status": "working",
  "statusMessage": "Task accepted",
  "createdAt": "2026-11-03T18:04:05.123Z",
  "lastUpdatedAt": "2026-11-03T18:04:05.123Z",
  "ttlMs": 3600000,
  "pollIntervalMs": 100,
  "_meta": {"io.modelcontextprotocol/subscriptionId": "sub-1"}}}
```

- snodo's runner publishes nothing on its own. The application turns state
  changes into events with `Tasks.status_event/2` and feeds its subscription
  source (`T:250-261`, `README:493-498`). The conformance fixture does not do
  this, so it sends no `notifications/tasks`.

## 9. The fixture

The ten scenarios call these tools. Each needs a `description` (runner
`tools-list`). They sort after `a_header_probe` (wire §13.5), so adding them does
not disturb `http-header-validation`.

| Tool | snodo policy | Arguments the runner sends | Behavior | Source |
|---|---|---|---|---|
| `greet` | sync (unlisted) | `{"name": "World"}`, `{"name": "sep-2243"}`, `{"name": "header-mismatch"}`, `{"name": "legacy-hint"}` (with `task`), `{"name": "rt"}` | text `Hello, <name>!` (default name `World`). Must never create a task | `fixture:1-11` |
| `slow_compute` | optional | `{"seconds": n, "label": s}`, n in 0, 1, 2, 60 | sleep `seconds` (number, rounded to ms; 0 or absent: none), then text `Completed <label>`. Cancel during the sleep must end `cancelled`, not `completed` or `failed` | `fixture:13-29`; runner `tasks-lifecycle` description |
| `failing_job` | required | `{}` | a tool error: `isError: true`, text `The task job failed`. Must be registered required: undeclared calls get -32021 before the handler runs | `fixture:31-38`; runner `tasks-required-task-error` description |
| `protocol_error_job` | required | `{}` | raises, so the task fails with -32603 | `fixture:40-47` |
| `confirm_delete` | optional | `{"filename": "mrtr-input.txt"}`, `"mrtr-resume.txt"`, `"rt.txt"` | parks on key `confirm-delete`: `elicitation/create` `{"message": "Delete <filename>?", "requestedSchema": {"type": "object", "properties": {"confirm": {"type": "boolean"}}, "required": ["confirm"]}}`; on answer, text `Delete confirmed: <true when content.confirm is true>` | `fixture:49-81` |
| `multi_input` | optional | `{}` | parks on two keys at once, `first-input` and `second-input`, each `elicitation/create` with `message` `First input`/`Second input` and properties `name` (string), `confirm` (boolean), none required; after both answers, text `Inputs received: <name1>, <name2>` (`confirmed` when a response has no `name`) | `fixture:83-124` |
| `test_tool_with_task` | function: sync without `inputResponses`, required with them | `{}` | section 6.2; final text `Hello, <name>; task completed` | `fixture:126-162`, `fixture:210-212` |

Fixture settings: memory store with one shared scope, `ttlMs` 3600000,
`pollIntervalMs` 100 (`fixture:183-201`). snodo's `failing_job` fails at once.
The runner's description says "after ~1s", but no check depends on the
delay.

Timing constraints the runner imposes:

- `K` polls every 200 ms for at most 10 s, so `seconds: 1` and `2` tasks must
  finish well within that.
- `fa` waits at most 5 s for `confirm_delete` to reach `input_required`. The
  partial-fulfillment check polls every 100 ms for 5 s until two keys show;
  both keys must be visible together on one `tasks/get`.
- Every `seconds: 60` task is cancelled by its scenario shortly after
  creation, so the worker must be interruptible.

## 10. Scenario checks

Every scenario except `tasks-status-notifications` also emits
`wire-schema-valid` (section 1; wire §3). On alpha.12 that is nine checks.
Each scenario's first step is a bootstrap that fails only if the client cannot
be built; ids `tasks-*-bootstrap`. Client capabilities are those passed to
`connect`.

### `tasks-lifecycle` (8)

Capabilities `{"elicitation": {}, "sampling": {}, "extensions": {tasks}}`.

| Check | Requires |
|---|---|
| `tasks-sync-tool-call` | `greet` `{"name": "World"}`: not `resultType: "task"`, no `taskId`, non-empty `content` |
| `sep-2663-result-type-task-on-create` | `slow_compute` `{seconds: 2}`: `resultType: "task"`; no `task` key; top-level `taskId`, `status`; no `result`, `error`, `inputRequests`; ISO `createdAt`, `lastUpdatedAt` |
| `sep-2663-tasks-get-status-working` | `tasks/get` on it: same `taskId`, a `status` (any) |
| `sep-2663-tasks-get-status-completed` | polled to terminal: `completed`, `result.content` non-empty |
| `sep-2663-tool-error-uses-completed-status` | `failing_job` creates a task that ends `completed` with `result.isError: true` |
| `sep-2663-tasks-get-status-failed` | `protocol_error_job` creates a task that ends `failed`, numeric `error.code`, string `error.message`, no `result` |
| `sep-2663-cancel-ack-empty-result` | `slow_compute` `{seconds: 60}` then `tasks/cancel`: `resultType: "complete"`, no `taskId`/`status`/`result`/`error`/`inputRequests`; then polled to terminal (status recorded, not asserted) |
| `tasks-cancel-terminal-idempotent-ack` | `slow_compute` `{seconds: 1}` to terminal, then `tasks/cancel`: the same empty ack |

### `tasks-capability-negotiation` (4)

Two clients: one declaring Tasks (plus elicitation, sampling), one with `{}`.

| Check | Requires |
|---|---|
| `tasks-extension-advertised` | discover: `capabilities.extensions["io.modelcontextprotocol/tasks"]` present; no `capabilities.tasks` |
| `sep-2663-tasks-methods-non-declaring` | undeclared `tasks/get` `{taskId: "gate-test"}`, `tasks/update` `{taskId: "gate-test", inputResponses: {}}`, `tasks/cancel` `{taskId: "gate-test"}`: each -32021 |
| `sep-2663-server-rejects-undeclared-client` | undeclared `slow_compute` `{seconds: 0}`: no `resultType: "task"`, no `taskId`, no `task`, has `content` |
| `tasks-per-request-meta-opt-in` | undeclared client, `slow_compute` `{seconds: 1}` with Tasks in that request's `_meta` client capabilities: `resultType: "task"`, `taskId`, no `task` wrapper; then cancelled |

### `tasks-wire-fields` (3)

Capabilities `{"extensions": {tasks}}`.

| Check | Requires |
|---|---|
| `tasks-wire-field-renames` | `slow_compute` `{seconds: 1}`: `ttlMs` key present, `null` or positive integer; `pollIntervalMs` absent or positive integer; no `ttl`, no `pollInterval` |
| `tasks-no-early-ttl-expiry` | that task polled to terminal, 500 ms later `tasks/get` still returns it |
| `tasks-no-related-task-meta-on-inlined-result` | another `seconds: 1` task to terminal: `result._meta` lacks `io.modelcontextprotocol/related-task` |

### `tasks-request-state-removal` (2)

Capabilities `{"extensions": {tasks}}`. Setup creates `slow_compute`
`{seconds: 60}`; if that throws or has no `taskId`, the scenario emits
`tasks-request-state-removal-setup` FAILURE and stops.

| Check | Requires |
|---|---|
| `tasks-create-result-no-request-state` | no own `requestState` on the `CreateTaskResult` |
| `tasks-get-detailed-no-request-state` | no `requestState` on `tasks/get` of the working task; then cancelled |

### `tasks-mrtr-input` (3)

Capabilities `{"elicitation": {}, "sampling": {}, "extensions": {tasks}}`.

| Check | Requires |
|---|---|
| `sep-2663-tasks-get-status-input-required` | `confirm_delete` reaches `input_required` within 5 s with a non-empty `inputRequests` object whose first entry has `method`; then cancelled |
| `tasks-mrtr-tasks-update-resumes` | `tasks/update` answering every key with `{"action": "accept", "content": {"confirm": true}}`: empty ack with `resultType: "complete"`; the task then ends `completed` |
| `tasks-mrtr-partial-fulfillment` | `multi_input`: two keys; answer the first with `{"action": "accept", "content": {"name": "partial-1", "confirm": true}}`; ack `complete`; still `input_required`, first key gone, second present; answer the second; ends `completed` |

### `tasks-request-headers` (4)

Section 7. Capabilities `{"extensions": {tasks}}`.

### `tasks-dispatch-and-envelope` (8)

Capabilities `{"elicitation": {}, "sampling": {}, "extensions": {tasks}}`.

| Check | Requires |
|---|---|
| `sep-2663-tasks-result-removed-method-not-found` | `tasks/result` `{taskId: "tasks-conformance-isolation-probe"}`: -32601 |
| `tasks-removed-tasks-list` | `tasks/list` `{}`: -32601 |
| `tasks-server-directed-creation-no-hint` | `failing_job` `{}` with no hint: `resultType: "task"` and `taskId` |
| `sep-2663-legacy-task-param-ignored` | `greet` with `task: {"ttl": 60000, "pollInterval": 100}`: no error, not a task, has `content` |
| `tasks-immediate-result-shortcut` | `slow_compute` `{seconds: 0}`: either a `CreateTaskResult` with `taskId` or a result with `content` |
| `tasks-result-type-complete-on-non-task-responses` | `resultType: "complete"` on sync `greet`, on `tasks/get` of a terminal `seconds: 0` task (if a task was made), on `tasks/cancel` of a `seconds: 60` task, and on `tasks/update` `{"unknown-key": {"ignored": true}}` to an `input_required` `confirm_delete` |
| `sep-2663-durable-create-strong-consistency` | `slow_compute` `{seconds: 60}` then immediate `tasks/get`: same `taskId`; then cancelled |
| `sep-2663-tasks-get-invalid-task-id-32602` | `tasks/get` `{taskId: "tasks-conformance-nonexistent-12345"}`: -32602 |

### `tasks-status-notifications` (1, skipped)

`tasks-status-notifications`: always SKIPPED (section 8).

### `tasks-required-task-error` (2)

Capabilities `{}`. `tools/call` `failing_job` `{}`.

| Check | Requires |
|---|---|
| `sep-2663-server-returns-missing-capability-when-required` | a JSON-RPC error with code -32021. A success or another code is FAILURE and ends the scenario |
| `sep-2663-server-returns-missing-capability-data-shape` | `data.requiredCapabilities.extensions["io.modelcontextprotocol/tasks"]` defined: SUCCESS; no `data`: INFO; any other shape: FAILURE |

The runner treats `data` as SHOULD, but the core schema requires
`data.requiredCapabilities` on -32021 (section 1), so omitting it also fails
`wire-schema-valid`. Send the full shape.

### `tasks-mrtr-composition` (1)

Capabilities `{"elicitation": {}, "extensions": {tasks}}`. Check
`sep-2663-mrtr-synchronous-before-task-creation`: section 6.2.

## 11. How snodo structures it (design input)

Tasks is a separate package on generic extension hooks; the core has no
Tasks code (`README:4-10`, `snodo:lib/snodo/extension.ex:1-46`). The hooks:

| Hook | Tasks use | Source |
|---|---|---|
| `methods/0` | the three exact-versioned methods | `T:77-95` |
| `negotiate/2` | empty settings only | `T:97-104` |
| `validate_operation/3` | `taskId`, `inputResponses` shapes | `T:106-121` |
| `dispatch/3`, `shape_result/3` | get, update, cancel; DetailedTask or ack | `T:123-164` |
| `around_dispatch/4` | wraps core `tools/call` with the policy decision and task creation | `T:230-244` |
| `missing_capability_error/2` | -32021 for undeclared `tasks/*` | `T:209-213` |
| `transport_policy/2` | adds required, mirrored `mcp-name` from `params.taskId` | `T:215-228` |
| `subscription_filter/2`, `shape_subscription_event/3` | `taskIds`, `notifications/tasks` | `T:169-207` |

Inside the package:

- `Task` (`ext:task.ex`): the protocol state, pure transitions (`add_input`,
  `fulfill_inputs`, `complete`, `fail`, `cancel`) that refuse to leave a
  terminal state, and the three wire projections (creation, detailed,
  notification).
- `Store` behaviour (`ext:store.ex:67-91`), referenced as `{module, state}`:
  `authorize(ctx, action)` returns an opaque access value scoped to one action
  (`{:create|:get|:update|:cancel, task_id}`); `create(task, work, access)`
  stores task and work atomically; `get(task_id, access)`;
  `transition(task_id, expected_revision, event, authority)` is a
  compare-and-set that returns the new snapshot or `{:conflict, current}`;
  `claim`, `claim_next`, `renew`, `release`, `worker_snapshot` handle worker
  leases with generation fencing; `reap` deletes expired tasks. The access
  value is the only thing derived from the request context, and a scope
  mismatch is indistinguishable from not-found.
- `Snapshot` (`ext:snapshot.ex:33-42`): task, work descriptor, revision,
  retry state, input history, accepted-response inbox.
- `Event` (`ext:event.ex`): versioned JSON data
  (`input_requested`, `input_responses_accepted`, `retry_requested`,
  `completed`, `failed`, `cancelled`) with stable ids for idempotent retry.
  `Transition` (`ext:transition.ex`) is the pure reducer every store uses.
- `EffectCodec` (`ext:effect_codec.ex`): versioned JSON encoding of a
  transition's side effects (`none`, `acceptedInputResponses`, `retry`) so a
  durable store can persist and replay them.
- `Runner` (`ext:runner.ex`): a supervised GenServer. `create_task` checks
  the worker limit, stores, and starts the worker in one call; `update`
  applies the accepted-responses transition and wakes parked waiters with only
  the matched keys; `cancel` commits `cancelled` and then kills the worker
  (`ext:runner.ex:90-137`, `242-268`). Defaults: 32 workers, 1 h runtime,
  30 s lease, 60 s reap (`ext:runner.ex:56-63`).
- `Work` and `WorkExecutor` (`ext:work.ex`, `ext:work_executor.ex`): a
  serializable descriptor (`type`, JSON `input`, `idempotency_key` equal to
  the task id) so a recovered task can run on another process; recovery is
  at least once (`README:149-281`).
- `ExecutionContext.detach/1`: section 5.3.

A smaller presa design that meets the wire contract: an atom of task maps
keyed by id with `swap!`-based transitions that ignore terminal tasks (the
CAS); one future per task; one promise per pending input key, delivered by
`tasks/update`; cancellation as a status write followed by `future-cancel`
(an interruptible sleep in `slow_compute`); a scope function per request;
and creation that stores the task before the response is written. Durability,
leases, and retry are not needed for the runner.

## 12. Gaps and disagreements

- No SEP-2663 text or Tasks schema was available. Shapes come from the
  runner's assertions and snodo's implementation, which agree everywhere the
  runner looks. Fields the runner does not check (`statusMessage`,
  `pollIntervalMs` defaults, timestamp precision, `ttlMs: null` meaning
  unlimited) are snodo choices.
- `ttlMs: null`: the runner accepts it; snodo emits it for an unset TTL. I
  found no source for what a client should do with `null` beyond snodo's
  "never reaped".
- The status-notification scenario is a stub. The notification shape in
  section 8, the `taskIds` filter, and its error rules come only from snodo.
  `taskIds` is not in the core `SubscriptionFilter`.
- Whether input requests parked inside a task must be checked against the
  creating request's client capabilities (and fail with -32021) is not stated
  in any source; snodo does not check.
- snodo rejects non-empty Tasks settings with -32602 on every request that
  declares them. The runner never sends settings; the spec's rule is unknown.
- snodo omits `serverInfo` `_meta` on `tasks/*` results while stamping it on
  the inlined `result` and on `CreateTaskResult`. The schema's SHOULD
  (`$defs.ResultMetaObject`) suggests adding it to all three.
- Whether the inlined `result` should keep its own `resultType` and `_meta`
  is not specified; snodo keeps both and the runner accepts that.
- The runner says `failing_job` errors "after ~1s"; snodo fails at once. No
  check measures it.
- The `tasks-request-headers` description calls `Mcp-Name` on task methods a
  SEP-2663 requirement; its checks only test the matched and mismatched
  cases, not a missing header. snodo rejects a missing one with -32020.
- Validation order between -32020 header checks and -32021 is untested by the
  runner: its client always sends correct headers for task methods.
