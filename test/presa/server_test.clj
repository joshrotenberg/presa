(ns presa.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.server :as server]))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientInfo" {"name" "test" "version" "0"}
   "io.modelcontextprotocol/clientCapabilities" {}})

(def tools
  [{:name "echo"
    :description "Echo text"
    :input-schema {"type" "object" "properties" {"text" {"type" "string"}}}
    :handler (fn [{:strs [text]} _] text)}
   {:name "sum"
    :handler (fn [{:strs [numbers]} _] {"sum" (reduce + numbers)})}
   {:name "boom"
    :handler (fn [_ _] (throw (ex-info "it broke" {})))}
   {:name "context"
    :handler (fn [_ context] {"version" (:version context)
                              "client" (get-in context [:client-info "name"])
                              "id" (:request-id context)})}
   {:name "nothing"
    :handler (fn [_ _] nil)}
   {:name "refuse"
    :handler (fn [_ _] (server/tool-error "no"))}])

(def stateless (server/server {:name "t" :version "1" :tools tools}))
(def both (server/server {:name "t" :version "1" :tools tools :versions protocol/versions}))

(defn- req
  ([id method] (req id method {}))
  ([id method params] (rpc/request id method (assoc params "_meta" meta-2026))))

(defn- respond [srv message]
  (second (server/handle srv {} message)))

(deftest construction
  (is (= ["2026-07-28"] (:versions stateless)))
  (is (= protocol/versions (:versions both)))
  (doseq [opts [{:version "1"}
                {:name "t" :version "1" :versions ["1999-01-01"]}
                {:name "t" :version "1" :versions []}
                {:name "t" :version "1" :tools [{:name "x"}]}
                {:name "t" :version "1" :tools [{:handler identity}]}
                {:name "t" :version "1" :tools [{:name "x" :handler identity} {:name "x" :handler identity}]}]]
    (is (= :invalid-input (:kind (ex-data (try (server/server opts) (catch Exception e e)))))
        (pr-str opts))))

(deftest stateless-requests
  (testing "server/discover"
    (let [result (get (respond stateless (req "d" "server/discover")) "result")]
      (is (= "complete" (get result "resultType")))
      (is (= ["2026-07-28"] (get result "supportedVersions")))
      (is (= {"tools" {}} (get result "capabilities")))
      (is (= {"name" "t" "version" "1"} (get-in result ["_meta" "io.modelcontextprotocol/serverInfo"])))))
  (testing "tools/list keeps the tools' order and defaults the input schema"
    (let [result (get (respond stateless (req 1 "tools/list")) "result")]
      (is (= "complete" (get result "resultType")))
      (is (= ["echo" "sum" "boom" "context" "nothing" "refuse"] (map #(get % "name") (get result "tools"))))
      (is (= {"name" "echo"
              "description" "Echo text"
              "inputSchema" {"type" "object" "properties" {"text" {"type" "string"}}}}
             (first (get result "tools"))))
      (is (= {"type" "object"} (get (second (get result "tools")) "inputSchema")))))
  (testing "tools/call"
    (let [call (fn [name args] (get (respond stateless (req 2 "tools/call" {"name" name "arguments" args})) "result"))]
      (is (= [{"type" "text" "text" "hi"}] (get (call "echo" {"text" "hi"}) "content")))
      (is (false? (get (call "echo" {"text" "hi"}) "isError")))
      (is (= "complete" (get (call "echo" {"text" "hi"}) "resultType")))
      (is (= {"sum" 6} (get (call "sum" {"numbers" [1 2 3]}) "structuredContent")))
      (is (= [{"type" "text" "text" "{\"sum\":6}"}] (get (call "sum" {"numbers" [1 2 3]}) "content")))
      (is (= {"content" [{"type" "text" "text" "it broke"}] "isError" true}
             (select-keys (call "boom" {}) ["content" "isError"])))
      (is (= {"content" [{"type" "text" "text" "no"}] "isError" true}
             (select-keys (call "refuse" {}) ["content" "isError"])))
      (is (= [] (get (call "nothing" {}) "content")))
      (is (= {"version" "2026-07-28" "client" "test" "id" 2}
             (get (call "context" {}) "structuredContent")))))
  (testing "2026-07-28 removed ping, initialize and logging/setLevel"
    (doseq [method ["ping" "initialize" "logging/setLevel"]]
      (is (= -32601 (get-in (respond stateless (req 3 method)) ["error" "code"])) method))))

(deftest stateless-errors
  (let [code (fn [response] (get-in response ["error" "code"]))]
    (testing "unknown tool and bad arguments are invalid params"
      (is (= -32602 (code (respond stateless (req 1 "tools/call" {"name" "nope"})))))
      (is (= -32602 (code (respond stateless (req 1 "tools/call" {})))))
      (is (= -32602 (code (respond stateless (req 1 "tools/call" {"name" "echo" "arguments" [1]}))))))
    (testing "unknown method"
      (is (= {"jsonrpc" "2.0" "id" 1 "error" {"code" -32601 "message" "Method not found: resources/list"}}
             (respond stateless (req 1 "resources/list")))))
    (testing "a request without the version in _meta"
      (is (= -32602 (code (respond stateless (rpc/request 1 "tools/list"))))))
    (testing "an unsupported version"
      (let [response (respond stateless (rpc/request 1 "tools/list" {"_meta" (assoc meta-2026 "io.modelcontextprotocol/protocolVersion" "2025-06-18")}))]
        (is (= -32022 (code response)))
        (is (= {"requested" "2025-06-18" "supported" ["2026-07-28"]} (get-in response ["error" "data"])))))
    (testing "malformed requests"
      (let [err (fn [message] (get (respond stateless message) "error"))]
        (is (= {"code" -32602 "message" "Request params must be an object"}
               (err {"jsonrpc" "2.0" "id" 1 "method" "tools/list" "params" [1]})))
        (is (= {"code" -32602 "message" "_meta must be an object"}
               (err (rpc/request 1 "tools/list" {"_meta" "x"}))))
        (is (= {"code" -32602 "message" "_meta contains an invalid key or non-JSON value"}
               (err (rpc/request 1 "tools/list" {"_meta" (assoc meta-2026 "bad key!" 1)}))))
        (is (= -32602 (get (err (rpc/request 1 "tools/list" {"_meta" (assoc meta-2026 "progressToken" 1.5)})) "code")))
        (is (= {"code" -32602 "message" "Invalid MCP request log level"}
               (err (rpc/request 1 "tools/list" {"_meta" (assoc meta-2026 "io.modelcontextprotocol/logLevel" "loud")}))))
        (is (= {"code" -32602 "message" "Client info must include string name and version"}
               (err (rpc/request 1 "tools/list" {"_meta" (assoc meta-2026 "io.modelcontextprotocol/clientInfo" {"name" "x"})}))))
        (is (= {"code" -32602 "message" "Client capabilities must be an object"}
               (err (rpc/request 1 "tools/list" {"_meta" (assoc meta-2026 "io.modelcontextprotocol/clientCapabilities" [])}))))
        (is (= -32600 (get (err (rpc/request (apply str (repeat 300 "x")) "tools/list" {"_meta" meta-2026})) "code")))
        (is (= nil (get (respond stateless (rpc/request 1 "tools/list" {"_meta" (assoc meta-2026 "claudecode/toolUseId" "t" "progressToken" "p")})) "error")))))
    (testing "missing client capabilities"
      (is (= -32602 (code (respond stateless (rpc/request 1 "tools/list" {"_meta" (dissoc meta-2026 "io.modelcontextprotocol/clientCapabilities")}))))))
    (testing "initialize is not served by default"
      (let [response (respond stateless (rpc/request 0 "initialize" {"protocolVersion" "2025-06-18" "capabilities" {} "clientInfo" {"name" "c" "version" "0"}}))]
        (is (= -32600 (code response)))
        (is (= {"supported" ["2026-07-28"]} (get-in response ["error" "data"])))))))

(defn- init [version]
  (rpc/request 0 "initialize" {"protocolVersion" version
                               "capabilities" {"elicitation" {}}
                               "clientInfo" {"name" "codex" "version" "0"}}))

(deftest initialize-era
  (testing "initialize answers the requested version when served"
    (let [[session response] (server/handle both {} (init "2025-06-18"))]
      (is (= {"protocolVersion" "2025-06-18"
              "capabilities" {"tools" {}}
              "serverInfo" {"name" "t" "version" "1"}}
             (get response "result")))
      (is (= "2025-06-18" (:version session)))
      (testing "and the session serves later requests without _meta"
        (let [[session' none] (server/handle both session (rpc/notification "notifications/initialized"))]
          (is (nil? none))
          (is (= session session'))
          (let [result (get (second (server/handle both session (rpc/request 1 "tools/list" {"_meta" {"progressToken" 0}}))) "result")]
            (is (= 6 (count (get result "tools"))))
            (is (not (contains? result "resultType"))))
          (let [result (get (second (server/handle both session (rpc/request 2 "tools/call" {"name" "context"}))) "result")]
            (is (= {"version" "2025-06-18" "client" "codex" "id" 2} (get result "structuredContent")))
            (is (not (contains? result "resultType"))))
          (is (= -32601 (get-in (second (server/handle both session (rpc/request 3 "server/discover"))) ["error" "code"])))))))
  (testing "an unknown requested version gets the newest initialize-era one"
    (is (= "2025-11-25" (get-in (respond both (init "2024-11-05")) ["result" "protocolVersion"]))))
  (testing "a malformed initialize"
    (is (= -32602 (get-in (respond both (rpc/request 0 "initialize" {"protocolVersion" "2025-06-18"})) ["error" "code"]))))
  (testing "a request before initialize, without _meta"
    (is (= -32602 (get-in (respond both (rpc/request 1 "tools/list")) ["error" "code"]))))
  (testing "ping before initialize"
    (is (= {} (get (respond both (rpc/request 1 "ping")) "result"))))
  (testing "the same server still serves stateless requests"
    (is (= protocol/versions (get-in (respond both (req "d" "server/discover")) ["result" "supportedVersions"])))))

(deftest messages-without-answers
  (is (nil? (respond stateless (rpc/notification "notifications/cancelled" {"requestId" 1}))))
  (is (nil? (respond stateless (rpc/result 1 {}))))
  (is (nil? (respond stateless (rpc/error 1 :internal-error "x")))))

(deftest invalid-messages
  (is (= {"jsonrpc" "2.0" "id" 7 "error" {"code" -32600 "message" "Invalid JSON-RPC 2.0 message"}}
         (respond stateless {"id" 7 "method" "tools/list"})))
  (is (= nil (get (respond stateless [1 2]) "id")))
  (is (= -32600 (get-in (respond stateless "x") ["error" "code"]))))

(deftest prepare-defers-the-work
  (let [ran (atom false)
        srv (server/server {:name "t" :version "1"
                            :tools [{:name "slow" :handler (fn [_ _] (reset! ran true) "done")}]})
        {:keys [respond]} (server/prepare srv {} (req 1 "tools/call" {"name" "slow"}))]
    (is (false? @ran))
    (is (= "done" (get-in (respond) ["result" "content" 0 "text"])))
    (is (true? @ran))))

(deftest malformed-notifications-are-ignored
  (let [cancel (fn [params] (:cancel (server/prepare stateless {} (rpc/notification "notifications/cancelled" params))))]
    (is (= 7 (cancel {"requestId" 7})))
    (is (= "a" (cancel {"requestId" "a" "reason" "user" "_meta" {"x/y" 1}})))
    (is (nil? (cancel {"requestId" 7 "reason" 3})))
    (is (nil? (cancel {"requestId" 1.5})))
    (is (nil? (cancel {"requestId" (apply str (repeat 300 "x"))})))
    (is (nil? (cancel {"requestId" 7 "_meta" "x"})))
    (is (nil? (cancel {"requestId" 7 "_meta" {"bad key!" 1}})))
    (is (nil? (:cancel (server/prepare stateless {} {"jsonrpc" "2.0" "method" "notifications/cancelled" "params" [7]}))))
    (testing "and get no answer either way"
      (is (nil? ((:respond (server/prepare stateless {} (rpc/notification "notifications/cancelled" {"requestId" 7 "_meta" "x"})))))))))
(deftest initialize-era-clients-see-only-object-schemas
  (let [srv (server/server {:name "t" :version "1" :versions protocol/versions
                            :tools [{:name "plain" :handler (fn [_ _] "ok")}
                                    {:name "list-out" :output-schema {"type" "array"} :handler (fn [_ _] "[]")}
                                    {:name "union-in" :input-schema {"anyOf" [{"type" "object"} {"type" "string"}]}
                                     :handler (fn [_ _] "ok")}]})
        [session] (server/handle srv {} (rpc/request 0 "initialize" {"protocolVersion" "2025-11-25" "capabilities" {}
                                                                     "clientInfo" {"name" "c" "version" "0"}}))
        legacy (fn [m] (second (server/handle srv session m)))]
    (is (= ["plain"] (map #(get % "name") (get-in (legacy (rpc/request 1 "tools/list")) ["result" "tools"]))))
    (is (= {"code" -32602 "message" "Unknown tool: list-out"}
           (get (legacy (rpc/request 2 "tools/call" {"name" "list-out"})) "error")))
    (testing "2026-07-28 clients see every tool"
      (is (= ["plain" "list-out" "union-in"]
             (map #(get % "name") (get-in (respond srv (req 3 "tools/list")) ["result" "tools"])))))))

(deftest results-with-keyword-keys
  (let [schema {:type :object :properties {:count {:type :integer}} :required [:count]}
        srv (server/server
             {:name "t" :version "1"
              :tools [{:name "count" :output-schema schema :handler (fn [_ _] {:count 3})}
                      {:name "built" :output-schema schema :handler (fn [_ _] (server/structured {:count 4}))}
                      {:name "ready" :handler (fn [_ _] {:content [{:type "text" :text "done"}]})}]})
        call (fn [tool] (get (respond srv (req 1 "tools/call" {"name" tool})) "result"))]
    (testing "the output schema checks the result as it is sent"
      (is (= {"count" 3} (get (call "count") "structuredContent")))
      (is (= {"count" 4} (get (call "built") "structuredContent"))))
    (testing "a keyword :content map is a result already"
      (is (= [{"type" "text" "text" "done"}] (get (call "ready") "content")))
      (is (not (contains? (call "ready") "structuredContent"))))))

(deftest a-malformed-template-uri-is-not-found
  (let [srv (server/server {:name "t" :version "1"
                            :resource-templates [{:uri-template "file:///{name}" :name "file"
                                                  :handler (fn [{:strs [name]} _] (str "file " name))}]})]
    (is (= "file a b" (get-in (respond srv (req 1 "resources/read" {"uri" "file:///a%20b"})) ["result" "contents" 0 "text"])))
    (is (= -32602 (get-in (respond srv (req 2 "resources/read" {"uri" "file:///%zz"})) ["error" "code"])))))

(deftest internal-errors-keep-their-message-to-themselves
  (let [events (atom [])
        opts {:name "t" :version "1" :on-event #(swap! events conj %)
              :resources [{:uri "x:/db" :name "db" :handler (fn [_] (throw (ex-info "SELECT * FROM secrets failed" {})))}]}
        read-db (fn [srv] (get (respond srv (req 1 "resources/read" {"uri" "x:/db"})) "error"))]
    (is (= {"code" -32603 "message" "Internal error"} (read-db (server/server opts))))
    (is (= "SELECT * FROM secrets failed"
           (some #(when (= :request/error (:event %)) (ex-message (:exception %))) @events))
        ":on-event gets the exception")
    (is (= "Internal error: SELECT * FROM secrets failed"
           (get (read-db (server/server (assoc opts :expose-errors? true))) "message"))
        ":expose-errors? keeps it, for development")))
