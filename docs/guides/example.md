# A complete example: the notebook

[`examples/presa/example/notebook.clj`](../../examples/presa/example/notebook.clj)
is an MCP server that keeps notes in memory. It is small, but it uses most
of presa together, and it runs from Claude Code and Codex. Three files go
with it:

| File | What it is |
|---|---|
| [`notebook.clj`](../../examples/presa/example/notebook.clj) | the server, over stdio or Streamable HTTP |
| [`notebook_repl.clj`](../../examples/presa/example/notebook_repl.clj) | a REPL walkthrough: a `(comment ...)` block to evaluate form by form, with the result of each; the tests evaluate it too, so the results stay true |
| [`notebook_client.clj`](../../examples/presa/example/notebook_client.clj) | a client walk-through from the command line, in process, over stdio or over HTTP, at any revision |

## What it uses

| Feature | In the notebook |
|---|---|
| tools with input and output schemas | `create_note`, `search_notes` and `tag_note` return structured content |
| tool annotations | `search_notes` and `summarize_note` are read-only, `delete_note` destructive |
| tool errors | a missing note is an `isError` result the model can act on |
| elicitation | `delete_note` asks the user to confirm |
| sampling | `summarize_note` asks the client's model |
| Tasks | `export_notes` is `:task :optional`: a task for clients that declare Tasks, a synchronous call with progress for the rest; it stops when cancelled |
| resources | `notes://index`, and each note at `notes://notes/{id}`, added and removed as notes are |
| a resource template | `notes://tags/{tag}`, with completion of tags |
| a prompt | `review_note`, with completion of note ids |
| subscriptions | adding a note changes the resource list; changes reach `subscriptions/listen` and `resources/subscribe` |
| logging | `create_note` logs at info, `search_notes` at debug |
| initialize-era clients | `:versions protocol/versions`, so Codex can use it |
| transports | stdio by default; `--http PORT` for Streamable HTTP |
| authorization | with `--http`, `NOTEBOOK_ISSUER` and `NOTEBOOK_JWKS`, an OAuth resource server; the tools that change notes need `notes:write` |

## In a REPL

`notebook` returns a server value. Connect a client to it in process:

```clojure
(require '[presa.client :as client]
         '[presa.example.notebook :as notebook])

(def srv (notebook/notebook {:notes notebook/sample-notes}))
(def c (client/connect (client/in-process srv)))

(get (client/call-tool c "create_note" {"title" "Ideas" "body" "Proxy two notebooks." "tags" ["work"]})
     "structuredContent")
;; => {"id" "n3" "title" "Ideas" "tags" ["work"]}

(map #(get % "title") (get-in (client/call-tool c "search_notes" {"query" "e"}) ["structuredContent" "notes"]))
;; => ["Shopping" "Release checklist" "Ideas"]

(client/close c)
```

`notebook_repl.clj` carries on from there: logs, subscriptions, resources,
completion, prompts, sampling, elicitation, an export as a task and as a
synchronous call with progress, and changing the server while clients are
connected. Start a REPL with the examples on the classpath
(`bb --config /path/to/presa/bb.edn nrepl-server`, or `clojure -A:dev` in
the presa checkout)
and evaluate its forms one at a time.

## From the command line

The client walk-through prints each step. Without arguments it starts a
notebook in its own process; `stdio` starts one as a subprocess; `http URL`
connects to one that is already serving. `--version` picks a revision.

<!-- no-run -->
```sh
bb --config /path/to/presa/bb.edn -m presa.example.notebook-client
bb --config /path/to/presa/bb.edn -m presa.example.notebook-client stdio --version 2025-06-18
bb --config /path/to/presa/bb.edn -m presa.example.notebook --http 8080 &
bb --config /path/to/presa/bb.edn -m presa.example.notebook-client http http://127.0.0.1:8080/mcp
```

## From a host

Register the stdio server with Claude Code or Codex:

<!-- no-run -->
```sh
claude mcp add notebook -- bb --config /path/to/presa/bb.edn -m presa.example.notebook
codex mcp add notebook -- bb --config /path/to/presa/bb.edn -m presa.example.notebook
```

Then ask for what the tools do ("add a note about the release",
"search my notes for coffee"). Claude Code speaks 2026-07-28 to it and
Codex 2025-06-18 (see [hosts.md](../hosts.md)); the notebook serves both.
How much of the rest a host uses (elicitation, sampling, tasks,
subscriptions) depends on the host.

## Reading the code

- Handlers take `(arguments context)`. A tool that asks for input returns
  `server/input-required` and is called again with
  `(server/input-response ctx id)` set; presa turns that into an
  `input_required` result on 2026-07-28 and into requests on
  initialize-era sessions, so `delete_note` and `summarize_note` are
  written once for both.
- `create_note` changes the running server with `server/add-resource!`,
  which tells listeners the resource list changed, and announces the index
  update with `server/resource-updated!`.
- `export_notes` checks `(:cancelled? ctx)` between notes; as a task, that
  is true once `tasks/cancel` arrives.
- `:required-scopes` is set only when authorization is on: a caller
  without a token has no scopes, so a scope policy without authorization
  would hide the write tools from everyone.
