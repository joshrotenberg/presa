(ns presa.wire-schema-test
  "Every message presa sends, in both directions, validated against the
  pinned 2026-07-28 JSON schema (conformance/schema/2026-07-28, commit
  5f5440bb). The conformance fixture is driven through presa.client over a
  transport that records each message; responses are checked against the
  definition for their request's method. Negative controls check that the
  validator catches broken messages. JVM only: it uses
  com.networknt/json-schema-validator."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.conformance.fixture :as fixture]
            [presa.server :as server])
  (:import (com.networknt.schema InputFormat SchemaLocation SchemaRegistry SpecificationVersion)
           (java.util.function Consumer)))

(def schema-id "https://presa.invalid/mcp/2026-07-28/schema.json")

(def registry
  (delay
    (let [doc (slurp (io/file "conformance/schema/2026-07-28/schema.json"))]
      (SchemaRegistry/withDefaultDialect
       SpecificationVersion/DRAFT_2020_12
       (reify Consumer
         (accept [_ b]
           (.schemas ^com.networknt.schema.SchemaRegistry$Builder b {schema-id doc})))))))

(def definitions (atom {}))

(defn- definition [def-name]
  (or (get @definitions def-name)
      (let [s (.getSchema ^SchemaRegistry @registry (SchemaLocation/of (str schema-id "#/$defs/" def-name)))]
        (swap! definitions assoc def-name s)
        s)))

(defn- problems
  "Validation messages of value against the schema's $defs.def-name."
  [def-name value]
  (mapv #(str (.getInstanceLocation ^com.networknt.schema.Error %) " " (.getMessage ^com.networknt.schema.Error %))
        (.validate ^com.networknt.schema.Schema (definition def-name) ^String (json/write-str value) InputFormat/JSON)))

;; Which definition each message is

(def response-defs
  {"server/discover" "DiscoverResultResponse"
   "tools/list" "ListToolsResultResponse"
   "tools/call" "CallToolResultResponse"
   "resources/list" "ListResourcesResultResponse"
   "resources/templates/list" "ListResourceTemplatesResultResponse"
   "resources/read" "ReadResourceResultResponse"
   "prompts/list" "ListPromptsResultResponse"
   "prompts/get" "GetPromptResultResponse"
   "completion/complete" "CompleteResultResponse"
   "subscriptions/listen" "SubscriptionsListenResultResponse"})

(def request-defs
  {"server/discover" "DiscoverRequest"
   "tools/list" "ListToolsRequest"
   "tools/call" "CallToolRequest"
   "resources/list" "ListResourcesRequest"
   "resources/templates/list" "ListResourceTemplatesRequest"
   "resources/read" "ReadResourceRequest"
   "prompts/list" "ListPromptsRequest"
   "prompts/get" "GetPromptRequest"
   "completion/complete" "CompleteRequest"
   "subscriptions/listen" "SubscriptionsListenRequest"})

(def notification-defs
  {"notifications/progress" "ProgressNotification"
   "notifications/message" "LoggingMessageNotification"
   "notifications/cancelled" "CancelledNotification"
   "notifications/tools/list_changed" "ToolListChangedNotification"
   "notifications/prompts/list_changed" "PromptListChangedNotification"
   "notifications/resources/list_changed" "ResourceListChangedNotification"
   "notifications/resources/updated" "ResourceUpdatedNotification"
   "notifications/subscriptions/acknowledged" "SubscriptionsAcknowledgedNotification"})

(defn- check
  "[definition problems] for one message; methods is the request method of
  each id the client sent."
  [methods message]
  (let [{:strs [id method result error]} message]
    (cond
      (and method (some? id)) (let [d (get request-defs method)] (when d [d (problems d message)]))
      method (let [d (get notification-defs method)] (when d [d (problems d message)]))
      error ["JSONRPCErrorResponse" (problems "JSONRPCErrorResponse" message)]
      (= "input_required" (get result "resultType"))
      ["InputRequiredResult" (into (problems "JSONRPCResultResponse" message)
                                   (problems "InputRequiredResult" result))]
      ;; tasks/* and CreateTaskResult belong to the Tasks extension, which
      ;; the core schema does not define: generic result shape only
      (or (= "task" (get result "resultType")) (str/starts-with? (str (get methods id)) "tasks/"))
      ["JSONRPCResultResponse" (problems "JSONRPCResultResponse" message)]
      (= "tools/call" (get methods id))
      ["CallToolResultResponse" (into (problems "CallToolResultResponse" message)
                                      (problems "CallToolResult" result))]
      :else (let [d (get response-defs (get methods id) "JSONRPCResultResponse")]
              [d (problems d message)]))))

(defn- recording
  "A transport around transport that records every message both ways."
  [transport sent received]
  (fn [{:keys [on-message on-close]}]
    (let [{:keys [send! close!]} (transport {:on-message (fn [m] (swap! received conj m) (on-message m))
                                             :on-close on-close})]
      {:send! (fn [m] (swap! sent conj m) (send! m))
       :close! close!})))

(def handlers
  {"elicitation/create" (fn [{:strs [requestedSchema]}]
                          {"action" "accept"
                           "content" (into {} (map (fn [[k s]] [k (case (get s "type") "boolean" true "string" "x" 1)]))
                                           (get requestedSchema "properties"))})
   "sampling/createMessage" (fn [_] {"role" "assistant" "model" "m" "content" {"type" "text" "text" "hi"}})
   "roots/list" (fn [_] {"roots" [{"uri" "file:///work" "name" "work"}]})})

(defn- exercise! [c]
  (doseq [{:strs [name inputSchema]} (client/list-tools c)
          :when (not (#{"test_missing_capability" "json_schema_2020_12_tool" "custom_header_region"} name))]
    (try (client/call-tool c name (case name
                                    "slow_compute" {"seconds" 0}
                                    "test_elicitation" {"message" "hi"}
                                    "test_sampling" {"prompt" "hi"}
                                    {})
                           {:log-level "debug" :on-progress (fn [_])})
         (catch Exception _)))
  (doseq [{:strs [uri]} (client/list-resources c)] (client/read-resource c uri))
  (client/list-resource-templates c)
  (client/read-resource c "test://template/7/data")
  (try (client/read-resource c "test://nope") (catch Exception _))
  (doseq [{:strs [name arguments]} (client/list-prompts c)]
    (try (client/get-prompt c name (into {} (map (fn [a] [(get a "name") "x"])) arguments)) (catch Exception _)))
  (client/complete c {"type" "ref/prompt" "name" "test_prompt_with_arguments"} "arg1" "x")
  (try (client/request c "no/such/method" {}) (catch Exception _))
  (let [sub (client/listen c {"toolsListChanged" true "promptsListChanged" true} (fn [_]))]
    (deref (:acknowledged sub) 2000 nil)
    (client/call-tool c "test_trigger_tool_change")
    (Thread/sleep 100)
    ((:stop! sub))))

(deftest every-message-matches-the-schema
  (let [sent (atom [])
        received (atom [])
        srv (fixture/fixture "latest")
        c (client/connect (recording (client/in-process srv) sent received)
                          {:handlers handlers :tasks true
                           :client-info {"name" "presa-wire-check" "version" "0"}})]
    (try
      (exercise! c)
      (let [created (client/call-tool c "slow_compute" {"seconds" 0 "label" "x"})]
        (when (client/task? created) (client/await-task c created {:timeout-ms 5000})))
      (finally (client/close c)))
    (let [methods (into {} (keep (fn [{:strs [id method]}] (when (and id method) [id method]))) @sent)
          checked (keep #(when-let [[d ps] (check methods %)] {:def d :problems ps :message %})
                        (concat @sent @received))
          bad (filter (comp seq :problems) checked)]
      (is (> (count checked) 60) "the run covered enough traffic")
      (is (= #{} (set (map :def bad))) (str/join "\n" (map (fn [{:keys [def problems message]}]
                                                            (str def ": " problems " in " (pr-str message)))
                                                          (take 5 bad))))
      (testing "the run reached every kind of message"
        (is (every? (set (map :def checked))
                    ["DiscoverResultResponse" "ListToolsResultResponse" "CallToolResultResponse"
                     "ReadResourceResultResponse" "GetPromptResultResponse" "CompleteResultResponse"
                     "JSONRPCErrorResponse" "InputRequiredResult" "ProgressNotification"
                     "LoggingMessageNotification" "SubscriptionsAcknowledgedNotification"
                     "ToolListChangedNotification" "CallToolRequest" "SubscriptionsListenRequest"]))))))

(deftest negative-controls
  (let [ok {"jsonrpc" "2.0" "id" 1
            "result" {"resultType" "complete" "tools" [] "ttlMs" 0 "cacheScope" "private"}}]
    (is (empty? (problems "ListToolsResultResponse" ok)))
    (testing "the validator catches broken messages"
      (doseq [[label broken] [["no resultType" (update ok "result" dissoc "resultType")]
                              ["no ttlMs" (update ok "result" dissoc "ttlMs")]
                              ["a bad cacheScope" (assoc-in ok ["result" "cacheScope"] "shared")]
                              ["a tool without inputSchema" (assoc-in ok ["result" "tools"] [{"name" "x"}])]
                              ["a bad jsonrpc version" (assoc ok "jsonrpc" "1.0")]]]
        (is (seq (problems "ListToolsResultResponse" broken)) label)))
    ;; CallToolResultResponse's result is anyOf InputRequiredResult and
    ;; CallToolResult, and InputRequiredResult accepts nearly any object, so
    ;; content is checked against CallToolResult itself (see `check`)
    (is (seq (problems "CallToolResult" {"resultType" "complete" "content" [{"type" "nope"}]})))
    (is (seq (problems "CallToolRequest"
                       {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" "x"}}))
        "a request without _meta")))
