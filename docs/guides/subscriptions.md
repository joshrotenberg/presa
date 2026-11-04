# Subscriptions

A server can tell clients when its lists change and when resources are
updated. On 2026-07-28 a client opens a `subscriptions/listen` stream and
says what it wants; the server acknowledges, sends matching notifications
tagged with the stream's id, and ends the stream with a result when it
shuts down. Cancelling the request, or closing the HTTP connection, ends it
from the client's side.

```clojure
(require '[presa.client :as client]
         '[presa.server :as server])

(def srv
  (server/server
   {:name "t" :version "1"
    :list-changed true   ; listChanged for tools, prompts and resources
    :subscribe true      ; resources.subscribe
    :tools [{:name "noop" :description "Nothing" :handler (fn [_ _] "ok")}]
    :resources [{:uri "file:///notes" :name "notes" :handler (fn [_] "notes")}]}))

(def c (client/connect (client/in-process srv)))

(def seen (atom []))

(def sub (client/listen c {"toolsListChanged" true "resourceSubscriptions" ["file:///notes"]}
                        #(swap! seen conj (get % "method"))))

(get (deref (:acknowledged sub) 2000 nil) "notifications")
;; => {"toolsListChanged" true "resourceSubscriptions" ["file:///notes"]}
```

The server side announces with `list-changed!` and `resource-updated!`,
from anywhere: a handler passes its context, other code the server.
`add-tool!` and the other catalog changes call `list-changed!` themselves.

```clojure
(server/add-tool! srv {:name "new" :description "New" :handler (fn [_ _] "new")})
(server/resource-updated! srv "file:///notes/today.md")   ; under a subscribed URI

(Thread/sleep 500)   ; notifications arrive on another thread

@seen
;; => ["notifications/subscriptions/acknowledged" "notifications/tools/list_changed" "notifications/resources/updated"]

((:stop! sub))
(deref (:done sub) 2000 nil)
;; => :stopped

(client/close c)
```

`server/end-subscriptions!` ends every open stream with its final result,
as `http/serve!`'s stop function does. `:max-subscriptions` (default 256)
bounds the open streams; one more is refused, with 503 over HTTP.

## Initialize-era clients

Those revisions have no `subscriptions/listen`. A session hears list
changes when the server has `:list-changed`, and with `:subscribe` it can
`resources/subscribe` and `resources/unsubscribe` to hear updates. Over
stdio they arrive on the connection; over HTTP, on the session's GET event
stream.
