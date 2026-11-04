(ns presa.example.notebook-repl
  "A REPL walkthrough of the notebook server with presa.client. Start a REPL
  with the examples on the classpath, under Babashka or the JVM:

      bb --config /path/to/presa/bb.edn nrepl-server
      clojure -A:dev            (or your editor's jack-in with that alias)

  then evaluate the forms in the comment block below one at a time. Each
  `;; =>` line is the result the form before it returns; the guides test
  evaluates the block and checks every one, so they stay true."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [presa.client :as client]
            [presa.example.notebook :as notebook]
            [presa.server :as server]))

(comment
  ;; A notebook with two notes, in this process. export_notes spends 20 ms
  ;; per note so the walkthrough stays quick.
  (def srv (notebook/notebook {:notes notebook/sample-notes :export-delay-ms 20}))

  ;; What the client answers when the server asks: a stand-in for the user
  ;; (elicitation) and for the client's model (sampling).
  (def answers (atom {"delete" true}))
  (def handlers
    {"elicitation/create" (fn [_] {"action" "accept" "content" @answers})
     "sampling/createMessage" (fn [{:strs [messages]}]
                                {"role" "assistant" "model" "stand-in"
                                 "content" {"type" "text"
                                            "text" (str "A note of " (count (get-in messages [0 "content" "text"])) " characters.")}})})

  (def logs (atom []))
  (def c (client/connect (client/in-process srv)
                         {:handlers handlers :tasks true
                          :log-level "info" :on-log #(swap! logs conj (get % "data"))}))

  ;; The revision both sides agreed on, and what the server offers
  (:version (client/info c))
  ;; => "2026-07-28"

  (map #(get % "name") (client/list-tools c))
  ;; => ["create_note" "search_notes" "tag_note" "delete_note" "summarize_note" "export_notes"]

  ;; Listen for changes before making any: new resources (each note is one)
  ;; and updates to the index
  (def changes (atom []))
  (def sub (client/listen c {"resourcesListChanged" true "resourceSubscriptions" ["notes://index"]}
                          #(swap! changes conj (get % "method"))))
  (some? (deref (:acknowledged sub) 2000 nil))
  ;; => true

  ;; Tools with structured output
  (get (client/call-tool c "create_note" {"title" "Ideas" "body" "Try the proxy with two notebooks." "tags" ["work"]})
       "structuredContent")
  ;; => {"id" "n3" "title" "Ideas" "tags" ["work"]}

  (map #(get % "id") (get-in (client/call-tool c "search_notes" {"query" "the" "tag" "work"}) ["structuredContent" "notes"]))
  ;; => ["n2" "n3"]

  ;; create_note logged at info, the level this client asked for
  @logs
  ;; => ["created n3"]

  ;; The changes arrive on the subscription, on another thread
  (Thread/sleep 500)
  @changes
  ;; => ["notifications/subscriptions/acknowledged" "notifications/resources/list_changed" "notifications/resources/updated"]

  ;; Resources: the index, each note, and a template by tag
  (map #(get % "uri") (client/list-resources c))
  ;; => ["notes://index" "notes://notes/n1" "notes://notes/n2" "notes://notes/n3"]

  (get-in (client/read-resource c "notes://notes/n3") [0 "text"])
  ;; => "# Ideas\n\nTry the proxy with two notebooks.\n\nTags: work"

  (map #(get % "title") (json/read-str (get-in (client/read-resource c "notes://tags/work") [0 "text"])))
  ;; => ["Release checklist" "Ideas"]

  ;; Completion of template arguments and prompt arguments
  (get (client/complete c {"type" "ref/resource" "uri" "notes://tags/{tag}"} "tag" "w") "values")
  ;; => ["work"]

  (get (client/complete c {"type" "ref/prompt" "name" "review_note"} "id" "n") "values")
  ;; => ["n1" "n2" "n3"]

  (get-in (client/get-prompt c "review_note" {"id" "n1"}) ["messages" 0 "role"])
  ;; => "user"

  ;; Sampling: the server asks the client's model, through the handler above
  (client/text (client/call-tool c "summarize_note" {"id" "n1"}))
  ;; => "A note of 84 characters."

  ;; Elicitation: delete_note asks the user to confirm
  (reset! answers {"delete" false})
  (client/text (client/call-tool c "delete_note" {"id" "n1"}))
  ;; => "Kept n1"

  (reset! answers {"delete" true})
  (client/text (client/call-tool c "delete_note" {"id" "n1"}))
  ;; => "Deleted n1"

  ;; A long call becomes a task, because this client declared Tasks
  (def created (client/call-tool c "export_notes" {"format" "json"}))
  (client/task? created)
  ;; => true

  (map #(get % "id") (json/read-str (get-in (client/await-task c created {:listen? true}) ["result" "content" 0 "text"])))
  ;; => ["n2" "n3"]

  ;; A client without Tasks gets the same call synchronously, with progress
  (def progress (atom []))
  (def plain (client/connect (client/in-process srv)))
  (first (str/split-lines (client/text (client/call-tool plain "export_notes" {} {:on-progress #(swap! progress conj (get % "message"))}))))
  ;; => "# Release checklist"

  @progress
  ;; => ["exported 1" "exported 2"]

  ;; Errors the model can act on are tool errors, not exceptions
  (client/text (client/call-tool c "tag_note" {"id" "n9" "tag" "x"}))
  ;; => "No note n9"

  ;; The server side is a value you can change while clients are connected
  (server/remove-tool! srv "summarize_note")
  (count (client/list-tools c))
  ;; => 5

  ((:stop! sub))
  (client/close plain)
  (client/close c)
  )
