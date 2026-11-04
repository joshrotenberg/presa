(ns presa.authoring-test
  "Keyword schemas and keyword tool arguments."
  (:require [clojure.test :refer [deftest is testing]]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.server :as server]))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}})

(def tasks-meta
  (assoc meta-2026 "io.modelcontextprotocol/clientCapabilities"
         {"extensions" {"io.modelcontextprotocol/tasks" {}}}))

(defn- result [srv method params & [meta]]
  (get (second (server/handle srv {} (rpc/request 1 method (assoc params "_meta" (or meta meta-2026)))))
       "result"))

(def keyword-schema
  {:type :object
   :properties {:numbers {:type :array :items {:type :number}}
                :mode {:enum [:fast :exact]}
                :ex/tag {:type :string}}
   :required [:numbers]})

(deftest keyword-schemas
  (let [add {:name "add"
             :input-schema keyword-schema
             :output-schema {:type :object :properties {:sum {:type :number}} :required [:sum]}
             :handler (fn [{:strs [numbers]} _] {"sum" (reduce + numbers)})}
        srv (server/server {:name "t" :version "1" :tools [add] :versions protocol/versions})]
    (testing "are sent as JSON Schema with string keys"
      (is (= {"type" "object"
              "properties" {"numbers" {"type" "array" "items" {"type" "number"}}
                            "mode" {"enum" ["fast" "exact"]}
                            "ex/tag" {"type" "string"}}
              "required" ["numbers"]}
             (get-in (result srv "tools/list" {}) ["tools" 0 "inputSchema"])))
      (is (= {"type" "object" "properties" {"sum" {"type" "number"}} "required" ["sum"]}
             (get-in (result srv "tools/list" {}) ["tools" 0 "outputSchema"]))))
    (testing "and validate as JSON Schema"
      (is (= "{\"sum\":6}" (get-in (result srv "tools/call" {"name" "add" "arguments" {"numbers" [1 2 3]}})
                                  ["content" 0 "text"])))
      (let [bad (result srv "tools/call" {"name" "add" "arguments" {"mode" "slow"}})]
        (is (true? (get bad "isError")))))
    (testing "count as object schemas for initialize-era clients"
      (let [[session] (server/handle srv {} (rpc/request 1 "initialize" {"protocolVersion" "2025-11-25"
                                                                        "capabilities" {}
                                                                        "clientInfo" {"name" "c" "version" "1"}}))
            [_ listed] (server/handle srv session (rpc/request 2 "tools/list" {}))]
        (is (= ["add"] (map #(get % "name") (get-in listed ["result" "tools"]))))))
    (testing "added to a running server too"
      (server/add-tool! srv (assoc add :name "add2"))
      (is (= "object" (get-in (server/tool srv "add2") [:input-schema "type"]))))
    (testing "string-keyed schemas are unchanged"
      (let [s {"type" "object" "properties" {"x" {"type" "string"}}}]
        (is (= s (:input-schema (server/tool (server/server {:name "t" :version "1"
                                                             :tools [{:name "x" :input-schema s :handler (fn [_ _])}]})
                                             "x"))))))))

(def seen (atom nil))

(defn- echo-args [args _] (reset! seen args) "ok")

(deftest keyword-arguments
  (let [args {"name" "Ada" "opts" {"loud" true} "tags" [{"k" "v"}]}]
    (testing "string keys by default"
      (let [srv (server/server {:name "t" :version "1" :tools [{:name "e" :handler echo-args}]})]
        (result srv "tools/call" {"name" "e" "arguments" args})
        (is (= args @seen))))
    (testing "keyword keys, recursively, with the server option"
      (let [srv (server/server {:name "t" :version "1" :keywordize-args? true
                                :tools [{:name "e" :handler echo-args}
                                        {:name "s" :keywordize-args? false :handler echo-args}]})]
        (result srv "tools/call" {"name" "e" "arguments" args})
        (is (= {:name "Ada" :opts {:loud true} :tags [{:k "v"}]} @seen))
        (testing "unless the tool says otherwise"
          (result srv "tools/call" {"name" "s" "arguments" args})
          (is (= args @seen)))))
    (testing "a tool can opt in on its own"
      (let [srv (server/server {:name "t" :version "1"
                                :tools [{:name "e" :keywordize-args? true :handler echo-args}]})]
        (result srv "tools/call" {"name" "e" "arguments" {"first_name" "Ada"}})
        (is (= {:first_name "Ada"} @seen) "names are not case-converted")))
    (testing "validation sees the arguments as sent"
      (let [srv (server/server {:name "t" :version "1" :keywordize-args? true
                                :tools [{:name "e" :input-schema {:type :object :required [:name]}
                                         :handler echo-args}]})]
        (is (true? (get (result srv "tools/call" {"name" "e" "arguments" {}}) "isError")))
        (is (= "ok" (get-in (result srv "tools/call" {"name" "e" "arguments" {"name" "x"}}) ["content" 0 "text"])))))
    (testing "a task handler gets the same arguments"
      (let [srv (server/server {:name "t" :version "1" :keywordize-args? true
                                :tasks {:ttl-ms 60000 :poll-interval-ms 20}
                                :tools [{:name "e" :task :optional :handler echo-args}]})
            id (get (result srv "tools/call" {"name" "e" "arguments" {"name" "Ada"}} tasks-meta) "taskId")]
        (is (string? id))
        (loop [n 0]
          (when (and (< n 200) (not= "completed" (get (result srv "tasks/get" {"taskId" id} tasks-meta) "status")))
            (Thread/sleep 10)
            (recur (inc n))))
        (is (= {:name "Ada"} @seen))))))
