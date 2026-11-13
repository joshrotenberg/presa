# The nREPL bridge

`presa.nrepl` puts a running Clojure or Babashka REPL behind MCP tools. An
agent evaluates code in the live process, loads files into it and looks
symbols up, over [nREPL](https://nrepl.org), the protocol editors use. State
persists between calls, so the agent works the way a person does at a REPL:
evaluate a little, look at the result, change the code, load it again.

It is a part (see [Parts](parts.md)), so it runs as a server of its own or
next to a project's other tools.

## Security

`eval` and `load_file` run arbitrary code inside the REPL's process, with
everything that process can do: read and write files, start programs, use
the network.

- A host's sandbox and its rules for shell commands do not apply to that
  code. A host asks before a tool call and shows the code, but allowing
  `eval` without review is close to allowing any command.
- nREPL has no permission model, and the bridge adds none.
- For unattended use, run the REPL itself in a restricted environment: a
  container, or a user with few rights.
- Over HTTP, put the tools behind scopes with `:required-scopes` (see
  [Authorization](authorization.md)). The bridge is meant for stdio, on the
  machine of the person who started the REPL.

## Running it

Start an nREPL server in the project, then serve the bridge over stdio:

```sh
bb --config /path/to/presa/bb.edn -m presa.nrepl --dir /path/to/project
```

- `--dir` is the project directory. The port is read from its `.nrepl-port`
  file at each connect, so a restarted REPL is found again.
- `--port` names the port instead, and `--host` a host other than
  `127.0.0.1`.

A JVM REPL started with `nrepl.cmdline` or by an editor, and
`bb nrepl-server`, each write `.nrepl-port` in the directory they start in.

Register it with a host as any stdio server:

```sh
claude mcp add repl -- bb --config /path/to/presa/bb.edn -m presa.nrepl --dir /path/to/project
codex mcp add repl -- bb --config /path/to/presa/bb.edn -m presa.nrepl --dir /path/to/project
```

The bridge connects on the first call, not at startup, so it can be
registered before the REPL is running. A call made while no REPL answers is
a tool error that says so.

## The tools

| Tool | What it does |
|---|---|
| `eval` | evaluates one or more forms and returns each value with what was printed |
| `load_file` | loads a source file, with its name and line numbers, as an editor's load-file does |
| `await` | waits for an evaluation that an earlier call left running |
| `interrupt` | stops the running evaluation |
| `lookup` | the doc string, argument lists and source location of a symbol |

```clojure
(require '[presa.nrepl :as nrepl])

;; a part connects on its first call, so building one needs no REPL
(map :name (:tools (nrepl/part {:port 7888})))
;; => ("eval" "load_file" "await" "interrupt" "lookup")
```

A result is text written for a model:

```
:hi
=> nil
=> #'user/x
=> 42
```

Printed output comes first, then each form's value after `=>`. An exception
makes an error result that names its class; the exception is in `*e` for the
next call. `load_file` ends with the file it loaded and the namespace the
file declares.

## How an evaluation behaves

- **State persists.** Definitions, the current namespace, `*1` and `*e`
  carry over between calls. The bridge clones one nREPL session and every
  call uses it.
- **Output streams.** What the code prints is sent as progress
  notifications while it runs, to a client that asked for them, and is in
  the result.
- **Long output is cut.** A result keeps the start and the end of stdout and
  of stderr, up to `:max-output-chars` and `:max-output-lines`, with a line
  saying how much was dropped. A long value is cut the same way.
- **A timeout does not stop the code.** A call that reaches its
  `timeout_ms` returns what it has, marked `[running]`, and the evaluation
  goes on. `await` waits for it again and returns what came since;
  `interrupt` stops it. Until it finishes, `eval` and `load_file` are
  refused, because a session runs one evaluation at a time.
- **Cancelling a call interrupts.** When the client cancels the tool call,
  the bridge interrupts the evaluation. An evaluation that does not stop
  within `:interrupt-grace-ms` gives up its session: later calls get a new
  one, with definitions kept and `*ns*`, `*1` and `*e` reset.
- **Reading input asks the client.** Code that reads `*in*`, such as
  `(read-line)`, becomes a form elicitation for a line. A client that cannot
  be asked, or that declines, gives the code end of input, and the result
  says so.
- **A restarted REPL is noticed.** A call made while the REPL is down is a
  tool error. Once it is back the bridge reconnects, and the first result
  says that the session is new and that definitions may be gone.
- **Concurrent calls take turns.** A call that arrives while another is
  being watched waits, so each call's timeout measures its own code.

## Skills

The part serves six guides as skills (see [Skills](skills.md)) and names
them in its instructions, so a model reads the one that fits its task:

| Skill | For |
|---|---|
| `repl-workflow` | the edit, load, call loop; files as the source of truth; stale definitions |
| `repl-long-running` | timeouts, `await` and `interrupt`; servers and jobs on other threads |
| `repl-exploring` | namespaces, documentation, the shape of data, Java classes |
| `repl-debugging` | the last exception, capturing values, stale code |
| `repl-testing` | running `clojure.test` from the REPL |
| `babashka-tooling` | Babashka as a tooling language: files, processes, HTTP, scripts, tasks |

```clojure
(map :name (:skills (nrepl/part {:port 7888})))
;; => ("repl-workflow" "repl-long-running" "repl-exploring" "repl-debugging" "repl-testing" "babashka-tooling")

(:skills (nrepl/part {:port 7888 :skills? false}))
;; => []
```

Each is at `skill://<name>/SKILL.md`. `:skills? false` leaves them out,
along with the lines of the instructions that list them.

## As a part

`part` returns a part, to combine with a project's own tools:

<!-- no-run -->
```clojure
(require '[presa.part :as part])

(part/serve! {:name "dev"
              :parts [(part/prefix (nrepl/part {:dir "."}) "repl")
                      {:tools [{:name "deploy" :handler (fn [_ _] "deployed")}]}]})
```

Its options:

| Option | Meaning | Default |
|---|---|---|
| `:port` | the nREPL server's port | the port in `:dir`'s `.nrepl-port` |
| `:host` | its host | `127.0.0.1` |
| `:dir` | the project directory, for `.nrepl-port` and relative `load_file` paths | the working directory |
| `:timeout-ms` | how long a call waits when it names no `timeout_ms` | 30000 |
| `:interrupt-grace-ms` | how long an interrupted evaluation has to stop | 3000 |
| `:max-output-chars` | the most characters kept of stdout, of stderr and of each value | 4000 |
| `:max-output-lines` | the most lines kept of stdout and of stderr | 100 |
| `:structured?` | add `structuredContent` to results | false |
| `:skills?` | serve the guides | true |

Results are text only by default: a host that gets both text and
`structuredContent` shows the model the JSON, which is harder to read than
the text. `:structured? true` adds a map of `status`, `values`, `out`,
`err`, `ns`, `ex`, `loaded` and `note` for a caller that wants data.

The part's `:close!` interrupts a running evaluation and closes the session
and the connection.

## Limits

- One session per part. Two agents on one bridge share `*ns*`, `*1` and
  `*e`.
- `lookup` knows only namespaces that are loaded.
- It is tested against JVM nREPL and Babashka's nREPL server. ClojureScript
  REPLs and other nREPL servers are untried.
- There is no delimiter repair and no file editing: a host's own file tools
  do that.
