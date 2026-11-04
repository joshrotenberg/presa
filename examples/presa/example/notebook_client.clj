(ns presa.example.notebook-client
  "A client walk-through of the notebook server: it connects, uses each
  feature and prints what happens. Run it against a notebook in this
  process, one it starts over stdio, or one served over HTTP:

      bb --config /path/to/presa/bb.edn -m presa.example.notebook-client
      bb --config /path/to/presa/bb.edn -m presa.example.notebook-client stdio
      bb --config /path/to/presa/bb.edn -m presa.example.notebook-client http http://127.0.0.1:8080/mcp

  Add --version 2025-11-25 (or 2025-06-18) to speak an initialize-era
  revision. The client's handlers stand in for a user (elicitation) and a
  model (sampling)."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [presa.client :as client]
            [presa.example.notebook :as notebook]
            [presa.protocol :as protocol]
            [presa.transport.http :as http]
            [presa.transport.stdio :as stdio]))

(def handlers
  {"elicitation/create" (fn [{:strs [message]}]
                          (println "  < the server asks:" message "- answering yes")
                          {"action" "accept" "content" {"delete" true}})
   "sampling/createMessage" (fn [_]
                              (println "  < the server asks the model for a summary")
                              {"role" "assistant" "model" "stand-in"
                               "content" {"type" "text" "text" "A note about proxies."}})})

(defn- step [title] (println) (println (str "== " title)))

(defn- structured [result] (get result "structuredContent"))

(defn walk
  "Use each feature of the notebook through client c, printing as it goes.
  Returns a map of what it saw, for tests."
  [c]
  (let [{:keys [version]} (client/info c)
        stateless? (protocol/stateless? version)
        seen (atom {:version version})
        changes (atom [])]
    (step (str "Connected with " version))
    (println "  tools:" (str/join ", " (map #(get % "name") (client/list-tools c))))

    (let [sub (when stateless?
                (client/listen c {"resourcesListChanged" true "resourceSubscriptions" ["notes://index"]}
                               #(swap! changes conj (get % "method"))))]
      (when sub (deref (:acknowledged sub) 2000 nil))

      (step "Tools with structured output")
      (let [note (structured (client/call-tool c "create_note" {"title" "Ideas" "body" "Proxy two notebooks." "tags" ["work"]}))
            hits (get (structured (client/call-tool c "search_notes" {"query" "e"})) "notes")]
        (println "  created" (get note "id"))
        (println "  search for \"e\":" (str/join ", " (map #(get % "title") hits)))
        (swap! seen assoc :created (get note "id") :hits (mapv #(get % "id") hits)))

      (step "Resources")
      (let [uris (map #(get % "uri") (client/list-resources c))]
        (println "  listed:" (str/join ", " uris))
        (println "  index:" (get-in (client/read-resource c "notes://index") [0 "text"]))
        (println "  tag work:" (count (json/read-str (get-in (client/read-resource c "notes://tags/work") [0 "text"]))) "notes")
        (swap! seen assoc :resources (vec uris)))

      (step "Completion and prompts")
      (let [ids (get (client/complete c {"type" "ref/prompt" "name" "review_note"} "id" "n") "values")]
        (println "  ids starting with n:" (str/join ", " ids))
        (println "  review_note:" (first (str/split-lines (get-in (client/get-prompt c "review_note" {"id" (:created @seen)})
                                                                  ["messages" 0 "content" "text"]))))
        (swap! seen assoc :completed ids))

      (step "Sampling and elicitation")
      (let [summary (client/text (client/call-tool c "summarize_note" {"id" (:created @seen)}))
            deleted (client/text (client/call-tool c "delete_note" {"id" (:created @seen)}))]
        (println "  summary:" summary)
        (println (str "  " deleted))
        (swap! seen assoc :summary summary :deleted deleted))

      (step "A long call")
      (let [progress (atom [])
            result (client/call-tool c "export_notes" {"format" "json"}
                                     {:on-progress #(swap! progress conj (get % "message"))})
            result (if (client/task? result)
                     (do (println "  running as task" (get result "taskId"))
                         (get (client/await-task c result) "result"))
                     result)
            exported (json/read-str (client/text result))]
        (when (seq @progress) (println "  progress:" (str/join ", " @progress)))
        (println "  exported" (count exported) "notes")
        (swap! seen assoc :exported (count exported)))

      (when sub
        (Thread/sleep 300)
        ((:stop! sub))
        (step "Changes heard on subscriptions/listen")
        (doseq [[method n] (frequencies @changes)] (println (str "  " method " x" n)))
        (swap! seen assoc :changes (set @changes))))
    @seen))

(defn- bb-config []
  ;; the bb.edn of this checkout, so the stdio server runs the same code
  (str (.getCanonicalPath (io/file "bb.edn"))))

(defn -main [& args]
  (let [[mode url] (remove #(str/starts-with? % "--") (take-while #(not= "--version" %) args))
        version (second (drop-while #(not= "--version" %) args))
        transport (case mode
                    "stdio" (stdio/command ["bb" "--config" (bb-config) "-m" "presa.example.notebook"]
                                           {:stderr (fn [_])})
                    "http" (http/url url)
                    (client/in-process (notebook/notebook {:notes notebook/sample-notes :export-delay-ms 50})))
        c (client/connect transport (cond-> {:handlers handlers :tasks true}
                                      version (assoc :versions [version])))]
    (try (walk c)
         (finally (client/close c)))
    (shutdown-agents)))
