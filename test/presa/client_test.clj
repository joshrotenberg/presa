(ns presa.client-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.content :as content]
            [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.stdio :as stdio]))

(def tools
  [{:name "echo" :handler (fn [{:strs [text]} _] text)}
   {:name "sum" :handler (fn [{:strs [numbers]} _] {"sum" (reduce + numbers)})}
   {:name "slow" :handler (fn [_ ctx]
                            (server/progress! ctx 1 {:total 2})
                            (server/log! ctx "info" "halfway")
                            (server/progress! ctx 2 {:total 2})
                            "slow done")}
   {:name "block" :handler (fn [_ _] (Thread/sleep 5000) "late")}
   {:name "fail" :handler (fn [_ _] (throw (ex-info "nope" {})))}
   {:name "version" :handler (fn [_ ctx] (:version ctx))}])

(defn- make-server [versions]
  (server/server
   {:name "t" :version "1" :versions versions :logging true :page-size 2 :tools tools
    :instructions "be nice"
    :resources [{:uri "x:/a" :name "a" :handler (fn [_] "A")}]
    :resource-templates [{:uri-template "x:/t/{id}" :name "t" :handler (fn [{:strs [id]} _] (str "T" id))}]
    :prompts [{:name "p" :arguments [{:name "who" :required true}]
               :handler (fn [{:strs [who]} _] (str "hi " who))
               :complete {"who" (fn [v _ _] (filter #(.startsWith ^String % v) ["ada" "alan" "bob"]))}}]}))

(defn- exercise [c]
  (testing "tools, following pagination"
    (is (= ["echo" "sum" "slow" "block" "fail" "version"] (map #(get % "name") (client/list-tools c))))
    (is (= "hi" (client/text (client/call-tool c "echo" {"text" "hi"}))))
    (is (= {"sum" 3} (get (client/call-tool c "sum" {"numbers" [1 2]}) "structuredContent")))
    (is (true? (get (client/call-tool c "fail") "isError")))
    (is (= "hi" (client/text ((get (client/tool-fns c) "echo") {"text" "hi"})))))
  (testing "resources and prompts"
    (is (= ["x:/a"] (map #(get % "uri") (client/list-resources c))))
    (is (= ["x:/t/{id}"] (map #(get % "uriTemplate") (client/list-resource-templates c))))
    (is (= [{"uri" "x:/a" "text" "A"}] (client/read-resource c "x:/a")))
    (is (= "T7" (get-in (client/read-resource c "x:/t/7") [0 "text"])))
    (is (= ["p"] (map #(get % "name") (client/list-prompts c))))
    (is (= "hi ada" (get-in (client/get-prompt c "p" {"who" "ada"}) ["messages" 0 "content" "text"])))
    (is (= ["ada" "alan"] (get (client/complete c {"type" "ref/prompt" "name" "p"} "who" "a") "values"))))
  (testing "a protocol error throws"
    (let [e (try (client/read-resource c "x:/missing") (catch Exception e e))]
      (is (= {:kind :protocol :code -32602 :message "Resource not found" :data {"uri" "x:/missing"}}
             (ex-data e)))))
  (testing "ping"
    (is (map? (client/ping c)))))

(deftest stateless-in-process
  (let [logs (atom [])
        c (client/connect (client/in-process (make-server [protocol/latest-version]))
                          {:on-log #(swap! logs conj %) :log-level "info"})]
    (try
      (is (= {:version "2026-07-28" :server-info {"name" "t" "version" "1"} :instructions "be nice"}
             (select-keys (client/info c) [:version :server-info :instructions])))
      (is (= {"tools" {} "resources" {} "prompts" {} "completions" {} "logging" {}}
             (:server-capabilities (client/info c))))
      (exercise c)
      (is (= "2026-07-28" (client/text (client/call-tool c "version"))))
      (testing "progress and log messages for one request"
        (let [progress (atom [])]
          (reset! logs [])
          (is (= "slow done" (client/text (client/call-tool c "slow" {} {:on-progress #(swap! progress conj %)}))))
          (is (= [1 2] (map #(get % "progress") @progress)))
          (is (= [{"level" "info" "data" "halfway"}] @logs))
          (testing "and none below the requested level"
            (reset! logs [])
            (client/call-tool c "slow" {} {:log-level "error"})
            (is (= [] @logs)))))
      (testing "a timeout cancels the request"
        (let [e (try (client/call-tool c "block" {} {:timeout-ms 100}) (catch Exception e e))]
          (is (= :timeout (:kind (ex-data e))))))
      (testing "set-log-level is for initialize-era connections"
        (is (= :invalid-input (:kind (ex-data (try (client/set-log-level c "debug") (catch Exception e e)))))))
      (finally (client/close c)))))

(deftest initialize-era-fallback
  (testing "a server that serves only initialize-era revisions"
    (let [c (client/connect (client/in-process (make-server protocol/legacy-versions)))]
      (try
        (is (= "2025-11-25" (:version (client/info c))))
        (exercise c)
        (is (= "2025-11-25" (client/text (client/call-tool c "version"))))
        (is (= {} (client/set-log-level c "debug")))
        (finally (client/close c)))))
  (testing "a client that allows only 2025-06-18"
    (let [c (client/connect (client/in-process (make-server protocol/versions)) {:versions ["2025-06-18"]})]
      (try
        (is (= "2025-06-18" (:version (client/info c))))
        (finally (client/close c)))))
  (testing "no revision in common"
    (let [e (try (client/connect (client/in-process (make-server [protocol/latest-version]))
                                 {:versions protocol/legacy-versions})
                 (catch Exception e e))]
      (is (= :protocol (:kind (ex-data e))))
      (is (= -32600 (:code (ex-data e)))))
    (let [e (try (client/connect (client/in-process (make-server protocol/legacy-versions))
                                 {:versions [protocol/latest-version]})
                 (catch Exception e e))]
      (is (= :unsupported-version (:kind (ex-data e)))))))

(deftest closed-connections
  (let [c (client/connect (client/in-process (make-server [protocol/latest-version])))]
    (client/close c)
    (is (= :closed (:kind (ex-data (try (client/ping c) (catch Exception e e))))))))

(deftest over-stdio
  (let [root (.getCanonicalPath (io/file "."))
        c (client/connect (stdio/command ["bb" "--config" (str root "/bb.edn") "-m" "presa.example.hello"]))]
    (try
      (is (= "2026-07-28" (:version (client/info c))))
      (is (= #{"echo" "add"} (set (keys (client/tool-fns c)))))
      (is (= {"sum" 6} (get (client/call-tool c "add" {"numbers" [1 2 3]}) "structuredContent")))
      (finally (client/close c))))
  (testing "an initialize-era client against the same server"
    (let [root (.getCanonicalPath (io/file "."))
          c (client/connect (stdio/command ["bb" "--config" (str root "/bb.edn") "-m" "presa.example.hello"])
                            {:versions ["2025-06-18"]})]
      (try
        (is (= "2025-06-18" (:version (client/info c))))
        (is (= "hi" (client/text (client/call-tool c "echo" {"text" "hi"}))))
        (finally (client/close c)))))
  (testing "a server process that exits"
    (let [e (try (client/connect (stdio/command ["bb" "-e" "(System/exit 0)"]) {:probe-timeout-ms 5000})
                 (catch Exception e e))]
      (is (= :closed (:kind (ex-data e)))))))
