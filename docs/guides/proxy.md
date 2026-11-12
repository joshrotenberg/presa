# The proxy

`presa.proxy/proxy-server` puts one server in front of several MCP servers.
Each backend is a connected `presa.client`; its tools and prompts appear
under a prefix, and its resources and templates under their own URIs.

```clojure
(require '[presa.client :as client]
         '[presa.protocol :as protocol]
         '[presa.proxy :as proxy]
         '[presa.server :as server])

(def notes
  (server/server
   {:name "notes" :version "1" :logging true
    :tools [{:name "add" :description "Add a note"
             :handler (fn [{:strs [text]} ctx]
                        (server/log! ctx "info" (str "adding " text))
                        "added")}]}))

(def front
  (proxy/proxy-server [{:prefix "notes" :client (client/connect (client/in-process notes))}]
                      {:name "front" :version "1" :versions protocol/versions}))

(def logs (atom []))
(def c (client/connect (client/in-process (:server front))
                       {:log-level "info" :on-log #(swap! logs conj (get % "data"))}))

(map #(get % "name") (client/list-tools c))
;; => ["notes_add"]

(client/text (client/call-tool c "notes_add" {"text" "milk"}))
;; => "added"
```

## What is forwarded

| | how |
|---|---|
| progress | the caller's progress token, on each forwarded call |
| log messages | the backend's messages about a call, through `server/log!`, so the caller's level applies (the proxy turns `:logging` on unless its options say otherwise) |
| input from a 2026-07-28 backend | its `input_required` is passed to the caller; the backend's `requestState` rides in the proxy's signed state |
| input from an initialize-era backend | the call is parked while the proxy asks the caller (below) |
| list changes | a 2026-07-28 backend's list change resyncs the proxy (one resync at a time per backend) and reaches the proxy's listeners; a new definition whose name another backend has is skipped and logged to `:log`; initialize-era backends are listed once, at start |
| completion | argument completion for a backend's prompts and templates goes to that backend |
| cancellation | a call the caller cancels (or times out on) is cancelled at the backend with `notifications/cancelled`, on every transport |

The log message above reached the caller:

```clojure
@logs
;; => ["adding milk"]
```

## Input from initialize-era backends

An initialize-era backend asks for input with its own requests
(`elicitation/create`, `sampling/createMessage`, `roots/list`) while a call
runs. The proxy parks the call and answers the caller with
`input_required`; when the caller retries with its answer, the proxy hands
it to the backend and waits for what the backend does next. A caller on an
initialize-era session is asked with a request instead, as for any presa
handler that returns `input_required`.

```clojure
(def old
  (server/server
   {:name "old" :version "1" :versions protocol/legacy-versions
    :tools [{:name "greet" :description "Asks who to greet"
             :handler (fn [_ ctx]
                        (let [r (server/client-request! ctx "elicitation/create"
                                                        {"mode" "form" "message" "Who?"
                                                         "requestedSchema" {"type" "object"
                                                                            "properties" {"name" {"type" "string"}}}})]
                          (str "hello " (get-in r ["content" "name"]))))}]}))

;; the backend asks only for what its client declared
(def old-front
  (proxy/proxy-server [{:prefix "old"
                        :client (client/connect (client/in-process old)
                                                {:capabilities {"elicitation" {"form" {}}}})}]
                      {:name "front" :version "1"}))

(def asker (client/connect (client/in-process (:server old-front))
                           {:handlers {"elicitation/create" (fn [_] {"action" "accept" "content" {"name" "Ada"}})}}))

(client/text (client/call-tool asker "old_greet"))
;; => "hello Ada"
```

A parked call lives in the proxy's process. If the caller does not answer
within the backend client's timeout, the backend's request fails and the
parked call is forgotten; a retry after that is refused with -32602. An
initialize-era caller that gives up on the request presa sent it frees the
parked call at once. A caller that did not declare the capability gets
-32021, and the backend's request fails at once.

## What is not forwarded

- Over stdio, a backend's log messages and requests cannot be matched to a
  call, so they go to the backend client's own `:on-log` and `:handlers`.
  In-process and Streamable HTTP backends are matched: their messages
  carry the id of the request they were sent for.
- Subscriptions to backend resources.

`:separator` on a backend map replaces the `_` between its prefix and a
name.

```clojure
(client/close c)
(client/close asker)
((:close! front))
((:close! old-front))
```
