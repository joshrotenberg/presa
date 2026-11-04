(ns presa.input-test
  "Multi round-trip requests: input requests, requestState, and both ends."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.input :as input]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.request-state :as rs]
            [presa.server :as server]))

(deftest capabilities-needed
  (is (nil? (input/missing-capabilities [(input/elicit "x" {"type" "object"})] {"elicitation" {}})))
  (is (nil? (input/missing-capabilities [(input/elicit "x" {"type" "object"})] {"elicitation" {"form" {}}})))
  (is (= {"elicitation" {"form" {}}}
         (input/missing-capabilities [(input/elicit "x" {"type" "object"})] {"elicitation" {"url" {}}})))
  (is (= {"elicitation" {"url" {}}}
         (input/missing-capabilities [(input/elicit-url "x" "https://example.com")] {"elicitation" {}})))
  (is (= {"sampling" {} "roots" {}}
         (input/missing-capabilities {"a" (input/sample [] 10) "b" (input/roots)} {})))
  (is (= {"sampling" {"context" {}}}
         (input/missing-capabilities [(input/sample [] 10 {:include-context "thisServer"})] {"sampling" {}}))))

(def secret (apply str (repeat 32 "k")))

(deftest request-state-tokens
  (let [params {"name" "t" "arguments" {"a" 1 "b" [1 2]}}
        token (rs/sign (rs/secret-bytes secret) "tools/call" params {"step" 1} 1000 5000)
        verify #(rs/verify (rs/secret-bytes secret) %1 %2 %3 %4)
        invalid? (fn [f] (= :invalid-state (:kind (ex-data (try (f) (catch Exception e e))))))]
    (is (str/starts-with? token "presa1."))
    (is (= {"step" 1} (verify "tools/call" params token 5500)))
    (testing "the scope ignores _meta, inputResponses and requestState, and key order"
      (is (= {"step" 1} (verify "tools/call" {"arguments" {"b" [1 2] "a" 1} "name" "t"
                                              "_meta" {"x" 1} "inputResponses" {} "requestState" token}
                                token 5500))))
    (is (invalid? #(verify "tools/call" params (str token "-TAMPERED") 5500)))
    (is (invalid? #(verify "tools/call" params token 7000)) "expired")
    (is (invalid? #(verify "tools/call" (assoc params "name" "other") token 5500)) "another tool")
    (is (invalid? #(verify "prompts/get" params token 5500)) "another method")
    (is (invalid? #(rs/verify (rs/secret-bytes (apply str (repeat 32 "z"))) "tools/call" params token 5500)) "another key")
    (is (invalid? #(verify "tools/call" params "garbage" 5500)))
    (is (= :invalid-input (:kind (ex-data (try (rs/secret-bytes "short") (catch Exception e e))))))))

(defn- greet-handler
  "Asks for a name, then greets; the state carries the round."
  [_ ctx]
  (if-let [{:strs [action content]} (server/input-response ctx "user_name")]
    (if (= "accept" action)
      (str "Hello, " (get content "name") "!")
      (server/tool-error "declined"))
    (server/input-required {"user_name" (input/elicit "Please provide name"
                                                      {"type" "object"
                                                       "properties" {"name" {"type" "string"}}
                                                       "required" ["name"]})}
                           {"round" 1})))

(defn- two-steps [_ ctx]
  (let [state (:state ctx)]
    (cond
      (nil? state)
      (server/input-required {"step1" (input/elicit "name?" {"type" "object"})} {"step" 1})

      (= 1 (get state "step"))
      (server/input-required {"step2" (input/elicit "color?" {"type" "object"})}
                             {"step" 2 "name" (get-in (server/input-response ctx "step1") ["content" "name"])})

      :else
      (str (get state "name") " likes " (get-in (server/input-response ctx "step2") ["content" "color"])))))

(def srv
  (server/server
   {:name "t" :version "1" :versions protocol/versions :state-secret secret
    :tools [{:name "greet" :handler greet-handler}
            {:name "two_steps" :handler two-steps}
            {:name "everything"
             :handler (fn [_ ctx]
                        (if (seq (:input-responses ctx))
                          (str (count (:input-responses ctx)) " answers, roots "
                               (get-in (server/input-response ctx "client_roots") ["roots" 0 "uri"]))
                          (server/input-required {"user_name" (input/elicit "name?" {"type" "object"})
                                                  "greeting" (input/sample [{"role" "user" "content" {"type" "text" "text" "hi"}}] 50)
                                                  "client_roots" (input/roots)})))}]
    :resources [{:uri "x:/needs-input" :name "needs"
                 :handler (fn [ctx]
                            (if-let [r (server/input-response ctx "ctx")]
                              (str "context: " (get-in r ["content" "context"]))
                              (server/input-required {"ctx" (input/elicit "context?" {"type" "object"})})))}]
    :prompts [{:name "p"
               :handler (fn [_ ctx]
                          (if-let [r (server/input-response ctx "ctx")]
                            (str "with " (get-in r ["content" "context"]))
                            (server/input-required {"ctx" (input/elicit "context?" {"type" "object"})})))}]}))

(def all-caps {"elicitation" {} "sampling" {} "roots" {}})

(defn- req [id method params caps]
  (rpc/request id method (assoc params "_meta" {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                                                "io.modelcontextprotocol/clientCapabilities" caps})))

(defn- call [id params & [caps]]
  (second (server/handle srv {} (req id "tools/call" params (or caps all-caps)))))

(deftest stateless-server
  (testing "round 1 is input_required with the requests and a signed state"
    (let [result (get (call 1 {"name" "greet"}) "result")]
      (is (= "input_required" (get result "resultType")))
      (is (= {"method" "elicitation/create"
              "params" {"mode" "form" "message" "Please provide name"
                        "requestedSchema" {"type" "object" "properties" {"name" {"type" "string"}} "required" ["name"]}}}
             (get-in result ["inputRequests" "user_name"])))
      (is (string? (get result "requestState")))
      (is (= {"name" "t" "version" "1"} (get-in result ["_meta" "io.modelcontextprotocol/serverInfo"])))
      (is (not (contains? result "ttlMs")))
      (testing "round 2 completes"
        (let [r2 (get (call 2 {"name" "greet"
                               "inputResponses" {"user_name" {"action" "accept" "content" {"name" "Alice"}}}
                               "requestState" (get result "requestState")})
                      "result")]
          (is (= "complete" (get r2 "resultType")))
          (is (= "Hello, Alice!" (get-in r2 ["content" 0 "text"])))))
      (testing "extra answers are ignored"
        (is (= "Hello, Bo!" (get-in (call 3 {"name" "greet"
                                             "inputResponses" {"user_name" {"action" "accept" "content" {"name" "Bo"}}
                                                               "unasked" {"x" 1}}})
                                    ["result" "content" 0 "text"]))))
      (testing "a tampered state is an error"
        (is (= {"code" -32602 "message" "Invalid or expired request state"}
               (get (call 4 {"name" "greet"
                             "inputResponses" {"user_name" {"action" "accept" "content" {"name" "A"}}}
                             "requestState" (str (get result "requestState") "-TAMPERED")})
                    "error"))))
      (testing "a state from another tool is an error"
        (is (= -32602 (get-in (call 5 {"name" "two_steps" "requestState" (get result "requestState")})
                              ["error" "code"]))))))
  (testing "malformed retries"
    (is (= -32602 (get-in (call 1 {"name" "greet" "inputResponses" {"user_name" 12345}}) ["error" "code"])))
    (is (= -32602 (get-in (call 1 {"name" "greet" "inputResponses" nil}) ["error" "code"])))
    (is (= -32602 (get-in (call 1 {"name" "greet" "requestState" 7}) ["error" "code"])))
    (is (= -32602 (get-in (second (server/handle srv {} (req 1 "tools/list" {"inputResponses" {}} all-caps)))
                          ["error" "code"]))))
  (testing "a missing client capability is -32021 naming it"
    (is (= {"code" -32021 "message" "Missing required client capability"
            "data" {"requiredCapabilities" {"sampling" {} "roots" {}}}}
           (get (call 1 {"name" "everything"} {"elicitation" {}}) "error"))))
  (testing "several inputs in one round"
    (let [r1 (get (call 1 {"name" "everything"}) "result")]
      (is (= #{"user_name" "greeting" "client_roots"} (set (keys (get r1 "inputRequests")))))
      (is (not (contains? r1 "requestState")))
      (is (= "3 answers, roots file:///r"
             (get-in (call 2 {"name" "everything"
                              "inputResponses" {"user_name" {"action" "accept" "content" {}}
                                                "greeting" {"role" "assistant" "content" {"type" "text" "text" "hey"} "model" "m"}
                                                "client_roots" {"roots" [{"uri" "file:///r"}]}}})
                     ["result" "content" 0 "text"])))))
  (testing "three rounds, each with a different state"
    (let [r1 (get (call 1 {"name" "two_steps"}) "result")
          r2 (get (call 2 {"name" "two_steps" "requestState" (get r1 "requestState")
                           "inputResponses" {"step1" {"action" "accept" "content" {"name" "Alice"}}}})
                  "result")
          r3 (get (call 3 {"name" "two_steps" "requestState" (get r2 "requestState")
                           "inputResponses" {"step2" {"action" "accept" "content" {"color" "blue"}}}})
                  "result")]
      (is (= "input_required" (get r2 "resultType")))
      (is (not= (get r1 "requestState") (get r2 "requestState")))
      (is (= "Alice likes blue" (get-in r3 ["content" 0 "text"])))))
  (testing "resources/read and prompts/get can ask too"
    (let [read (fn [params] (get (second (server/handle srv {} (req 1 "resources/read" (merge {"uri" "x:/needs-input"} params) all-caps))) "result"))
          get-prompt (fn [params] (get (second (server/handle srv {} (req 1 "prompts/get" (merge {"name" "p"} params) all-caps))) "result"))
          answer {"inputResponses" {"ctx" {"action" "accept" "content" {"context" "c"}}}}]
      (is (= "input_required" (get (read {}) "resultType")))
      (is (= "context: c" (get-in (read answer) ["contents" 0 "text"])))
      (is (= "input_required" (get (get-prompt {}) "resultType")))
      (is (= "with c" (get-in (get-prompt answer) ["messages" 0 "content" "text"]))))))

(deftest initialize-era-sessions-refuse-retry-params
  (let [[session] (server/handle srv {} (rpc/request 0 "initialize" {"protocolVersion" "2025-11-25" "capabilities" {}
                                                                     "clientInfo" {"name" "c" "version" "0"}}))]
    (is (= -32602 (get-in (second (server/handle srv session (rpc/request 1 "tools/call" {"name" "greet" "inputResponses" {}})))
                          ["error" "code"])))))

(def handlers
  {"elicitation/create" (fn [{:strs [message]}]
                          {"action" "accept" "content" (case message
                                                         "name?" {"name" "Alice"}
                                                         "color?" {"color" "blue"}
                                                         "context?" {"context" "c"}
                                                         {"name" "Ada"})})
   "sampling/createMessage" (fn [_] {"role" "assistant" "content" {"type" "text" "text" "hey"} "model" "m"})
   "roots/list" (fn [_] {"roots" [{"uri" "file:///r"}]})})

(deftest clients-answer-input
  (doseq [versions [[protocol/latest-version] ["2025-11-25"]]]
    (testing (str "over " versions)
      (let [c (client/connect (client/in-process srv) {:versions versions :handlers handlers})]
        (try
          (is (= (first versions) (:version (client/info c))))
          (is (= "Hello, Ada!" (client/text (client/call-tool c "greet"))))
          (is (= "Alice likes blue" (client/text (client/call-tool c "two_steps"))))
          (is (= "3 answers, roots file:///r" (client/text (client/call-tool c "everything"))))
          (is (= "context: c" (get-in (client/read-resource c "x:/needs-input") [0 "text"])))
          (is (= "with c" (get-in (client/get-prompt c "p") ["messages" 0 "content" "text"])))
          (finally (client/close c))))))
  (testing "a client without a handler for what the server asks"
    (let [c (client/connect (client/in-process srv) {:capabilities all-caps})]
      (try
        (is (= :unsupported (:kind (ex-data (try (client/call-tool c "greet") (catch Exception e e))))))
        (finally (client/close c))))))

(deftest request-state-lifetime
  (testing ":state-ttl-ms expires a token, and the retry is -32602"
    (let [ttl 20
          short-srv (server/server {:name "t" :version "1" :state-secret secret :state-ttl-ms ttl
                                    :tools [{:name "greet" :handler greet-handler}]})
          started (System/currentTimeMillis)
          r1 (get (second (server/handle short-srv {} (req 1 "tools/call" {"name" "greet"} all-caps))) "result")
          retry (fn [] (second (server/handle short-srv {} (req 2 "tools/call"
                                                                {"name" "greet"
                                                                 "inputResponses" {"user_name" {"action" "accept" "content" {"name" "A"}}}
                                                                 "requestState" (get r1 "requestState")}
                                                                all-caps)))) ]
      (is (= "input_required" (get r1 "resultType")))
      ;; wait until the clock is past the expiry, polling rather than sleeping a fixed time
      (loop [n 0] (when (and (<= (System/currentTimeMillis) (+ started ttl 5)) (< n 1000)) (Thread/sleep 5) (recur (inc n))))
      (is (= {"code" -32602 "message" "Invalid or expired request state"} (get (retry) "error")))))
  (testing "a token longer than max-token-length is refused"
    (let [key (rs/secret-bytes secret)
          params {"name" "t"}
          small (rs/sign key "tools/call" params {"pad" "x"} 1000 5000)
          big (rs/sign key "tools/call" params {"pad" (apply str (repeat (* 2 rs/max-token-length) "x"))} 1000 5000)
          invalid? (fn [token] (= :invalid-state (:kind (ex-data (try (rs/verify key "tools/call" params token 5500) (catch Exception e e))))))]
      (is (< (count small) rs/max-token-length))
      (is (> (count big) rs/max-token-length))
      (is (= {"pad" "x"} (rs/verify key "tools/call" params small 5500)))
      (is (invalid? big) "a validly signed token that is too long"))))

(deftest client-request-failures
  (testing "an initialize-era client that never answers gives :kind :timeout"
    (let [release (promise)
          outcome (promise)
          srv (server/server
               {:name "t" :version "1" :versions protocol/legacy-versions
                :tools [{:name "ask"
                         :handler (fn [_ ctx]
                                    (let [e (try (server/client-request! ctx "roots/list" {} {:timeout-ms 100})
                                                 :answered
                                                 (catch Exception e e))]
                                      (deliver outcome (if (keyword? e) e (:kind (ex-data e))))
                                      "done"))}]})
          c (client/connect (client/in-process srv)
                            {:capabilities {"roots" {}}
                             :handlers {"roots/list" (fn [_] @release {"roots" []})}})]
      (try
        (client/call-tool c "ask")
        (is (= :timeout (deref outcome 5000 :no-outcome)))
        (finally (deliver release true) (client/close c)))))
  (testing "2026-07-28 throws :unsupported"
    (let [outcome (promise)
          srv (server/server
               {:name "t" :version "1"
                :tools [{:name "ask"
                         :handler (fn [_ ctx]
                                    (let [e (try (server/client-request! ctx "roots/list" {} {:timeout-ms 100})
                                                 :answered
                                                 (catch Exception e e))]
                                      (deliver outcome (if (keyword? e) e (:kind (ex-data e))))
                                      "done"))}]})
          c (client/connect (client/in-process srv) {:capabilities {"roots" {}}})]
      (try
        (client/call-tool c "ask")
        (is (= :unsupported (deref outcome 5000 :no-outcome)))
        (finally (client/close c))))))
