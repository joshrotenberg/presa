(ns presa.part-test
  (:require [presa.server]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.jsonrpc :as rpc]
            [presa.part :as part]))

(defn- tool [name v] {:name name :handler (fn [_ _] v)})

(deftest combining
  (let [closed (atom [])
        a {:tools [(tool "a" "A")] :instructions "Use a." :close! #(do (swap! closed conj :a) :a)}
        b {:tools [(tool "b" {"n" 1})]
           :resources [{:uri "x:/r" :name "r" :handler (fn [_] "R")}]
           :resource-templates [{:uri-template "x:/t/{id}" :name "t" :handler (fn [_ _] "T")}]
           :prompts [{:name "p" :handler (fn [_ _] "P")}]
           :instructions "Use b."}
        broken {:close! #(throw (ex-info "cannot close" {}))}
        p (part/combine [a nil b broken])]
    (is (= ["a" "b"] (map :name (:tools p))))
    (is (= [1 1 1] (map (comp count p) [:resources :resource-templates :prompts])))
    (is (= "Use a.\n\nUse b." (:instructions p)))
    (testing "close! closes each part in order and reports failures in place"
      (is (= [:a nil {:error "cannot close"}] ((:close! p))))
      (is (= [:a] @closed)))
    (testing "an empty combination"
      (is (= {:tools [] :resources [] :resource-templates [] :prompts [] :instructions nil :dropped []}
             (dissoc (part/combine []) :close!))))))

(deftest precedence
  (let [user {:tools [(tool "spawn" "user") (tool "a" "A")]}
        project {:tools [(tool "spawn" "project") (tool "b" "B")]
                 :resources [{:uri "x:/r" :name "r" :handler (fn [_] "")}]}
        p (part/combine [user project {:resources [{:uri "x:/r" :name "again" :handler (fn [_] "")}]}]
                        {:duplicates :first})]
    (is (= ["spawn" "a" "b"] (map :name (:tools p))))
    (is (= [["tool" "spawn"] ["resource" "x:/r"]] (:dropped p)))
    (is (= "user" (part/with-client {:parts [user project] :duplicates :first} #(part/call % "spawn"))))))

(deftest proxying
  (let [p (part/proxy ["bb" "--config" (.getCanonicalPath (io/file "bb.edn")) "-m" "presa.example.hello"]
                      {:stderr (fn [_])})]
    (try
      (is (= #{"echo" "add"} (set (map :name (:tools p)))))
      (testing "calls are forwarded, as part of another server"
        (is (= ["hi" "x"]
               (part/with-client {:parts [(dissoc p :close!) {:tools [(tool "x" "x")]}]}
                 (fn [c] [(part/call c "echo" {"text" "hi"}) (part/call c "x")])))))
      (finally ((:close! p))))))

(defn- backend
  "A command serving a presa server built from the server-map form, over
  stdio, from this repository's sources."
  [server-map]
  ["bb" "--config" (.getCanonicalPath (io/file "bb.edn")) "-e"
   (pr-str (list 'do
                 '(require (quote [presa.server :as s]) (quote [presa.transport.stdio :as stdio]))
                 (list 'stdio/serve! (list 's/server server-map))))])

(deftest proxying-a-backend-without-tools
  (let [p (part/proxy (backend '{:name "b" :version "1" :resources [{:uri "x:/a" :name "a" :handler (fn [_] "A")}]})
                      {:stderr (fn [_])})]
    (try
      (is (= [] (:tools p)))
      (is (= ["x:/a"] (map :uri (:resources p))))
      (finally ((:close! p))))))

(def ^:private slow-backend
  (backend '{:name "b" :version "1"
             :tools [{:name "slow"
                      :handler (fn [_ ctx]
                                 (s/progress! ctx 1 {:total 2})
                                 (Thread/sleep 1500)
                                 (s/progress! ctx 2 {:total 2})
                                 "done")}]}))

(deftest proxied-calls-forward-progress-and-take-a-timeout
  (testing "progress reaches the front's caller"
    (let [p (part/proxy slow-backend {:stderr (fn [_])})
          progress (atom [])]
      (try
        (part/with-client {:parts [(dissoc p :close!)]}
          (fn [c]
            (is (= "done" (client/text (client/call-tool c "slow" {} {:on-progress #(swap! progress conj (get % "progress"))}))))))
        (is (= [1 2] @progress))
        (finally ((:close! p))))))
  (testing ":timeout-ms bounds a forwarded call"
    (let [p (part/proxy slow-backend {:stderr (fn [_]) :timeout-ms 500})]
      (try
        (part/with-client {:parts [(dissoc p :close!)]}
          (fn [c]
            (let [r (client/call-tool c "slow")]
              (is (true? (get r "isError")))
              (is (re-find #"timed out" (client/text r))))))
        (finally ((:close! p)))))))

(deftest duplicates
  (let [a {:tools [(tool "a" "A")] :resources [{:uri "x:/r" :name "r" :handler (fn [_] "")}]}]
    (is (thrown-with-msg? Exception #"Duplicate tool a" (part/combine [a {:tools [(tool "a" "B")]}])))
    (is (thrown-with-msg? Exception #"Duplicate resource x:/r" (part/combine [a (dissoc a :tools)])))
    (testing "prefix renames tools and prompts, not resources"
      (let [p (part/prefix {:tools [(tool "a" "A")] :prompts [{:name "q" :handler (fn [_ _] "")}]
                            :resources [{:uri "x:/s" :name "s" :handler (fn [_] "")}]}
                           "x")]
        (is (= ["x_a"] (map :name (:tools p))))
        (is (= ["x_q"] (map :name (:prompts p))))
        (is (= ["x:/s"] (map :uri (:resources p))))
        (is (= ["a" "x_a"] (map :name (:tools (part/combine [a (dissoc p :resources :prompts)])))))))))

(deftest in-process
  (let [closed (atom false)
        parts [{:tools [(tool "text" "hello") (tool "data" {"n" 1})]
                :instructions "Say hello."
                :close! #(reset! closed true)}]]
    (is (= ["hello" {"n" 1} "Say hello." (.getName (.getCanonicalFile (io/file ".")))]
           (part/with-client {:parts parts}
             (fn [c] [(part/call c "text") (part/call c "data") (:instructions (client/info c))
                      (get-in (client/info c) [:server-info "name"])]))))
    (is @closed "with-client closes the parts")
    (testing "a tool error throws, and the parts are still closed"
      (reset! closed false)
      (is (thrown-with-msg? Exception #"bad: no"
                            (part/with-client {:parts [{:tools [{:name "bad" :handler (fn [_ _] (presa.server/tool-error "no"))}]
                                                        :close! #(reset! closed true)}]}
                              #(part/call % "bad"))))
      (is @closed))
    (testing "server options pass through and override the defaults"
      (part/with-client {:parts parts :name "named" :version "2" :instructions "Mine."}
        (fn [c]
          (is (= {"name" "named" "version" "2"} (:server-info (client/info c))))
          (is (= "Mine." (:instructions (client/info c)))))))))

(deftest serving-over-stdio
  (let [closed (atom false)
        in (java.io.StringReader.
            (str (rpc/write-line (rpc/request 1 "initialize" {"protocolVersion" "2025-06-18" "capabilities" {}
                                                              "clientInfo" {"name" "c" "version" "1"}}))
                 (rpc/write-line (rpc/request 2 "tools/list" {}))))
        out (java.io.StringWriter.)
        result (part/serve! {:parts [{:tools [(tool "t" "x")] :close! #(do (reset! closed true) :closed)}]
                             :in in :out out :log (fn [& _])})]
    (is (= [:closed] result) "serve! returns what the parts' close! returned")
    (is @closed)
    (is (str/includes? (str out) "\"name\":\"t\""))))

(deftest serve-passes-the-line-limit
  (let [out (java.io.StringWriter.)]
    (part/serve! {:parts [{:tools [(tool "t" "x")]}]
                  :in (java.io.StringReader. (str (apply str (repeat 200 "x")) "\n"))
                  :out out :log (fn [& _]) :max-line-bytes 100})
    (is (str/includes? (str out) "100-byte line limit"))))

(deftest a-signalled-server-closes-its-parts
  (let [marker (java.io.File/createTempFile "presa-part" ".closed")
        _ (.delete marker)
        script (pr-str (list 'do
                             '(require (quote [presa.part :as part]))
                             (list 'part/serve! {:parts [{:tools [{:name "t" :handler '(fn [_ _] "x")}]
                                                          :close! (list 'fn [] (list 'spit (.getPath marker) "closed"))}]
                                                 :log '(fn [& _])})))
        proc (.start (ProcessBuilder. ^java.util.List ["bb" "--config" (.getCanonicalPath (io/file "bb.edn")) "-e" script]))]
    (try
      ;; a request answered means the server is up; stdin stays open
      (doto (.getOutputStream proc)
        (.write (.getBytes (rpc/write-line (rpc/request 1 "server/discover"
                                                        {"_meta" {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                                                                  "io.modelcontextprotocol/clientCapabilities" {}}}))
                           "UTF-8"))
        (.flush))
      (is (some? (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream proc))))))
      (.destroy proc)
      (.waitFor proc 10 java.util.concurrent.TimeUnit/SECONDS)
      (is (= "closed" (when (.exists marker) (slurp marker))) "SIGTERM ran the parts' close!")
      (finally (.destroyForcibly proc) (.delete marker)))))
