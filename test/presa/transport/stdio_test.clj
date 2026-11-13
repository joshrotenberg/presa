(ns presa.transport.stdio-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.stdio :as stdio]))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}})

(defn- run
  "Serve srv over the given input lines; the parsed output lines and the log."
  [srv lines]
  (let [out (java.io.StringWriter.)
        logged (atom [])]
    (stdio/serve! srv {:in (java.io.StringReader. (str/join "\n" lines))
                       :out out
                       :log (fn [& parts] (swap! logged conj (apply str parts)))})
    {:responses (mapv rpc/parse-line (str/split-lines (str out)))
     :log @logged}))

(defn- line [m] (str/trim-newline (rpc/write-line m)))

(deftest a-codex-style-session
  (let [srv (server/server {:name "t" :version "1" :versions protocol/versions
                            :tools [{:name "echo" :handler (fn [{:strs [text]} _] text)}]})
        {:keys [responses]} (run srv [(line (rpc/request 0 "initialize" {"protocolVersion" "2025-06-18"
                                                                         "capabilities" {}
                                                                         "clientInfo" {"name" "c" "version" "0"}}))
                                      (line (rpc/notification "notifications/initialized"))
                                      (line (rpc/request 1 "tools/list" {"_meta" {"progressToken" 0}}))
                                      (line (rpc/request 2 "tools/call" {"name" "echo" "arguments" {"text" "hi"}}))])
        by-id (into {} (map (juxt #(get % "id") identity)) responses)]
    (is (= 3 (count responses)))
    (is (= "2025-06-18" (get-in by-id [0 "result" "protocolVersion"])))
    (is (= ["echo"] (map #(get % "name") (get-in by-id [1 "result" "tools"]))))
    (is (= "hi" (get-in by-id [2 "result" "content" 0 "text"])))))

(deftest framing-errors
  (let [srv (server/server {:name "t" :version "1"})
        {:keys [responses log]} (run srv ["not json" "" "   " (line (rpc/request 1 "server/discover" {"_meta" meta-2026}))])]
    (testing "a line that is not JSON gets a parse error, and blank lines are skipped"
      (let [[parse-error discovered] (sort-by #(some? (get % "id")) responses)]
        (is (= {"jsonrpc" "2.0" "id" nil "error" {"code" -32700 "message" "Parse error"}} parse-error))
        (is (= ["2026-07-28"] (get-in discovered ["result" "supportedVersions"]))))
      (is (= 1 (count log))))))

(def failing-srv
  (server/server
   {:name "t" :version "1"
    :tools [{:name "asserts" :handler (fn [_ _] (assert false "nope"))}
            {:name "unwritable" :handler (fn [_ _] {"content" [] "structuredContent" {"at" (Object.)}})}]
    :resources [{:uri "x:/error" :name "error" :handler (fn [_] (assert false "deep"))}]}))

(deftest every-request-is-answered
  (let [{:keys [responses log]} (run failing-srv
                                     [(line (rpc/request 1 "tools/call" {"name" "asserts" "_meta" meta-2026}))
                                      (line (rpc/request 2 "tools/call" {"name" "unwritable" "_meta" meta-2026}))
                                      (line (rpc/request 3 "resources/read" {"uri" "x:/error" "_meta" meta-2026}))])
        by-id (into {} (map (juxt #(get % "id") identity)) responses)]
    (testing "a tool handler's Error is an isError result"
      (is (true? (get-in by-id [1 "result" "isError"])))
      (is (re-find #"nope" (get-in by-id [1 "result" "content" 0 "text"]))))
    (testing "a result that cannot be written is an internal error with the request id"
      (is (= -32603 (get-in by-id [2 "error" "code"])))
      (is (some #(re-find #"failed to send a response" %) log)))
    (testing "another handler's Error is an internal error"
      (is (= -32603 (get-in by-id [3 "error" "code"])))
      (is (= "Internal error" (get-in by-id [3 "error" "message"])) "without the exception's message"))))

(deftest requests-in-flight-are-cancelled-after-the-drain-timeout
  (let [interrupted (promise)
        cancelled (promise)
        srv (server/server {:name "t" :version "1"
                            :tools [{:name "long"
                                     :handler (fn [_ ctx]
                                                (try (Thread/sleep 30000)
                                                     (catch InterruptedException _
                                                       (deliver cancelled ((:cancelled? ctx)))
                                                       (deliver interrupted true)))
                                                "late")}]})
        t0 (System/currentTimeMillis)]
    (stdio/serve! srv {:in (java.io.StringReader. (line (rpc/request 1 "tools/call" {"name" "long" "_meta" meta-2026})))
                       :out (java.io.StringWriter.) :log (fn [& _]) :drain-timeout-ms 200})
    (is (< (- (System/currentTimeMillis) t0) 5000) "serve! returned without waiting out the call")
    (is (true? (deref interrupted 2000 false)) "the handler's thread was interrupted")
    (is (true? (deref cancelled 2000 false)) "(:cancelled? context) was true")))

(deftest tool-calls-run-concurrently
  (let [released (promise)
        srv (server/server {:name "t" :version "1"
                            :tools [{:name "wait"
                                     :handler (fn [_ _] (deref released 5000 "timed out"))}
                                    {:name "release"
                                     :handler (fn [_ _] (deliver released "released") "ok")}]})
        call (fn [id name] (line (rpc/request id "tools/call" {"name" name "_meta" meta-2026})))
        {:keys [responses]} (run srv [(call 1 "wait") (call 2 "release")])]
    (testing "the second call runs while the first is still waiting"
      (let [by-id (into {} (map (juxt #(get % "id") identity)) responses)]
        (is (= "released" (get-in by-id [1 "result" "content" 0 "text"])))
        (is (= "ok" (get-in by-id [2 "result" "content" 0 "text"])))))))

(deftest handler-output-stays-off-the-protocol-stream
  (let [srv (server/server {:name "t" :version "1"
                            :tools [{:name "chatty" :handler (fn [_ _] (println "noise") "quiet")}]})
        err (java.io.StringWriter.)
        {:keys [responses]} (binding [*err* err]
                              (run srv [(line (rpc/request 1 "tools/call" {"name" "chatty" "_meta" meta-2026}))]))]
    (is (= 1 (count responses)))
    (is (= "quiet" (get-in (first responses) ["result" "content" 0 "text"])))
    (is (= "noise\n" (str err)))))

(deftest progress-and-cancellation
  (testing "progress notifications go out while the request runs"
    (let [srv (server/server {:name "t" :version "1"
                              :tools [{:name "steps"
                                       :handler (fn [_ ctx]
                                                  (server/progress! ctx 1 {:total 2})
                                                  (server/progress! ctx 2 {:total 2 :message "done"})
                                                  "ok")}]})
          {:keys [responses]} (run srv [(line (rpc/request 1 "tools/call" {"name" "steps" "_meta" (assoc meta-2026 "progressToken" "p")}))])]
      (is (= [{"jsonrpc" "2.0" "method" "notifications/progress" "params" {"progressToken" "p" "progress" 1 "total" 2}}
              {"jsonrpc" "2.0" "method" "notifications/progress" "params" {"progressToken" "p" "progress" 2 "total" 2 "message" "done"}}]
             (take 2 responses)))
      (is (= "ok" (get-in (nth responses 2) ["result" "content" 0 "text"])))))
  (testing "no progressToken, no notifications"
    (let [srv (server/server {:name "t" :version "1"
                              :tools [{:name "steps" :handler (fn [_ ctx] (server/progress! ctx 1) "ok")}]})
          {:keys [responses]} (run srv [(line (rpc/request 1 "tools/call" {"name" "steps" "_meta" meta-2026}))])]
      (is (= 1 (count responses)))))
  (testing "a cancelled request is interrupted and gets no response"
    (let [started (promise)
          interrupted (promise)
          srv (server/server {:name "t" :version "1"
                              :tools [{:name "block"
                                       :handler (fn [_ _]
                                                  (deliver started true)
                                                  (try (Thread/sleep 5000) "finished"
                                                       (catch InterruptedException _
                                                         (deliver interrupted true)
                                                         "interrupted")))}]})
          pipe-in (java.io.PipedInputStream.)
          pipe-out (java.io.PipedOutputStream. pipe-in)
          in (java.io.OutputStreamWriter. pipe-out "UTF-8")
          reader (java.io.InputStreamReader. pipe-in "UTF-8")
          out (java.io.StringWriter.)
          done (future (stdio/serve! srv {:in reader :out out :log (fn [& _])}) :done)]
      (.write in (rpc/write-line (rpc/request 7 "tools/call" {"name" "block" "_meta" meta-2026})))
      (.flush in)
      (is (deref started 2000 false))
      (.write in (rpc/write-line (rpc/notification "notifications/cancelled" {"requestId" 7 "reason" "user"})))
      (.close in)
      (is (deref interrupted 2000 false))
      (is (= :done (deref done 2000 :timeout)))
      (is (= "" (str out))))))

(deftest closing-ends-a-stubborn-server-and-its-children
  (let [pids (java.io.File/createTempFile "presa-stdio" ".pids")
        ;; sh ignores SIGTERM, and so does the sleep it starts
        t ((stdio/command ["sh" "-c" (str "trap '' TERM; sleep 60 & echo $$ $! > " (.getPath pids) "; wait")]
                          {:close-timeout-ms 200 :stderr :discard})
           {:on-message (fn [_]) :on-close (fn [])})
        alive? (fn [pid] (.orElse (.map (java.lang.ProcessHandle/of pid)
                                        (reify java.util.function.Function (apply [_ h] (.isAlive ^java.lang.ProcessHandle h))))
                                  false))]
    (try
      (let [ids (loop [n 0] (let [s (str/trim (slurp pids))]
                              (if (or (seq s) (> n 50)) s (do (Thread/sleep 50) (recur (inc n))))))
            [sh-pid sleep-pid] (map parse-long (str/split ids #"\s+"))]
        (is (and (alive? sh-pid) (alive? sleep-pid)))
        ((:close! t))
        (is (loop [n 0] (cond (not (or (alive? sh-pid) (alive? sleep-pid))) true
                              (> n 40) false
                              :else (do (Thread/sleep 50) (recur (inc n)))))
            "the server and its child are gone"))
      (finally (.delete pids)))))

(deftest internal-errors-go-to-the-log
  (let [srv (server/server {:name "t" :version "1"
                            :resources [{:uri "x:/db" :name "db"
                                         :handler (fn [_] (throw (ex-info "SELECT * FROM secrets failed" {})))}]})
        {:keys [responses log]} (run srv [(line (rpc/request 1 "resources/read" {"uri" "x:/db" "_meta" meta-2026}))])]
    (is (= "Internal error" (get-in (first responses) ["error" "message"])) "the client gets no details")
    (is (some #(and (str/includes? % "resources/read") (str/includes? % "SELECT * FROM secrets failed")) log)
        "the log has them, with the stack trace")))
