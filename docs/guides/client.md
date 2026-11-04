# The client

`presa.client` calls any MCP server. A client is opened over a transport:

| Transport | Function |
|---|---|
| a server map in this process | `presa.client/in-process` |
| a server subprocess over stdio | `presa.transport.stdio/command` |
| a Streamable HTTP endpoint | `presa.transport.http/url` |

```clojure
(require '[presa.client :as client]
         '[presa.protocol :as protocol]
         '[presa.server :as server])

(def srv
  (server/server
   {:name "demo" :version "1" :versions protocol/versions :logging true
    :tools [{:name "echo" :description "Echo" :handler (fn [{:strs [text]} _] text)}
            {:name "count" :description "Counts, reporting progress"
             :handler (fn [_ ctx]
                        (doseq [i (range 1 4)]
                          (server/progress! ctx i {:total 3})
                          (server/log! ctx "info" (str "step " i)))
                        "counted")}
            {:name "slow" :description "Takes a while"
             :handler (fn [_ _] (Thread/sleep 2000) "late")}]}))
```

## Connecting

`connect` tries `server/discover` first. When the server does not answer as
a 2026-07-28 server, it falls back to `initialize` with the newest
initialize-era revision the client allows (`:versions`). Other `connect`
options: `:client-info` (what the client calls itself), `:capabilities`,
`:probe-timeout-ms` (how long to wait for `server/discover`, default
10000) and `:on-notification` (every notification the server sends).

```clojure
(def c (client/connect (client/in-process srv)))

(:version (client/info c))
;; => "2026-07-28"

(client/close c)

(def old (client/connect (client/in-process srv) {:versions ["2025-06-18"]}))

(:version (client/info old))
;; => "2025-06-18"

(client/close old)
```

## Calls

`list-tools`, `list-resources`, `list-resource-templates` and `list-prompts`
follow pagination and return every item. `call-tool`, `read-resource`,
`get-prompt` and `complete` make one call each; `request` sends any method.
A JSON-RPC error throws ex-info with `:kind :protocol` and the error's
`:code`, `:message` and `:data`. A tool that fails returns `isError: true`
rather than throwing.

```clojure
(def logs (atom []))
(def c (client/connect (client/in-process srv) {:log-level "info" :on-log #(swap! logs conj (get % "data"))}))

(map #(get % "name") (client/list-tools c))
;; => ["echo" "count" "slow"]

((get (client/tool-fns c) "echo") {"text" "hi"})
;; => {"content" [{"type" "text" "text" "hi"}] "isError" false "resultType" "complete" "_meta" {"io.modelcontextprotocol/serverInfo" {"name" "demo" "version" "1"}}}

(:code (ex-data (try (client/request c "no/such" {}) (catch Exception e e))))
;; => -32601
```

## Progress, logs and timeouts

`:on-progress` on a request receives its `notifications/progress`.
`:log-level` (on `connect` or on a request) asks a 2026-07-28 server for
log messages at that level and above, delivered to `:on-log`; on an
initialize-era connection use `set-log-level`. `:on-log` on a request
takes that request's log messages in place of the client's, and
`:handlers` on a request answer what the server asks while it runs, over
the client's. `:on-log`, and requests from initialize-era servers, reach a
request's functions only when the transport can tell which request a
message was sent for (in-process and HTTP can, stdio cannot); 2026-07-28
input requests always do. `:timeout-ms` bounds a
request (default 60000); a timeout sends `notifications/cancelled` so the
server stops the work.

```clojure
(def progress (atom []))

(client/text (client/call-tool c "count" {} {:on-progress #(swap! progress conj (get % "progress"))}))
;; => "counted"

@progress
;; => [1 2 3]

@logs
;; => ["step 1" "step 2" "step 3"]

(:kind (ex-data (try (client/call-tool c "slow" {} {:timeout-ms 100}) (catch Exception e e))))
;; => :timeout

(client/close c)
```

## Answering the server

Servers ask clients for input: elicitation (a question for the user),
sampling (a completion from the client's model) and roots. `:handlers`
answers them, both inside 2026-07-28 `input_required` results and as
requests from initialize-era servers. Without `:capabilities`, the client
declares the capabilities its handlers cover. See [input.md](input.md).

<!-- no-run -->
```clojure
(client/connect transport
                {:handlers {"elicitation/create" (fn [{:strs [message requestedSchema]}]
                                                   {"action" "accept" "content" {"name" "Ada"}})
                            "sampling/createMessage" (fn [params]
                                                       {"role" "assistant" "model" "m"
                                                        "content" {"type" "text" "text" "..."}})
                            "roots/list" (fn [_] {"roots" [{"uri" "file:///work"}]})}})
```

## Caching

With `:cache true`, 2026-07-28 discover, list and `resources/read` results
are kept for their `ttlMs`. List changes and resource updates that arrive
on a subscription drop what they make stale; `:no-cache` on a request asks
the server anyway, and `clear-cache!` forgets everything.

## Subscriptions and tasks

`listen` opens a `subscriptions/listen` stream ([subscriptions.md](subscriptions.md)).
`:tasks true` declares the Tasks extension, and `await-task` follows a task
to the end ([tasks.md](tasks.md)).
