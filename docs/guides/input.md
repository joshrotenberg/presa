# Asking the client for input

A tool, resource or prompt handler can stop to ask the client something:
the user (elicitation), the client's model (sampling), or the client's
roots. The handler returns `server/input-required` with the requests, and is
called again with the answers.

```clojure
(require '[presa.client :as client]
         '[presa.input :as input]
         '[presa.protocol :as protocol]
         '[presa.server :as server])

(def greet
  {:name "greet"
   :description "Greets whoever the user names"
   :handler (fn [_ ctx]
              (if-let [{:strs [action content]} (server/input-response ctx "who")]
                (if (= "accept" action)
                  (str "Hello, " (get content "name") "!")
                  (server/tool-error "No name given"))
                (server/input-required
                 {"who" (input/elicit "Who should I greet?"
                                      {"type" "object"
                                       "properties" {"name" {"type" "string"}}
                                       "required" ["name"]})})))})
```

`presa.input` builds the requests: `elicit` (a form), `elicit-url` (a link
for the user to visit), `sample` (messages for the client's model) and
`roots`. The handler sees the answers with `server/input-response` (or all
of them as `(:input-responses context)`), and answers can be partial: ask
again for what is missing.

## On 2026-07-28

The request ends with an `input_required` result. The client answers on a
new request with `inputResponses` and the server's `requestState`, and the
handler runs again. presa:

- checks each input request against the capabilities the client declared,
  and answers -32021 with `requiredCapabilities` when one is missing;
- signs the handler's state (the second argument of `input-required`) into
  `requestState` with HMAC-SHA256, scoped to the method, the params and the
  caller, with an expiry (`:state-ttl-ms`, default 300000). A changed,
  expired or misplaced token is -32602. `:state-secret` sets the key; the
  default is random per server map, so tokens do not survive a restart;
- sends `ttlMs: 0` on a `resources/read` result that took input.

Multi-round flows put what they need in the state:

```clojure
(def two-steps
  {:name "two-steps"
   :description "Asks twice"
   :handler (fn [_ ctx]
              (case (get (:state ctx) "step")
                nil (server/input-required {"name" (input/elicit "Name?" {"type" "object"})} {"step" 1})
                1 (server/input-required {"color" (input/elicit "Color?" {"type" "object"})}
                                         {"step" 2 "name" (get-in (server/input-response ctx "name") ["content" "name"])})
                2 (str (get (:state ctx) "name") " likes "
                       (get-in (server/input-response ctx "color") ["content" "color"]))))})
```

## On initialize-era sessions

Those revisions have no `input_required`. presa sends each input request to
the client as a request from server to client (`elicitation/create` and
friends), waits for the answers, and calls the handler again, all within
the one `tools/call`. The same handler serves both eras.

## The client side

A presa client answers with its `:handlers`, in both eras:

```clojure
(def srv (server/server {:name "t" :version "1" :versions protocol/versions :tools [greet two-steps]}))

(def answers
  {"elicitation/create" (fn [{:strs [message]}]
                          {"action" "accept"
                           "content" (case message
                                       "Who should I greet?" {"name" "Ada"}
                                       "Name?" {"name" "Ada"}
                                       "Color?" {"color" "blue"})})})

(def c (client/connect (client/in-process srv) {:handlers answers}))

(client/text (client/call-tool c "greet"))
;; => "Hello, Ada!"

(client/text (client/call-tool c "two-steps"))
;; => "Ada likes blue"

(client/close c)

(def old (client/connect (client/in-process srv) {:versions ["2025-11-25"] :handlers answers}))

(client/text (client/call-tool old "greet"))
;; => "Hello, Ada!"

(client/close old)
```

A client that has no handler for a kind of input does not declare that
capability, so the server refuses to ask: -32021 (ex-info `:kind :protocol`)
on 2026-07-28, an `isError` result on initialize-era sessions. A client that
declares a capability in `:capabilities` without a handler for it gets
ex-info with `:kind :unsupported` when the server asks.
