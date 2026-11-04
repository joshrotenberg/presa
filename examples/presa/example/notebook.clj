(ns presa.example.notebook
  "An MCP notebook server: notes in memory, served over stdio or Streamable
  HTTP. It uses most of presa in one place; docs/guides/example.md walks
  through it. Run it with

      bb --config /path/to/presa/bb.edn -m presa.example.notebook
      bb --config /path/to/presa/bb.edn -m presa.example.notebook --http 8080

  With --http, NOTEBOOK_ISSUER and NOTEBOOK_JWKS (the authorization server's
  issuer and JWKS URL) turn on OAuth: every request needs a token for the
  endpoint, and the tools that change notes need the notes:write scope."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [presa.auth :as auth]
            [presa.auth.jwt :as jwt]
            [presa.input :as input]
            [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.http :as http]
            [presa.transport.stdio :as stdio]))

;; The store: {:next-id n :notes {id note}}, a note being
;; {"id" .. "title" .. "body" .. "tags" [..]}

(defn- note-uri [id] (str "notes://notes/" id))

(defn- summary [note] (select-keys note ["id" "title" "tags"]))

(defn- markdown [{:strs [title body tags]}]
  (str "# " title "\n\n" body
       (when (seq tags) (str "\n\nTags: " (str/join ", " tags)))))

(defn- note-resource
  "The listed resource of a note."
  [store {:strs [id title]}]
  {:uri (note-uri id) :name id :title title :mime-type "text/markdown"
   :handler (fn [_] (if-let [n (get-in @store [:notes id])]
                      (markdown n)
                      (throw (server/error :invalid-params "Resource not found" {"uri" (note-uri id)}))))})

(defn- find-note [store id]
  (or (get-in @store [:notes id])
      (server/tool-error (str "No note " id))))

(defn- tool-error? [v] (and (map? v) (true? (get v "isError"))))

(defn- note-ids [store] (sort (keys (:notes @store))))

(defn- complete-from [values]
  (fn [typed _ _] (filter #(str/starts-with? % (str typed)) values)))

;; Tools

(def ^:private note-schema
  {"type" "object"
   "properties" {"id" {"type" "string"} "title" {"type" "string"}
                 "tags" {"type" "array" "items" {"type" "string"}}}
   "required" ["id" "title" "tags"]})

(defn- tools [store {:keys [export-delay-ms]}]
  [{:name "create_note"
    :title "Create a note"
    :description "Create a note with a title, a body and optional tags. Returns its id."
    :input-schema {"type" "object"
                   "properties" {"title" {"type" "string"} "body" {"type" "string"}
                                 "tags" {"type" "array" "items" {"type" "string"}}}
                   "required" ["title" "body"]}
    :output-schema note-schema
    :handler (fn [{:strs [title body tags]} ctx]
               (let [id (str "n" (:next-id (swap! store update :next-id inc)))
                     note {"id" id "title" title "body" body "tags" (vec (distinct tags))}]
                 (swap! store assoc-in [:notes id] note)
                 ;; a new listed resource: listeners hear resources/list_changed
                 (server/add-resource! (:server ctx) (note-resource store note))
                 (server/resource-updated! ctx "notes://index")
                 (server/log! ctx "info" (str "created " id))
                 (summary note)))}

   {:name "search_notes"
    :title "Search notes"
    :description "Find notes whose title or body contains the query (case-insensitive), optionally only those with a tag."
    :input-schema {"type" "object"
                   "properties" {"query" {"type" "string"} "tag" {"type" "string"}}
                   "required" ["query"]}
    :output-schema {"type" "object"
                    "properties" {"notes" {"type" "array" "items" note-schema}}
                    "required" ["notes"]}
    :annotations {"readOnlyHint" true}
    :handler (fn [{:strs [query tag]} ctx]
               (let [q (str/lower-case query)
                     hits (->> (vals (:notes @store))
                               (filter #(or (str/includes? (str/lower-case (get % "title")) q)
                                            (str/includes? (str/lower-case (get % "body")) q)))
                               (filter #(or (nil? tag) (some #{tag} (get % "tags"))))
                               (sort-by #(get % "id"))
                               (mapv summary))]
                 (server/log! ctx "debug" (str (count hits) " notes match " (pr-str query)))
                 {"notes" hits}))}

   {:name "tag_note"
    :title "Tag a note"
    :description "Add a tag to a note."
    :input-schema {"type" "object"
                   "properties" {"id" {"type" "string"} "tag" {"type" "string"}}
                   "required" ["id" "tag"]}
    :output-schema note-schema
    :handler (fn [{:strs [id tag]} ctx]
               (let [note (find-note store id)]
                 (if (tool-error? note)
                   note
                   (let [updated (update note "tags" #(vec (distinct (conj % tag))))]
                     (swap! store assoc-in [:notes id] updated)
                     (server/resource-updated! ctx (note-uri id))
                     (summary updated)))))}

   {:name "delete_note"
    :title "Delete a note"
    :description "Delete a note, after the user confirms."
    :input-schema {"type" "object" "properties" {"id" {"type" "string"}} "required" ["id"]}
    :annotations {"destructiveHint" true}
    :handler (fn [{:strs [id]} ctx]
               (let [note (find-note store id)]
                 (cond
                   (tool-error? note) note

                   ;; the second call: the client answered the elicitation
                   (server/input-response ctx "confirm")
                   (let [{:strs [action content]} (server/input-response ctx "confirm")]
                     (if (and (= "accept" action) (true? (get content "delete")))
                       (do (swap! store update :notes dissoc id)
                           (server/remove-resource! (:server ctx) (note-uri id))
                           (server/resource-updated! ctx "notes://index")
                           (str "Deleted " id))
                       (str "Kept " id)))

                   :else
                   (server/input-required
                    {"confirm" (input/elicit (str "Delete \"" (get note "title") "\"?")
                                             {"type" "object"
                                              "properties" {"delete" {"type" "boolean" "title" "Delete it"}}
                                              "required" ["delete"]})}))))}

   {:name "summarize_note"
    :title "Summarize a note"
    :description "Summarize a note with the client's model (sampling)."
    :input-schema {"type" "object" "properties" {"id" {"type" "string"}} "required" ["id"]}
    :annotations {"readOnlyHint" true}
    :handler (fn [{:strs [id]} ctx]
               (let [note (find-note store id)]
                 (cond
                   (tool-error? note) note

                   (server/input-response ctx "summary")
                   (get-in (server/input-response ctx "summary") ["content" "text"])

                   :else
                   (server/input-required
                    {"summary" (input/sample [{"role" "user"
                                               "content" {"type" "text"
                                                          "text" (str "Summarize this note in one sentence.\n\n"
                                                                      (markdown note))}}]
                                             200
                                             {:system-prompt "You write short, plain summaries."})}))))}

   {:name "export_notes"
    :title "Export notes"
    :description "Export every note as Markdown or JSON. A long call: runs as a task for clients that support tasks."
    :input-schema {"type" "object"
                   "properties" {"format" {"type" "string" "enum" ["markdown" "json"]}}}
    :task :optional
    :handler (fn [{:strs [format]} ctx]
               (let [notes (map #(get-in @store [:notes %]) (note-ids store))
                     total (count notes)]
                 (loop [done 0 out []]
                   (cond
                     ((:cancelled? ctx)) (server/tool-error "Export cancelled")

                     (= done total)
                     (if (= "json" format)
                       (json/write-str out)
                       (str/join "\n\n---\n\n" (map markdown out)))

                     :else
                     (do (Thread/sleep (long export-delay-ms))
                         (server/progress! ctx (inc done) {:total total :message (str "exported " (inc done))})
                         (recur (inc done) (conj out (nth notes done))))))))}])

;; Resources and prompts

(defn- index-resource [store]
  {:uri "notes://index" :name "index" :title "Every note" :mime-type "application/json"
   :description "The id, title and tags of every note."
   :handler (fn [_] (json/write-str (mapv #(summary (get-in @store [:notes %])) (note-ids store))))})

(defn- tag-template [store]
  {:uri-template "notes://tags/{tag}" :name "tag" :title "Notes with a tag" :mime-type "application/json"
   :handler (fn [{:strs [tag]} _]
              (json/write-str (->> (vals (:notes @store))
                                   (filter #(some #{tag} (get % "tags")))
                                   (sort-by #(get % "id"))
                                   (mapv summary))))
   :complete {"tag" (fn [typed others ctx]
                      ((complete-from (sort (distinct (mapcat #(get % "tags") (vals (:notes @store))))))
                       typed others ctx))}})

(defn- review-prompt [store]
  {:name "review_note" :title "Review a note"
   :description "Ask the model to review a note and suggest improvements."
   :arguments [{:name "id" :description "The note's id" :required true}]
   :handler (fn [{:strs [id]} _]
              (if-let [note (get-in @store [:notes id])]
                (str "Review this note. Point out anything unclear and suggest tags.\n\n" (markdown note))
                (throw (server/error :invalid-params (str "No note " id)))))
   :complete {"id" (fn [typed others ctx] ((complete-from (note-ids store)) typed others ctx))}})

;; The server

(defn notebook
  "A notebook server. Options:

  - :notes            notes to start with, maps of \"title\" \"body\" \"tags\"
  - :export-delay-ms  the time export_notes spends on each note (default 200)
  - :write-scopes     scopes the tools that change notes need (default none;
                      set them only with authorization, as a caller without
                      a token has no scopes)
  - :tasks            Tasks extension options (default {:poll-interval-ms 100})

  Returns the presa.server map; :notebook/store holds the notes atom."
  ([] (notebook {}))
  ([{:keys [notes export-delay-ms write-scopes tasks]
     :or {export-delay-ms 200 tasks {:poll-interval-ms 100}}}]
   (let [store (atom {:next-id 0 :notes {}})
         opts {:export-delay-ms export-delay-ms}
         srv (server/server
              {:name "presa-notebook"
               :version "0.1.0"
               :title "Notebook"
               :instructions "A notebook. Create, search, tag, summarize, export and delete notes. Notes are resources at notes://notes/{id}; notes://index lists them."
               :versions protocol/versions
               :logging true
               :list-changed true
               :subscribe true
               :tasks tasks
               :required-scopes (into {} (for [t ["create_note" "tag_note" "delete_note"]
                                               :when (seq write-scopes)]
                                           [[:tool t] (vec write-scopes)]))
               :tools (tools store opts)
               :resources [(index-resource store)]
               :resource-templates [(tag-template store)]
               :prompts [(review-prompt store)]})]
     (doseq [{:strs [title body tags]} notes]
       (let [id (str "n" (:next-id (swap! store update :next-id inc)))
             note {"id" id "title" title "body" body "tags" (vec tags)}]
         (swap! store assoc-in [:notes id] note)
         (server/add-resource! srv (note-resource store note))))
     (assoc srv :notebook/store store))))

(def sample-notes
  [{"title" "Shopping" "body" "Milk, eggs and coffee." "tags" ["home"]}
   {"title" "Release checklist" "body" "Run the conformance lanes, then tag the release." "tags" ["work"]}])

(defn- serve-http [port]
  (let [issuer (System/getenv "NOTEBOOK_ISSUER")
        jwks (System/getenv "NOTEBOOK_JWKS")
        resource (str "http://127.0.0.1:" port "/mcp")
        secured? (and issuer jwks)
        srv (notebook {:notes sample-notes :write-scopes (when secured? ["notes:write"])})
        config (when secured?
                 (auth/resource-server
                  {:resource resource
                   :verify (jwt/verifier {:audience resource :issuer issuer :jwks-url jwks})
                   :authorization-servers [issuer]
                   :scopes-supported ["notes:read" "notes:write"]}))
        {:keys [url]} (http/serve! srv (cond-> {:port port} config (assoc :auth config)))]
    (binding [*out* *err*]
      (println "notebook at" url (if config "(OAuth)" "")))
    @(promise)))

(defn -main [& args]
  (if-let [port (second (drop-while #(not= "--http" %) args))]
    (serve-http (parse-long port))
    (do (stdio/serve! (notebook {:notes sample-notes}))
        (shutdown-agents))))
