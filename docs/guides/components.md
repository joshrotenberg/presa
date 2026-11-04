# Tools, resources and prompts

A server is built from three kinds of definitions, all plain maps with
keyword keys. Wire data (arguments, results, schemas) uses string keys
throughout, as clojure.data.json reads it.

```clojure
(require '[presa.client :as client]
         '[presa.content :as content]
         '[presa.server :as server])
```

## Tools

A tool's handler is `(fn [arguments context])`. What it returns becomes the
`tools/call` result:

| Handler returns | Result |
|---|---|
| a string | one text content block |
| a map | `structuredContent`, plus a text block holding its JSON |
| `(server/content blocks)` | those content blocks (see `presa.content`) |
| a map with `"content"` | that result, as is |
| `(server/tool-error message)` | `isError: true`, for errors the model can act on |
| nil | an empty result |

A handler that throws reports an `isError` result with the exception's
message. To answer with a JSON-RPC error instead, throw `server/error`.

```clojure
(def tools
  [{:name "add"
    :description "Add numbers."
    :input-schema {"type" "object"
                   "properties" {"numbers" {"type" "array" "items" {"type" "number"}}}
                   "required" ["numbers"]}
    :output-schema {"type" "object" "properties" {"sum" {"type" "number"}} "required" ["sum"]}
    :handler (fn [{:strs [numbers]} _] {"sum" (reduce + numbers)})}
   {:name "logo"
    :description "A one-pixel PNG."
    :handler (fn [_ _]
               (server/content
                [(content/text "Here is the logo:")
                 (content/image "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z4p8AAAAASUVORK5CYII="
                                "image/png")]))}])
```

Arguments are checked against `:input-schema` and structured results
against `:output-schema` with `presa.schema`, a dependency-free subset of
JSON Schema (`type`, `enum`, `const`, `required`, `properties`,
`additionalProperties`, `items`, the size and range keywords, `pattern`).
Invalid arguments become an `isError` result naming the path, so the model
can correct its call. `:validate? false` on the server turns this off, and
`:validator` replaces the validator: `presa.schema.networknt/validator`
gives full 2020-12 validation on the JVM with
`com.networknt/json-schema-validator` added.

### Keywords instead of strings

Schemas may use keyword keys and values. presa turns them into JSON Schema
with string keys when the tool is defined, so `tools/list` and the
validator see `{"type" "object" ...}`; a namespaced keyword `:a/b` becomes
`"a/b"`. Arguments still arrive with string keys unless the server, or one
tool, sets `:keywordize-args?`: then the handler gets keyword keys,
recursively, as `clojure.walk/keywordize-keys` gives them, with names as
the client sent them (`first_name` stays `:first_name`). Arguments are
validated before they are converted, and a task's handler gets the same
keys.

```clojure
(def greeter
  (server/server
   {:name "greeter" :version "0.1.0"
    :keywordize-args? true
    :tools [{:name "greet"
             :input-schema {:type :object
                            :properties {:name {:type :string}
                                         :style {:enum [:plain :loud]}}
                            :required [:name]}
             :handler (fn [{:keys [name style]} _]
                        (cond-> (str "Hello, " name "!")
                          (= "loud" style) clojure.string/upper-case))}]}))

(get (:input-schema (server/tool greeter "greet")) "required")
;; => ["name"]

(let [g (client/connect (client/in-process greeter))]
  (try (client/text (client/call-tool g "greet" {"name" "Ada" "style" "loud"}))
       (finally (client/close g))))
;; => "HELLO, ADA!"
```

## Resources and templates

A resource has a URI and a handler of the context. It returns a string
(text), a byte array (blob, sent as base64), or contents maps.

A resource template has an RFC 6570 URI template (`{var}` for one path
segment, `{+var}` for the rest of the URI) and a handler of the variables
and the context. It returns nil when there is no such resource, which the
client sees as -32602 `Resource not found` with the URI in `data`.

```clojure
(def people {"ada" "Ada Lovelace" "alan" "Alan Turing"})

(def resources
  [{:uri "file:///readme.txt" :name "readme" :mime-type "text/plain"
    :handler (fn [_] "Read me first.")}])

(def templates
  [{:uri-template "people://{id}" :name "person" :mime-type "text/plain"
    :handler (fn [{:strs [id]} _] (get people id))
    :complete {"id" (fn [value _ _] (filter #(.startsWith ^String % value) (sort (keys people))))}}])
```

## Prompts

A prompt's handler takes the arguments (string to string) and the context,
and returns a string (one user message), a sequence of messages (strings,
or maps with `"role"` and `"content"`), or a map with `"messages"`.
Required arguments are checked before the handler runs.

```clojure
(def prompts
  [{:name "introduce"
    :description "Introduce a person."
    :arguments [{:name "id" :description "Who" :required true}]
    :handler (fn [{:strs [id]} _] (str "Introduce " (get people id) " in one sentence."))
    :complete {"id" (fn [value _ _] (filter #(.startsWith ^String % value) (sort (keys people))))}}])
```

`:complete` maps an argument name to a completion function of the value
typed so far, the other arguments, and the context. It returns strings, or
a map with `"values"`, `"total"` and `"hasMore"`; at most 100 values are
sent.

## Putting it together

```clojure
(def srv (server/server {:name "library" :version "0.1.0"
                         :tools tools
                         :resources resources
                         :resource-templates templates
                         :prompts prompts
                         ;; list results of more than 10 items come in pages
                         :page-size 10}))

(def c (client/connect (client/in-process srv)))

(get (client/call-tool c "add" {"numbers" [1 2 3]}) "structuredContent")
;; => {"sum" 6}

(client/text (client/call-tool c "add" {"numbers" ["one"]}))
;; => "Invalid arguments at /numbers/0: expected number, got string"

(get-in (client/read-resource c "people://ada") [0 "text"])
;; => "Ada Lovelace"

(:message (ex-data (try (client/read-resource c "people://nobody") (catch Exception e e))))
;; => "Resource not found"

(get-in (client/get-prompt c "introduce" {"id" "alan"}) ["messages" 0 "content" "text"])
;; => "Introduce Alan Turing in one sentence."

(get (client/complete c {"type" "ref/prompt" "name" "introduce"} "id" "a") "values")
;; => ["ada" "alan"]
```

The capabilities a server advertises follow from what it defines: `tools`
when it has tools, `resources` for resources or templates, `prompts`,
`completions` when any definition has `:complete`, and `logging` with
`:logging true`.

```clojure
(:server-capabilities (client/info c))
;; => {"tools" {} "resources" {} "prompts" {} "completions" {}}
```

## Changing a running server

Definitions can change while the server serves. Each request reads one
snapshot of them; pagination cursors from before a change expire; and with
`:list-changed true`, clients listening for list changes are told (see
[subscriptions.md](subscriptions.md)).

```clojure
(server/add-tool! srv {:name "late" :description "Added while serving" :handler (fn [_ _] "late")})

(map #(get % "name") (client/list-tools c))
;; => ["add" "logo" "late"]

(server/remove-tool! srv "late")
(client/close c)
```

`add-resource!`, `remove-resource!`, `add-resource-template!`,
`remove-resource-template!`, `add-prompt!` and `remove-prompt!` work the same
way.

## Caching hints

2026-07-28 list, read and discover results carry `ttlMs` and `cacheScope`.
`:cache {:ttl-ms 60000 :scope "public"}` on the server sets them (the
default is 0 and `private`); a presa client with `:cache true` keeps
results for their TTL (see [client.md](client.md)).
