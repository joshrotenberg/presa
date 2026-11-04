# Tasks

The Tasks extension (`io.modelcontextprotocol/tasks`, SEP-2663) lets a tool
call outlive its request: `tools/call` answers at once with a task, and the
client polls `tasks/get`, answers input with `tasks/update`, or cancels
with `tasks/cancel`. presa implements it on 2026-07-28;
[docs/tasks-extension.md](../tasks-extension.md) is the wire reference.

## Server

`:tasks` turns the extension on and advertises it. A tool's `:task` policy
decides how a call runs:

| Policy | Client declares Tasks | Client does not |
|---|---|---|
| `:sync` (default) | runs synchronously | runs synchronously |
| `:optional` | becomes a task | runs synchronously |
| `:required` | becomes a task | -32021 |
| `(fn [params context])` | returns one of the above, per call | |

```clojure
(require '[presa.client :as client]
         '[presa.input :as input]
         '[presa.server :as server])

(def srv
  (server/server
   {:name "jobs" :version "1"
    :tasks {:ttl-ms 3600000 :poll-interval-ms 20 :max-tasks 1000}
    :tools [{:name "build" :description "Builds" :task :optional
             :handler (fn [{:strs [target]} ctx]
                        (if-let [r (server/input-response ctx "confirm")]
                          (if (true? (get-in r ["content" "ok"]))
                            (str "built " target)
                            (server/tool-error "not confirmed"))
                          (server/input-required
                           {"confirm" (input/elicit (str "Build " target "?")
                                                    {"type" "object" "properties" {"ok" {"type" "boolean"}}})})))}]}))
```

The handler runs on its own thread with a context that outlives the
request: the caller's version, client capabilities and identity, but no
progress or log channel. `input-required` parks the task at
`input_required` until `tasks/update` answers; a tool error ends it
`completed` with `isError`; an exception ends it `failed` with a -32603
error that does not leak the exception. A task stays readable for its
`ttlMs`, and belongs to the authenticated subject that created it.

## Client

`:tasks true` declares the extension. `await-task` polls to the end and
answers input with the client's `:handlers`; `task`, `update-task` and
`cancel-task` do one step each.

```clojure
(def c (client/connect (client/in-process srv)
                       {:tasks true
                        :handlers {"elicitation/create" (fn [_] {"action" "accept" "content" {"ok" true}})}}))

(def created (client/call-tool c "build" {"target" "all"}))

(client/task? created)
;; => true

(get-in (client/await-task c created) ["result" "content" 0 "text"])
;; => "built all"

(client/close c)
```

A client that does not declare Tasks gets the same tool synchronously, and
its input requests the ordinary way:

```clojure
(def plain (client/connect (client/in-process srv)
                           {:handlers {"elicitation/create" (fn [_] {"action" "accept" "content" {"ok" true}})}}))

(client/text (client/call-tool plain "build" {"target" "docs"}))
;; => "built docs"

(client/close plain)
```

## Following a task without polling

A client can listen for a task's status changes: `subscriptions/listen`
with `"taskIds"` (at most 100, each visible to the caller; others are
dropped from the acknowledgement) delivers `notifications/tasks` with the
task's DetailedTask fields. It needs the Tasks declaration. `await-task`
with `:listen? true` uses it, polling rarely as a fallback.

```clojure
(def watcher (client/connect (client/in-process srv)
                             {:tasks true
                              :handlers {"elicitation/create" (fn [_] {"action" "accept" "content" {"ok" true}})}}))

(get-in (client/await-task watcher (client/call-tool watcher "build" {"target" "web"}) {:listen? true})
        ["result" "content" 0 "text"])
;; => "built web"

(client/close watcher)
```
