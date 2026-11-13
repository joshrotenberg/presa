(ns presa.nrepl
  "An nREPL server as a part (see presa.part): tools that evaluate code in a
  running REPL, load a file into it and look a symbol up.

      (presa.part/serve! {:name \"repl\" :parts [(presa.nrepl/part {:port 7888})]})

  or, as a server of its own:

      bb -m presa.nrepl --port 7888

  How an evaluation maps onto MCP:

  - printed output arrives as progress notifications while the code runs,
    and in the result
  - cancelling the tool call interrupts the evaluation
  - a call that reaches its timeout returns what it has and leaves the code
    running; the await and interrupt tools follow up
  - code that reads *in* asks the client for a line (a form elicitation)
    when the client supports that, and reads end of input otherwise

  Security: eval and load_file run arbitrary code inside the REPL's process,
  with everything that process can do: read and write files, start programs,
  use the network. A host's sandbox and its rules for shell commands do not
  apply to that code, so allowing eval without review is close to allowing
  any command. nREPL has no permission model and this part adds none. For
  unattended use, run the REPL itself in a restricted environment (a
  container, a separate user), and over HTTP put the tools behind scopes
  (:required-scopes of presa.server/server). See docs/guides/nrepl.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [presa.input :as input]
            [presa.nrepl.client :as nrepl]
            [presa.part :as part]
            [presa.server :as server]
            [presa.skill :as skill]))

;; Bounded output: the first and last halves of what was printed

(def ^:private empty-buffer {:head "" :tail "" :dropped 0})

(defn- append [{:keys [head tail dropped]} s half]
  (let [to-head (subs s 0 (min (max 0 (- half (count head))) (count s)))
        tail (str tail (subs s (count to-head)))
        over (- (count tail) half)]
    {:head (str head to-head)
     :tail (if (pos? over) (subs tail over) tail)
     :dropped (if (pos? over) (+ dropped over) dropped)}))

(defn- buffer-text
  "The buffer as text, cut to max-lines lines as well: half from the start
  and half from the end, around a line saying how much is missing."
  [{:keys [head tail dropped]} max-lines]
  (let [half (max 1 (quot (long max-lines) 2))
        lines #(str/split % #"(?<=\n)")
        ;; nothing dropped yet: head and tail are one run of text
        [head tail] (if (zero? (long dropped))
                      (let [all (lines (str head tail))]
                        (if (> (count all) (* 2 half))
                          [(apply str (take half all)) (apply str (drop half all))]
                          [(str head tail) ""]))
                      [head tail])
        head* (if (seq tail) (apply str (take half (lines head))) head)
        tail* (apply str (take-last half (lines tail)))
        dropped (+ (long dropped) (- (count head) (count head*)) (- (count tail) (count tail*)))]
    (str head*
         (when (pos? dropped)
           (str (when-not (or (str/blank? head*) (str/ends-with? head* "\n")) "\n")
                "... " dropped " characters dropped ...\n"))
         tail*)))

(defn- clip [s limit]
  (if (> (count s) limit)
    (str (subs s 0 limit) " ... " (- (count s) limit) " more characters")
    s))

;; The connection and its session

(defn- port-of [{:keys [port dir]}]
  (or port
      (let [f (io/file (or dir ".") ".nrepl-port")]
        (when (.exists f) (parse-long (str/trim (slurp f)))))
      (throw (ex-info (str "No nREPL port: pass :port, or start a REPL that writes "
                           (io/file (or dir ".") ".nrepl-port"))
                      {:kind :nrepl-connection}))))

(defn- ensure!
  "The live connection state {:conn :session :ops}, connecting and cloning a
  session when there is none. :fresh? is true on the call that made the
  session."
  [{:keys [state opts lock]}]
  (locking lock
    (let [{:keys [conn session] :as st} @state
          conn (if (and conn (nrepl/open? conn))
                 conn
                 (nrepl/connect {:host (:host opts "127.0.0.1") :port (port-of opts)}))
          new-conn? (not (identical? conn (:conn st)))
          ops (if new-conn?
                (set (map name (keys (get (nrepl/request conn {"op" "describe"}) "ops"))))
                (:ops st))
          fresh? (or new-conn? (nil? session))
          session (if fresh?
                    (get (nrepl/request conn {"op" "clone" "client-name" "presa"}) "new-session")
                    session)]
      (reset! state {:conn conn :session session :ops ops})
      {:conn conn :session session :ops ops :fresh? fresh?})))

(defn- drop-session!
  "Forget session, so the next call clones a new one."
  [{:keys [state]} session]
  (swap! state #(if (= session (:session %)) (dissoc % :session) %)))

;; One evaluation
;;
;; An evaluation outlives the tool call that started it: a thread reads its
;; answers into the slot below for as long as it runs, and tool calls watch
;; the slot. The part holds one slot at a time, as a session runs one
;; evaluation at a time.

(defn- absorb
  "acc with one answer of an evaluation folded in."
  [acc m {:keys [max-output-chars]}]
  (let [half (quot (long max-output-chars) 2)
        printed (or (get m "out") (get m "err"))]
    (cond-> acc
      (contains? m "out") (update :out append (get m "out") half)
      (contains? m "err") (update :err append (get m "err") half)
      (contains? m "value") (-> (update :values conj (clip (get m "value") max-output-chars))
                                (update :value-count (fnil inc 0)))
      (contains? m "ns") (assoc :ns (get m "ns"))
      (contains? m "ex") (assoc :ex (get m "ex"))
      (nrepl/status? m "interrupted") (assoc :interrupted? true)
      ;; what a watching call has yet to report as progress
      (seq printed) (update :fresh #(vec (take-last 50 (conj % printed)))))))

(def ^:private empty-acc {:values [] :out empty-buffer :err empty-buffer :fresh []})

(defn- pump!
  "Read the evaluation's answers into its slot until it is done."
  [{:keys [opts] :as bridge} {:keys [sent acc phase session]}]
  (doto (Thread. ^Runnable
         (fn []
           (loop []
             (let [m (nrepl/poll sent 1000)]
               (cond
                 (nil? m) (recur)
                 (nrepl/status? m "connection-closed") (reset! phase :disconnected)
                 :else (do (swap! acc absorb m opts)
                           (cond
                             (nrepl/done? m) (do (when (nrepl/status? m "session-closed")
                                                   (drop-session! bridge session))
                                                 (reset! phase :done))
                             (nrepl/status? m "need-input") (do (reset! phase :need-input) (recur))
                             :else (recur)))))))
                 "presa-nrepl-eval")
    (.setDaemon true)
    (.start)))

(defn- start!
  "Send message (an eval or load-file op) and return its slot."
  [{:keys [current used?] :as bridge} message]
  (let [{:keys [conn session fresh?]} (ensure! bridge)
        sent (nrepl/send! conn (assoc message "session" session))
        slot {:conn conn :session session :sent sent
              :started (System/currentTimeMillis)
              :phase (atom :running)
              :watched? (atom true)
              :acc (atom (cond-> (merge empty-acc (select-keys (meta message) [:loaded :forms]))
                           (and fresh? @used?)
                           (assoc :note "This is a new REPL session: earlier *ns*, *1 and *e are gone, and if the REPL restarted, so are its definitions.")))}]
    (reset! used? true)
    (reset! current slot)
    (pump! bridge slot)
    slot))

(defn- result
  "A tool result from an evaluation's status and what it has accumulated."
  [opts status {:keys [values out err ns ex note loaded]}]
  (let [out (buffer-text out (:max-output-lines opts))
        err (buffer-text err (:max-output-lines opts))
        loaded (when (= "done" status) loaded)
        m (cond-> {"status" status "values" values}
            (seq out) (assoc "out" out)
            (seq err) (assoc "err" err)
            ns (assoc "ns" ns)
            ex (assoc "ex" ex)
            loaded (assoc "loaded" loaded)
            note (assoc "note" note))
        text (str/join "\n"
                       (concat (when (seq out) [(str/trim-newline out)])
                               (when (seq err) [(str "stderr:\n" (str/trim-newline err))])
                               (map #(str "=> " %) values)
                               (when loaded [(str "Loaded " loaded)])
                               (when-not (= "done" status) [(str "[" status (when ex (str ": " ex)) "]")])
                               (when note [note])))]
    (cond-> {"content" [{"type" "text" "text" (if (str/blank? text) "[no value]" text)}]
             "isError" (boolean (#{"error" "interrupted" "disconnected"} status))}
      (:structured? opts) (assoc "structuredContent" m))))

(defn- final-status [{:keys [phase acc]}]
  (cond (= :disconnected @phase) "disconnected"
        (:interrupted? @acc) "interrupted"
        (:ex @acc) "error"
        :else "done"))

(defn- finish!
  "The result of a slot that is done, which stops being the current one."
  [{:keys [opts current last-ns]} {:keys [phase acc] :as slot}]
  (compare-and-set! current slot nil)
  (when-let [n (:ns @acc)] (reset! last-ns n))
  ;; a REPL that reads one form per message (ClojureScript through
  ;; piggieback) drops the rest without a word
  (let [{:keys [forms value-count ex interrupted?]} @acc
        evaluated (or value-count 0)]
    (when (and forms (= :done @phase) (not ex) (not interrupted?) (< evaluated (long forms)))
      (swap! acc update :note
             #(str (when % (str % " "))
                   "Only " evaluated " of the " forms " forms sent were evaluated: this REPL takes one form per"
                   " call. Send one form at a time, or wrap them in (do ...)."))))
  (when (= :disconnected @phase)
    (swap! acc assoc :note "The nREPL connection closed before the evaluation finished."))
  (result opts (final-status slot) @acc))

(defn- over? [{:keys [phase]}] (contains? #{:done :disconnected} @phase))

(defn- stop!
  "Interrupt the slot's evaluation and wait :interrupt-grace-ms for it to
  finish; one that does not gives up its session, and so does one on a
  server that has no interrupt op. Returns the result."
  [{:keys [opts current state] :as bridge} {:keys [conn session sent acc] :as slot} note]
  (let [can? (contains? (:ops @state) "interrupt")]
    (when can?
      (try (nrepl/request conn {"op" "interrupt" "session" session "interrupt-id" (:id sent)} 2000)
           (catch Exception _ nil))
      (let [deadline (+ (System/currentTimeMillis) (long (:interrupt-grace-ms opts)))]
        (while (and (not (over? slot)) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 25))))
    (when-not (over? slot) (drop-session! bridge session))
    (compare-and-set! current slot nil)
    (result opts "interrupted"
            (assoc @acc :note
                   (cond
                     (over? slot) note
                     can? (str note " The evaluation did not stop when interrupted, so it may still be running;"
                               " later calls use a new session with fresh *ns*, *1 and *e.")
                     :else (str note " This nREPL server cannot interrupt an evaluation, so it was left running;"
                                " later calls use a new session with fresh *ns*, *1 and *e."))))))

(def ^:private stdin-request
  (input/elicit "The code being evaluated is reading a line from standard input."
                {"type" "object"
                 "properties" {"line" {"type" "string" "title" "Input line"}}
                 "required" ["line"]}))

(defn- send-stdin! [{:keys [conn session phase]} s]
  (reset! phase :running)
  (nrepl/request conn {"op" "stdin" "session" session "stdin" s} 5000))

(defn- take-acc!
  "What the slot has accumulated, which it then starts over: a later report
  holds only what came after this one."
  [{:keys [acc]}]
  (first (swap-vals! acc merge (dissoc empty-acc :fresh) {:note nil})))

(defn- watch
  "Follow the slot until its evaluation finishes, and return the tool
  result. A call that is cancelled interrupts the evaluation. One that
  reaches timeout-ms first leaves it running and reports what it has so far;
  `await` and `interrupt` take it from there. An evaluation that asks for
  input asks the client for a line, and `resume` continues it."
  [{:keys [opts] :as bridge} {:keys [acc phase watched? sent] :as slot} ctx timeout-ms]
  (reset! watched? true)
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))
        n (volatile! 0)]
    (try
      (loop []
        (doseq [chunk (:fresh (first (swap-vals! acc assoc :fresh [])))]
          (server/progress! ctx (vswap! n inc) {:message (clip chunk 1000)}))
        (cond
          (over? slot) (finish! bridge slot)

          (= :need-input @phase)
          (if (input/supported? stdin-request (:client-capabilities ctx))
            (server/input-required {"stdin" stdin-request} {"eval" (:id sent)})
            (do (swap! acc assoc :note "The code read standard input and got end of input: this client cannot be asked for a line.")
                (send-stdin! slot "")
                (recur)))

          ((:cancelled? ctx)) (stop! bridge slot "Cancelled by the client.")

          (>= (System/currentTimeMillis) deadline)
          (result opts "running"
                  (assoc (take-acc! slot) :note
                         (str "Still running after " timeout-ms " ms. Call await to keep waiting for it, or interrupt"
                              " to stop it; until it finishes, eval and load_file are refused.")))

          :else (do (Thread/sleep 25) (recur))))
      ;; a transport cancels a request by interrupting its thread; the
      ;; evaluation runs in the REPL and has to be told
      (catch InterruptedException _
        (Thread/interrupted)
        (stop! bridge slot "Cancelled by the client."))
      (finally (reset! watched? false)))))

(defn- resume
  "Continue the evaluation named by the call's request state with the
  client's line of input, or nil when this call is not such an answer."
  [{:keys [current] :as bridge} ctx timeout-ms]
  (when-let [id (get (:state ctx) "eval")]
    (let [slot @current
          {:strs [action content]} (server/input-response ctx "stdin")]
      (if (and slot (= id (:id (:sent slot))) (= :need-input @(:phase slot)))
        (do (when-not (= "accept" action)
              (swap! (:acc slot) assoc :note "The client gave no line, so the code read end of input."))
            (send-stdin! slot (if (= "accept" action) (str (get content "line") "\n") ""))
            (watch bridge slot ctx timeout-ms))
        (server/tool-error "The evaluation waiting for input is gone; evaluate the code again.")))))

(defn- busy [{:keys [started phase]}]
  (server/tool-error
   (str "An evaluation started " (quot (- (System/currentTimeMillis) (long started)) 1000) " s ago is still running"
        (when (= :need-input @phase) ", waiting for a line of input")
        ". Call await to wait for it, or interrupt to stop it.")))

(defn- claim!
  "A new slot for message, a tool error when an evaluation nobody is
  watching holds the session, or nil while another call watches one."
  [{:keys [current finished lock] :as bridge} message]
  (locking lock
    (let [slot @current]
      (cond
        (nil? slot) (start! bridge message)
        @(:watched? slot) nil
        (over? slot) (do (reset! finished slot)
                         (reset! current nil)
                         (doto (start! bridge message)
                           (-> :acc (swap! assoc :note "The evaluation left running earlier has finished; await returns its result."))))
        :else (busy slot)))))

(defn- evaluate
  "Run message (an eval or load-file op) and return the tool result. A call
  that arrives while another is being watched waits its turn for up to
  timeout-ms, so a call's timeout measures its own code."
  [{:keys [opts current] :as bridge} message ctx timeout-ms]
  (let [timeout-ms (or timeout-ms (:timeout-ms opts))
        deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (or (resume bridge ctx timeout-ms)
        (loop []
          (let [claimed (claim! bridge message)]
            (cond
              (:sent claimed) (watch bridge claimed ctx timeout-ms)
              claimed claimed
              ((:cancelled? ctx)) (server/tool-error "Cancelled while waiting for an earlier evaluation.")
              (>= (System/currentTimeMillis) deadline) (if-let [slot @current]
                                                         (busy slot)
                                                         (recur))
              :else (do (Thread/sleep 25) (recur))))))))

(defn- reporting
  "handler, with a failure to reach the REPL as a tool error."
  [handler]
  (fn [arguments ctx]
    (try (handler arguments ctx)
         (catch java.io.IOException e
           (server/tool-error (str "Cannot reach the nREPL server: " (ex-message e))))
         (catch clojure.lang.ExceptionInfo e
           (if (#{:nrepl-connection :nrepl-timeout} (:kind (ex-data e)))
             (server/tool-error (ex-message e))
             (throw e))))))

;; Tools

(def ^:private timeout-property
  {"type" "integer" "minimum" 1
   "description" (str "Milliseconds to wait for the result. Code still running then keeps running: "
                      "the call returns what it has, and await or interrupt follow up.")})

(defn- form-count
  "How many top-level forms code holds, or nil when it cannot be told: code
  this runtime's reader does not read, or with reader conditionals, whose
  forms depend on the REPL's platform."
  [code]
  (when-not (str/includes? code "#?")
    (try
      (binding [*read-eval* false
                *default-data-reader-fn* (fn [_ v] v)]
        (with-open [r (java.io.PushbackReader. (java.io.StringReader. code))]
          (loop [n 0]
            (if (identical? r (read {:eof r} r)) n (recur (inc n))))))
      (catch Throwable _ nil))))

(defn- eval-tool [bridge]
  {:name "eval"
   :title "Evaluate code"
   :description (str "Evaluate code in the running REPL and return each form's value with what it printed. "
                     "State persists between calls: definitions, the current namespace, *1 and *e. "
                     "An exception is reported with its class; evaluate *e for the details. "
                     "Code that outlasts the timeout keeps running and the result says [running].")
   :input-schema {"type" "object"
                  "properties" {"code" {"type" "string" "description" "One or more forms."}
                                "ns" {"type" "string"
                                      "description" "The namespace to evaluate in; it must exist. Defaults to the session's current namespace."}
                                "timeout_ms" timeout-property}
                  "required" ["code"]}
   :annotations {"destructiveHint" true "openWorldHint" true}
   :handler (reporting
             (fn [{:strs [code ns timeout_ms]} ctx]
               (evaluate bridge
                         (with-meta (cond-> {"op" "eval" "code" code} ns (assoc "ns" ns))
                           {:forms (form-count code)})
                         ctx timeout_ms)))})

(defn- load-file-tool [{:keys [opts] :as bridge}]
  {:name "load_file"
   :title "Load a file"
   :description (str "Load a source file into the running REPL, as an editor's load-file does: every form is "
                     "evaluated with the file's name and line numbers, so definitions point at their source. "
                     "Use it after editing a file.")
   :input-schema {"type" "object"
                  "properties" {"path" {"type" "string" "description" "The file: an absolute path, or one relative to the directory this server was started for, which may not be the REPL's working directory."}
                                "timeout_ms" timeout-property}
                  "required" ["path"]}
   :annotations {"destructiveHint" true "openWorldHint" true}
   :handler (reporting
             (fn [{:strs [path timeout_ms]} ctx]
               (let [f (io/file path)
                     f (if (.isAbsolute f) f (io/file (or (:dir opts) ".") path))]
                 (cond
                   ;; an answer to an evaluation waiting for input needs no file
                   (get (:state ctx) "eval") (evaluate bridge nil ctx timeout_ms)
                   (not (.isFile f)) (server/tool-error (str "No file " path))
                   :else (let [text (slurp f)
                               ;; the namespace the file declares, when it starts with an ns form
                               [_ file-ns] (re-find #"\A(?:\s|;[^\n]*\n)*\(ns\s+(?:\^:?\S+\s+|\^\{[^}]*\}\s+)*([^\s()\[\]{}\"]+)" text)]
                           (evaluate bridge
                                     (with-meta {"op" "load-file" "file" text
                                                 "file-path" (.getPath f) "file-name" (.getName f)}
                                       {:loaded (str path (when file-ns (str " (" file-ns ")")))})
                                     ctx timeout_ms))))))})

(defn- await-tool [{:keys [opts current finished] :as bridge}]
  {:name "await"
   :title "Wait for a running evaluation"
   :description (str "Wait for the evaluation that an earlier eval or load_file left running, and return what it "
                     "printed since the last report and, once it finishes, its values.")
   :input-schema {"type" "object" "properties" {"timeout_ms" timeout-property}}
   :handler (reporting
             (fn [{:strs [timeout_ms]} ctx]
               (let [timeout-ms (or timeout_ms (:timeout-ms opts))]
                 (or (resume bridge ctx timeout-ms)
                     (let [slot @current
                           [done _] (reset-vals! finished nil)]
                       (cond
                         (and slot @(:watched? slot)) (server/tool-error "Another call is already waiting for it.")
                         slot (watch bridge slot ctx timeout-ms)
                         done (result opts (final-status done) @(:acc done))
                         :else (server/tool-error "No evaluation is running.")))))))})

(defn- interrupt-tool [{:keys [current] :as bridge}]
  {:name "interrupt"
   :title "Interrupt the running evaluation"
   :description "Stop the evaluation that is running, and return what it printed since the last report."
   :input-schema {"type" "object"}
   :annotations {"destructiveHint" true}
   :handler (reporting
             (fn [_ _]
               (if-let [slot @current]
                 (stop! bridge slot "Interrupted.")
                 (server/tool-error "No evaluation is running."))))})

(defn- lookup-tool [bridge]
  {:name "lookup"
   :title "Look a symbol up"
   :description "The documentation, argument lists and source location of a var or special form, as the REPL sees it."
   :input-schema {"type" "object"
                  "properties" {"sym" {"type" "string" "description" "A symbol, qualified or not, such as clojure.string/join."}
                                "ns" {"type" "string" "description" "The namespace to resolve it in. Defaults to the session's current namespace."}}
                  "required" ["sym"]}
   :annotations {"readOnlyHint" true}
   :handler (reporting
             (fn [{:strs [sym ns]} _]
               (let [{:keys [conn session ops]} (ensure! bridge)
                     op (cond (ops "lookup") "lookup" (ops "info") "info")
                     answer (when op
                              ;; nbb answers only a request that names a namespace
                              (nrepl/request conn {"op" op "session" session "sym" sym
                                                   "ns" (or ns @(:last-ns bridge))}))
                     ;; lookup nests under "info"; cider's info op answers flat
                     ;; and bencode has no empty map: an empty one arrives as an empty list
                     info (cond (map? (get answer "info")) (get answer "info")
                                (contains? answer "info") {}
                                :else (dissoc answer "id" "session" "status"))]
                 (cond
                   (nil? op) (server/tool-error "This nREPL server has no lookup op.")
                   (or (nrepl/status? answer "no-info") (not (or (get info "name") (get info "doc"))))
                   (server/tool-error (str "Nothing known about " sym ". Its namespace may not be loaded yet."))
                   :else
                   (let [{:strs [name doc file line] arglists "arglists-str" in "ns"} info]
                     (cond-> {"content" [{"type" "text"
                                          "text" (str/join "\n" (remove nil? [(str (when (seq in) (str in "/")) name)
                                                                              (when (seq arglists) arglists)
                                                                              (when (seq doc) doc)
                                                                              (when (seq file) (str file (when line (str ":" line))))]))}]
                              "isError" false}
                       (:structured? (:opts bridge)) (assoc "structuredContent" info)))))))})

(def instructions
  (str "eval runs code in a live REPL whose state persists between calls. Prefer small evaluations: "
       "require a namespace, call one function, inspect one value. After editing a source file, load it "
       "with load_file (or require it with :reload) before relying on the change. A call that throws "
       "reports the exception's message; *e holds the exception for its data and stack trace. Code that "
       "outlasts a call's timeout keeps running: await waits for it and interrupt stops it. The REPL may "
       "be JVM Clojure, Babashka or another dialect: (System/getProperty \"babashka.version\") is nil "
       "on the JVM."))

;; Skills: guides served with the Skills extension (presa.skill)

(def skill-names
  "The skills the part serves, each a SKILL.md under presa/nrepl/skills/ on
  the classpath."
  ["repl-workflow" "repl-long-running" "repl-exploring" "repl-debugging" "repl-testing" "babashka-tooling"])

(defn- skills []
  (mapv #(skill/from-classpath (str "presa/nrepl/skills/" %)) skill-names))

(defn- skill-instructions [skills]
  (str "Guides for working this way are skills of this server. Read the one that fits before a task of "
       "its kind, with the guide tool or as a resource:\n"
       (str/join "\n" (map #(str "- " (:name %) " (" (skill/uri %) "): " (get (:frontmatter %) "description")) skills))))

(defn- guide-tool
  "The skills as a tool, for a host that gives its model no way to read
  resources."
  [skills]
  {:name "guide"
   :title "Read a guide"
   :description (str "Read one of this server's guides to working in the REPL. The same text is served as a "
                     "skill at skill://<name>/SKILL.md.\n"
                     (str/join "\n" (map #(str "- " (:name %) ": " (get (:frontmatter %) "description")) skills)))
   :input-schema {"type" "object"
                  "properties" {"name" {"type" "string" "enum" (mapv :name skills)}}
                  "required" ["name"]}
   :annotations {"readOnlyHint" true}
   :handler (fn [{:strs [name]} _]
              (or (some #(when (= name (:name %)) (get (:files %) "SKILL.md")) skills)
                  (server/tool-error (str "No guide " name))))})

(defn part
  "A part (see presa.part) of tools over one nREPL server: eval, load_file,
  await, interrupt, lookup and guide. It connects on the first call and again
  whenever the connection is gone, cloning one session that the calls
  share. Options:

  - :port  the nREPL server's port; without it, the port in :dir's
           .nrepl-port file, read at each connect
  - :host  default 127.0.0.1
  - :dir   the project directory, for .nrepl-port and relative load_file
           paths (default the working directory)
  - :timeout-ms  how long a call waits for its evaluation when it names no
           timeout_ms (default 30000). An evaluation still running then
           keeps running
  - :interrupt-grace-ms  how long an interrupted evaluation has to stop
           before its session is abandoned for a new one (default 3000)
  - :structured?  true to add structuredContent to results: for eval and
           load_file a map of status, values, out, err, ns, ex and note, for
           lookup the server's info map. Default false: the text is written
           for a model, and hosts that get both show it the JSON
  - :max-output-chars  the most characters kept of stdout, of stderr and of
           each value; the middle of longer output is dropped (default 4000)
  - :max-output-lines  the most lines kept of stdout and of stderr, half
           from the start and half from the end (default 100)
  - :skills?  false to leave out the guides: the skills (see `skill-names`,
           served as skill://<name>/SKILL.md), the guide tool that reads
           them for a host without resources, and the lines of the
           instructions that list them

  :close! interrupts a running evaluation and closes the session and the
  connection."
  ([] (part {}))
  ([opts]
   (let [bridge {:opts (merge {:timeout-ms 30000 :interrupt-grace-ms 3000 :max-output-chars 4000 :max-output-lines 100} opts)
                 :state (atom {})
                 :current (atom nil)
                 :finished (atom nil)
                 :used? (atom false)
                 ;; the session's namespace as the last evaluation left it
                 :last-ns (atom "user")
                 :lock (Object.)}
         skills (when (:skills? opts true) (skills))]
     {:tools (cond-> [(eval-tool bridge) (load-file-tool bridge) (await-tool bridge) (interrupt-tool bridge)
                      (lookup-tool bridge)]
               skills (conj (guide-tool skills)))
      :skills (vec skills)
      :instructions (cond-> instructions skills (str "\n\n" (skill-instructions skills)))
      :close! (fn []
                (let [{:keys [conn session]} @(:state bridge)]
                  (when (and conn (nrepl/open? conn))
                    (when-let [slot @(:current bridge)]
                      (when-not (over? slot) (stop! bridge slot "Closed.")))
                    (when session
                      (try (nrepl/request conn {"op" "close" "session" session} 2000)
                           (catch Exception _ nil)))
                    (nrepl/close conn))
                  :closed))})))

(defn -main
  "Serve the part over stdio: [--port n] [--host h] [--dir d]."
  [& args]
  (let [{:strs [--port --host --dir]} (apply hash-map args)]
    (part/serve! {:name "presa-nrepl"
                  :logging true
                  :parts [(part (cond-> {}
                                  --port (assoc :port (parse-long --port))
                                  --host (assoc :host --host)
                                  --dir (assoc :dir --dir)))]})
    (System/exit 0)))
