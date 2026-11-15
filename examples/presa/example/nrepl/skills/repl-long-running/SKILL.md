---
name: repl-long-running
description: How to run code that takes long or never returns in the REPL. Covers timeouts that leave code running, the await and interrupt tools, starting servers and background jobs without blocking the session, and polling for progress. Read before starting a server, a watcher, a long test run or any job over a few seconds.
---

# Long-running code

A REPL session runs one evaluation at a time. Code that takes long, or never
returns, holds the session until it finishes or is interrupted.

## What a timeout does

A call that reaches its `timeout_ms` does not stop the code. It returns what
was printed so far with `[running]`, and the evaluation keeps going.

- `await` waits again. It returns what was printed since the last report, and
  the values once the code finishes.
- `interrupt` stops it.
- Until it finishes, `eval` and `load_file` are refused.

For work you know is slow, such as a full test suite, pass a larger
`timeout_ms`, or take the `[running]` result and `await`.

## Code that never returns

A blocking server start, a watch loop or a `(while true ...)` would hold the
session forever. Start it on another thread and keep a handle so you can stop
it:

```clojure
(defonce server (atom nil))

(defn start! []
  (reset! server (future (run-server! {:port 8080}))))   ; a blocking start

(defn stop! []
  (some-> @server future-cancel))
```

Many libraries have a start function that returns at once and gives back a
stop function or a server object. Prefer it, and store what it returns:

```clojure
(defonce server (atom nil))
(reset! server (http/run-server handler {:port 8080}))   ; http-kit returns a stop fn
(@server)                                                ; stop
```

Use `defonce` for the handle, so reloading the file does not lose a running
server.

## Background jobs

Run the job in a `future`, record progress in an atom, and look at the atom
from later calls:

```clojure
(def progress (atom {:done 0 :total (count items) :errors []}))

(def job
  (future
    (doseq [item items]
      (try (process! item)
           (catch Exception e (swap! progress update :errors conj (ex-message e))))
      (swap! progress update :done inc))
    :finished))

@progress            ; any time
(realized? job)      ; finished?
(future-cancel job)  ; stop it
```

Do not rely on printing from a background thread: once the evaluation that
started it has returned, its output has nowhere to go. Collect what you need
in an atom.

`@job` blocks until the job ends. Use `(deref job 1000 :still-running)` to
look without waiting.

## When interrupt does not work

Code in a tight loop that never checks for interruption may not stop. The
result then says the evaluation may still be running, and later calls use a
new session: `*ns*`, `*1` and `*e` are reset, definitions are kept. If the
runaway code keeps a CPU busy, the REPL process has to be restarted from
outside.
