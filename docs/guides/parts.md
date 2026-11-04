# Parts

A part is a piece of a server as plain data: tools, resources, resource
templates and prompts as `presa.server/server` takes them, optional
`:instructions`, and an optional `:close!` function. Every key is optional.
A library can hand back a part instead of a server, and a caller combines
several parts into one server: a project's own tools next to a library's,
for example.

```clojure
(require '[presa.part :as part]
         '[presa.client :as client])

(def notes (atom []))

(def notebook
  {:tools [{:name "add"
            :input-schema {:type :object :properties {:text {:type :string}} :required [:text]}
            :keywordize-args? true
            :handler (fn [{:keys [text]} _] (swap! notes conj text) {"count" (count @notes)})}
           {:name "list" :handler (fn [_ _] (clojure.string/join "\n" @notes))}]
   :instructions "Keep notes with add and list."
   :close! (fn [] (reset! notes []) :cleared)})

(def clock
  {:tools [{:name "now" :handler (fn [_ _] "12:00")}]})
```

## Combining

`combine` concatenates parts, joins their instructions, and gives one
`:close!` that closes each part in order. A duplicate tool or prompt name,
resource URI or URI template throws; `prefix` renames a part's tools and
prompts to keep them apart.

```clojure
(map :name (:tools (part/combine [notebook (part/prefix clock "clock")])))
;; => ("add" "list" "clock_now")
```

## Serving and calling

`server` builds a server from `:parts` and any `presa.server/server`
options, with defaults that suit a local tool server: the working
directory's name, version `"0"`, and every protocol revision, so Codex
connects too. It returns the server and the combined part, whose `:close!`
the caller runs when the server stops. `serve!` does that over stdio:

<!-- no-run -->
```clojure
(defn -main [& _]
  (part/serve! {:name "notes" :parts [notebook clock]}))
```

`with-client` connects a client to the same server in process, runs a
function with it, then closes the client and the parts. `call` returns a
tool's structured content, or its text, and throws on an `isError` result.
Code in the same process uses the tools an agent sees this way:

```clojure
(part/with-client {:name "notes" :parts [notebook clock]}
  (fn [c]
    (part/call c "add" {"text" "buy milk"})
    [(part/call c "add" {"text" "call Ada"})
     (part/call c "list")
     (:instructions (client/info c))]))
;; => [{"count" 2} "buy milk\ncall Ada" "Keep notes with add and list."]

@notes
;; => []
```

The notebook's `:close!` ran when `with-client` returned, so its notes are
gone.
