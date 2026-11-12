(ns presa.bb
  "Parts from a bb.edn: its tasks as tools, and what a project directory
  offers. See presa.part for parts and servers made of them.

  Nothing here needs Babashka: bb.edn is read as EDN, as bb reads it, and
  tasks run as `bb` subprocesses."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [presa.part :as part]
            [presa.server :as server]))

(defn- tool-name
  "A task name as a tool name: characters other than A-Z a-z 0-9 _ become _
  (test:jvm becomes test_jvm). Codex shows hyphens as underscores, so this
  gives both hosts the same name."
  [task]
  (str/replace (str task) #"[^A-Za-z0-9_]" "_"))

(defn- tasks-of
  "The :tasks of the bb.edn at f; bb reads bb.edn as EDN, so task bodies are
  EDN data."
  [^java.io.File f]
  (:tasks (edn/read-string {:default (fn [_ v] v)} (slurp f))))

(defn- run-task
  "Run `bb --config config task args...` in config's directory, with no
  stdin; {:exit :out :err :timed-out?}, each stream cut to max-chars."
  [bb ^java.io.File config task args timeout-ms max-chars]
  (let [out-file (java.io.File/createTempFile "presa-bb" ".out")
        err-file (java.io.File/createTempFile "presa-bb" ".err")
        unit java.util.concurrent.TimeUnit/MILLISECONDS]
    (try
      (let [proc (.start (doto (ProcessBuilder. ^java.util.List (into [bb "--config" (str config) (str task)] args))
                           (.directory (.getParentFile config))
                           (.redirectInput (java.lang.ProcessBuilder$Redirect/from (io/file "/dev/null")))
                           (.redirectOutput out-file)
                           (.redirectError err-file)))
            done? (.waitFor proc timeout-ms unit)
            cap (fn [s] (if (> (count s) max-chars)
                          (str (subs s 0 max-chars) "\n[... " (- (count s) max-chars) " more characters]")
                          s))]
        (when-not done?
          (run! #(.destroy ^java.lang.ProcessHandle %) (iterator-seq (.iterator (.descendants proc))))
          (.destroy proc)
          (when-not (.waitFor proc 2000 unit) (.destroyForcibly proc)))
        {:exit (when done? (.exitValue proc))
         :timed-out? (not done?)
         :out (cap (slurp out-file))
         :err (cap (slurp err-file))})
      (finally (.delete out-file) (.delete err-file)))))

(defn task-tools
  "A part with a tool per bb task that has :mcp/tool true in its task map
  (or each task named in :only). A tool runs `bb --config <bb.edn> <task>
  args...` as a subprocess in the bb.edn's directory, so a task's output
  never reaches an MCP stream, and answers with its exit code and output;
  a nonzero exit or a timeout is an isError result. The task's :doc is the
  tool's description. Options:

  - :bb-edn      path of the bb.edn (default \"bb.edn\")
  - :only        task names (symbols or strings) to expose, ignoring :mcp/tool
  - :bb          the bb executable (default \"bb\")
  - :timeout-ms  per call (default 600000)
  - :max-chars   output kept per stream (default 100000)"
  ([] (task-tools {}))
  ([{:keys [bb-edn only bb timeout-ms max-chars]
     :or {bb-edn "bb.edn" bb "bb" timeout-ms 600000 max-chars 100000}}]
   (let [f (.getCanonicalFile (io/file bb-edn))
         only (some->> only (map str) set)]
     {:tools
      (vec (for [[task spec] (tasks-of f)
                 :when (symbol? task)
                 :when (if only (only (str task)) (and (map? spec) (:mcp/tool spec)))]
             {:name (tool-name task)
              :description (str (or (and (map? spec) (:doc spec)) (str "Run the bb task " task "."))
                                " (Runs `bb " task "` in " (.getParent f) ".)")
              :input-schema {:type :object
                             :properties {:args {:type :array :items {:type :string}
                                                 :description "Command-line arguments for the task."}}}
              ;; the handler reads string keys whatever the server's :keywordize-args?
              :keywordize-args? false
              :handler (fn [{:strs [args]} _]
                         (let [{:keys [exit out err timed-out?]} (run-task bb f task (vec args) timeout-ms max-chars)
                               text (str (if timed-out? (str "timed out after " timeout-ms " ms") (str "exit " exit))
                                         (when (seq out) (str "\n\nstdout:\n" out))
                                         (when (seq err) (str "\n\nstderr:\n" err)))]
                           (if (and (not timed-out?) (zero? exit))
                             (server/text text)
                             (server/tool-error text))))}))})))

(defn file-resources
  "A part with a text resource per existing file in paths (README.md,
  AGENTS.md, docs), at its file: URI, read on each request."
  [paths]
  {:resources
   (vec (for [p paths
              :let [f (.getCanonicalFile (io/file p))]
              :when (.isFile f)]
          {:uri (str (.toURI f)) :name (.getName f) :mime-type "text/plain"
           :handler (fn [_] (slurp f))}))})

(defn project-parts
  "The parts the project in dir offers from its bb.edn: when it has an mcp
  task, that server, run as a subprocess and proxied (its bb, deps and
  config stay its own); otherwise its tasks marked :mcp/tool. No bb.edn, no
  parts. Options: :task (default \"mcp\"), :bb, and task-tools' :timeout-ms
  and :max-chars."
  ([dir] (project-parts dir {}))
  ([dir {:keys [task bb] :or {task "mcp" bb "bb"} :as opts}]
   (let [f (.getCanonicalFile (io/file dir "bb.edn"))]
     (cond
       (not (.isFile f)) []
       (contains? (tasks-of f) (symbol task))
       [(part/proxy [bb "--config" (str f) task] {:dir (str (.getParentFile f))})]
       :else
       [(task-tools (assoc (select-keys opts [:bb :timeout-ms :max-chars]) :bb-edn (str f)))]))))
