# Logging, progress and instrumentation

## Log messages to the client

With `:logging true`, `server/log!` sends `notifications/message` from a
handler. On 2026-07-28 the client chooses a level per request
(`io.modelcontextprotocol/logLevel` in `_meta`; a presa client's
`:log-level`) and gets nothing without one. On an initialize-era session
the level is the server's `:log-level` (default `info`) until the client
sends `logging/setLevel`. A request sends at most 1000 messages, with data
of at most 16384 bytes; `log!` returns false for what it drops.

## Progress

`server/progress!` sends `notifications/progress` when the request carries
a `progressToken`. Values must increase; a request sends at most 1000
updates, with messages of at most 4096 bytes.

```clojure
(require '[presa.client :as client]
         '[presa.server :as server])

(def events (atom []))

(def srv
  (server/server
   {:name "t" :version "1" :logging true
    :on-event #(swap! events conj (select-keys % [:event :method :outcome :error-code]))
    :tools [{:name "work" :description "Works"
             :handler (fn [_ ctx]
                        (server/log! ctx "info" "starting")
                        (server/progress! ctx 1 {:total 2 :message "half"})
                        (server/progress! ctx 2 {:total 2})
                        "done")}]}))

(def logs (atom []))
(def progress (atom []))

(def c (client/connect (client/in-process srv) {:log-level "info" :on-log #(swap! logs conj %)}))

(client/text (client/call-tool c "work" {} {:on-progress #(swap! progress conj (get % "progress"))}))
;; => "done"

@logs
;; => [{"level" "info" "data" "starting"}]

@progress
;; => [1 2]
```

## Instrumentation

`:on-event` receives a map per event: `:request/start` and `:request/stop`
(with `:duration-ms` and `:outcome`: `:ok`, `:error` with `:error-code`, or
`:cancelled`), `:request/rejected` for a request refused before it ran,
`:tool/error` for a tool handler that threw, and `:request/error` (with
`:exception`) for another handler that threw. Exceptions from the function
are ignored.

The client gets `-32603 "Internal error"` for an unexpected exception,
without its message, which can carry file paths, queries or other internals;
`:expose-errors? true` on the server adds the message, for development.
The exception and its stack trace go to the transport's `:log`, which is a
line on stderr by default for stdio and Streamable HTTP; a stdio server's
stderr usually lands in the host's MCP server log.

```clojure
(filter #(= "tools/call" (:method %)) @events)
;; => [{:event :request/start :method "tools/call"} {:event :request/stop :method "tools/call" :outcome :ok}]

(client/close c)
```
