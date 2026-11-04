(ns presa.conformance.fixture
  "The server the official MCP conformance runner's server scenarios call:
  the tool, resource and prompt names they expect, per
  docs/wire-2026-07-28.md section 16. Served over Streamable HTTP on
  127.0.0.1. On start it prints one JSON line,
  {\"conformanceReady\":true,\"url\":...}, then serves until stdin closes.

  MCP_PORT picks the port (default 0, a free one). MCP_FIXTURE_PROFILE is
  \"latest\" (2026-07-28 only, the default) or \"legacy\" (initialize-era
  revisions as well)."
  (:require [clojure.data.json :as json]
            [presa.content :as content]
            [presa.input :as input]
            [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.http :as http]))

(def png "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z4p8AAAAASUVORK5CYII=")
(def wav "UklGRiQAAABXQVZFZm10IBAAAAABAAEAQB8AAEAfAAABAAgAZGF0YQAAAAA=")

(defn- tool [name description handler & [extra]]
  (merge {:name name :description description :handler handler} extra))

(def name-request
  (input/elicit "Please provide name"
                {"type" "object" "properties" {"name" {"type" "string"}} "required" ["name"]}))

(defn- accepted [ctx id k]
  (let [{:strs [action content]} (server/input-response ctx id)]
    (when (= "accept" action) (get content k))))

(def tools
  [(tool "a_header_probe" "Accepts empty arguments; the first tool in tools/list"
         (fn [_ _] "ok"))
   (tool "test_simple_text" "Returns one text content item"
         (fn [_ _] "This is a simple text response for testing."))
   (tool "test_image_content" "Returns one image content item"
         (fn [_ _] (server/content [(content/image png "image/png")])))
   (tool "test_audio_content" "Returns one audio content item"
         (fn [_ _] (server/content [(content/audio wav "audio/wav")])))
   (tool "test_embedded_resource" "Returns an embedded resource"
         (fn [_ _] (server/content [(content/embedded-resource "test://embedded-resource"
                                                               "This is an embedded resource content."
                                                               {:mime-type "text/plain"})])))
   (tool "test_multiple_content_types" "Returns text, image and resource content"
         (fn [_ _] (server/content [(content/text "Multiple content types test:")
                                    (content/image png "image/png")
                                    (content/embedded-resource "test://mixed-content-resource"
                                                               "{\"test\":\"data\",\"value\":123}"
                                                               {:mime-type "application/json"})])))
   (tool "test_error_handling" "Returns a tool execution error"
         (fn [_ _] (server/tool-error "This tool intentionally returns an error for testing")))
   (tool "test_tool_with_progress" "Reports progress three times"
         (fn [_ ctx]
           (server/progress! ctx 0 {:total 100 :message "Starting computation"})
           (server/progress! ctx 50 {:total 100 :message "First half complete"})
           (server/progress! ctx 100 {:total 100 :message "Computation complete"})
           (str "Progress computation completed: " (reduce + (range 101)))))
   (tool "test_missing_capability" "Asks for sampling whatever the client declared"
         (fn [_ ctx]
           (if (server/input-response ctx "greeting")
             "sampled"
             (server/input-required {"greeting" (input/sample [{"role" "user" "content" (content/text "Say hello")}] 50)}))))
   (tool "test_logging_tool" "Logs at every level"
         (fn [_ ctx]
           (doseq [level protocol/log-levels]
             (server/log! ctx level (str level " message") "conformance"))
           "Logged at every level"))
   (tool "test_streaming_elicitation" "Asks for a name"
         (fn [_ ctx]
           (if-let [n (accepted ctx "user_name" "name")]
             (str "Hello, " n "!")
             (server/input-required {"user_name" name-request}))))
   (tool "test_trigger_tool_change" "Tells listening clients the tool list changed"
         (fn [_ ctx] (server/list-changed! ctx "tools") "tools changed"))
   (tool "test_trigger_prompt_change" "Tells listening clients the prompt list changed"
         (fn [_ ctx] (server/list-changed! ctx "prompts") "prompts changed"))
   (tool "test_input_required_result_elicitation" "Elicits a name, then greets"
         (fn [_ ctx]
           (if-let [n (accepted ctx "user_name" "name")]
             (str "Hello, " n "!")
             (server/input-required {"user_name" name-request}))))
   (tool "test_input_required_result_sampling" "Samples an answer"
         (fn [_ ctx]
           (if-let [r (server/input-response ctx "capital_question")]
             (str "The model said: " (get-in r ["content" "text"]))
             (server/input-required
              {"capital_question" (input/sample [{"role" "user" "content" (content/text "What is the capital of France?")}] 100)}))))
   (tool "test_input_required_result_list_roots" "Lists the client's roots"
         (fn [_ ctx]
           (if-let [r (server/input-response ctx "client_roots")]
             (str "Roots: " (count (get r "roots")))
             (server/input-required {"client_roots" (input/roots)}))))
   (tool "test_input_required_result_request_state" "Keeps state across rounds"
         (fn [_ ctx]
           (if (and (= {"phase" "confirm"} (:state ctx)) (server/input-response ctx "confirm"))
             "state-ok"
             (server/input-required
              {"confirm" (input/elicit "Confirm?" {"type" "object" "properties" {"ok" {"type" "boolean"}}})}
              {"phase" "confirm"}))))
   (tool "test_input_required_result_multiple_inputs" "Asks for three inputs at once"
         (fn [_ ctx]
           (if (every? #(server/input-response ctx %) ["user_name" "greeting" "client_roots"])
             "All inputs received"
             (server/input-required
              {"user_name" name-request
               "greeting" (input/sample [{"role" "user" "content" (content/text "Generate a greeting")}] 50)
               "client_roots" (input/roots)}
              {"phase" "multiple"}))))
   (tool "test_input_required_result_multi_round" "Asks in two rounds"
         (fn [_ ctx]
           (let [step (get (:state ctx) "step")]
             (cond
               (and (= 2 step) (accepted ctx "step2" "color"))
               (str (get (:state ctx) "name") " likes " (accepted ctx "step2" "color"))

               (and (= 1 step) (accepted ctx "step1" "name"))
               (server/input-required
                {"step2" (input/elicit "Favorite color?" {"type" "object" "properties" {"color" {"type" "string"}}})}
                {"step" 2 "name" (accepted ctx "step1" "name")})

               :else
               (server/input-required
                {"step1" (input/elicit "Name?" {"type" "object" "properties" {"name" {"type" "string"}}})}
                {"step" 1})))))
   (tool "test_input_required_result_tampered_state" "Rejects a changed requestState"
         (fn [_ ctx]
           (if (:state ctx)
             "state verified"
             (server/input-required {"user_name" name-request} {"phase" "tamper"}))))
   (tool "test_input_required_result_capabilities" "Asks only for what the client supports"
         (fn [_ ctx]
           (let [candidates {"user_name" name-request
                             "greeting" (input/sample [{"role" "user" "content" (content/text "Generate a greeting")}] 50)}
                 supported (into {} (filter #(input/supported? (val %) (:client-capabilities ctx))) candidates)]
             (cond
               (some #(server/input-response ctx %) (keys candidates)) "Inputs received"
               (seq supported) (server/input-required supported)
               :else "No supported input"))))
   ;; Initialize-era scenarios: presa sends these input requests to the
   ;; client as requests and calls the handler again with the answers.
   (tool "test_elicitation" "Asks the user for a username and email"
         (fn [{:strs [message]} ctx]
           (if-let [r (server/input-response ctx "info")]
             (str "User response: action=" (get r "action") ", content=" (json/write-str (get r "content")))
             (server/input-required
              {"info" (input/elicit (or message "Please provide your information")
                                    {"type" "object"
                                     "properties" {"username" {"type" "string" "description" "User's response"}
                                                   "email" {"type" "string" "description" "User's email address"}}
                                     "required" ["username" "email"]})})))
         {:input-schema {"type" "object" "properties" {"message" {"type" "string"}} "required" ["message"]}})
   (tool "test_sampling" "Asks the client's model to answer a prompt"
         (fn [{:strs [prompt]} ctx]
           (if-let [r (server/input-response ctx "answer")]
             (str "LLM response: " (get-in r ["content" "text"]))
             (server/input-required
              {"answer" (input/sample [{"role" "user" "content" (content/text prompt)}] 100)})))
         {:input-schema {"type" "object" "properties" {"prompt" {"type" "string"}} "required" ["prompt"]}})
   (tool "test_tool_with_logging" "Logs three messages while it runs"
         (fn [_ ctx]
           (server/log! ctx "info" "Tool execution started")
           (Thread/sleep 50)
           (server/log! ctx "info" "Tool processing data")
           (Thread/sleep 50)
           (server/log! ctx "info" "Tool execution completed")
           "Tool with logging executed successfully"))
   (tool "test_elicitation_sep1034_defaults" "Elicits with defaults for every primitive type"
         (fn [_ ctx]
           (if-let [r (server/input-response ctx "defaults")]
             (str "Elicitation completed: action=" (get r "action") ", content=" (json/write-str (get r "content")))
             (server/input-required
              {"defaults" (input/elicit "Please review and update the form fields with defaults"
                                        {"type" "object"
                                         "properties" {"name" {"type" "string" "description" "User name" "default" "John Doe"}
                                                       "age" {"type" "integer" "description" "User age" "default" 30}
                                                       "score" {"type" "number" "description" "User score" "default" 95.5}
                                                       "status" {"type" "string" "description" "User status"
                                                                 "enum" ["active" "inactive" "pending"] "default" "active"}
                                                       "verified" {"type" "boolean" "description" "Verification status" "default" true}}})}))))
   (tool "test_elicitation_sep1330_enums" "Elicits with every enum schema variant"
         (fn [_ ctx]
           (if-let [r (server/input-response ctx "enums")]
             (str "Elicitation completed: action=" (get r "action") ", content=" (json/write-str (get r "content")))
             (server/input-required
              {"enums" (input/elicit "Please select options from the enum fields"
                                     {"type" "object"
                                      "properties"
                                      {"untitledSingle" {"type" "string" "description" "Select one option"
                                                         "enum" ["option1" "option2" "option3"]}
                                       "titledSingle" {"type" "string" "description" "Select one option with titles"
                                                       "oneOf" [{"const" "value1" "title" "First Option"}
                                                                {"const" "value2" "title" "Second Option"}
                                                                {"const" "value3" "title" "Third Option"}]}
                                       "legacyEnum" {"type" "string" "description" "Select one option (legacy)"
                                                     "enum" ["opt1" "opt2" "opt3"]
                                                     "enumNames" ["Option One" "Option Two" "Option Three"]}
                                       "untitledMulti" {"type" "array" "description" "Select multiple options"
                                                        "items" {"type" "string" "enum" ["option1" "option2" "option3"]}}
                                       "titledMulti" {"type" "array" "description" "Select multiple options with titles"
                                                      "items" {"anyOf" [{"const" "value1" "title" "First Choice"}
                                                                        {"const" "value2" "title" "Second Choice"}
                                                                        {"const" "value3" "title" "Third Choice"}]}}}})}))))
   ;; Tasks extension scenarios (docs/tasks-extension.md section 9)
   (tool "greet" "Greets; never a task"
         (fn [{:strs [name]} _] (str "Hello, " (or name "World") "!")))
   (tool "slow_compute" "Sleeps for seconds, then completes; a task when the client declares Tasks"
         (fn [{:strs [seconds label]} _]
           (when (and (number? seconds) (pos? seconds))
             (Thread/sleep (long (Math/round (* 1000.0 (double seconds))))))
           (str "Completed " label))
         {:task :optional})
   (tool "failing_job" "A task that reports a tool error"
         (fn [_ _] (server/tool-error "The task job failed"))
         {:task :required})
   (tool "protocol_error_job" "A task that raises"
         (fn [_ _] (throw (ex-info "protocol_error_job raised" {})))
         {:task :required})
   (tool "confirm_delete" "Asks for confirmation inside its task"
         (fn [{:strs [filename]} ctx]
           (if-let [r (server/input-response ctx "confirm-delete")]
             (str "Delete confirmed: " (true? (get-in r ["content" "confirm"])))
             (server/input-required
              {"confirm-delete" (input/elicit (str "Delete " filename "?")
                                              {"type" "object"
                                               "properties" {"confirm" {"type" "boolean"}}
                                               "required" ["confirm"]})})))
         {:task :optional})
   (tool "multi_input" "Asks for two inputs at once inside its task"
         (fn [_ ctx]
           (let [a (server/input-response ctx "first-input")
                 b (server/input-response ctx "second-input")
                 label #(or (get-in % ["content" "name"]) "confirmed")]
             (if (and a b)
               (str "Inputs received: " (label a) ", " (label b))
               (server/input-required
                (cond-> {}
                  (not a) (assoc "first-input" (input/elicit "First input" {"type" "object" "properties" {"name" {"type" "string"} "confirm" {"type" "boolean"}}}))
                  (not b) (assoc "second-input" (input/elicit "Second input" {"type" "object" "properties" {"name" {"type" "string"} "confirm" {"type" "boolean"}}})))))))
         {:task :optional})
   (tool "test_tool_with_task" "Asks for a name synchronously, then runs as a task"
         (fn [_ ctx]
           (if-let [n (accepted ctx "user_name" "name")]
             (str "Hello, " n "; task completed")
             (server/input-required
              {"user_name" (input/elicit "What is your name?"
                                         {"type" "object" "properties" {"name" {"type" "string"}} "required" ["name"]})}
              {"round" 1})))
         ;; synchronous MRTR first; the round with the answer becomes a task
         {:task (fn [params _] (if (seq (get params "inputResponses")) :required :sync))})
   (tool "custom_header_region" "Returns its region, which also travels in the Mcp-Param-Region header"
         (fn [{:strs [region]} _] (str "Region: " region))
         {:input-schema {"type" "object"
                         "properties" {"region" {"type" "string" "x-mcp-header" "Region"}
                                       "query" {"type" "string"}}
                         "required" ["region" "query"]}})
   (tool "json_schema_2020_12_tool" "Tool with JSON Schema 2020-12 features"
         (fn [_ _] "schema accepted")
         {:input-schema {"$schema" "https://json-schema.org/draft/2020-12/schema"
                         "type" "object"
                         "$defs" {"address" {"$anchor" "addressDef"
                                             "type" "object"
                                             "properties" {"street" {"type" "string"} "city" {"type" "string"}}}}
                         "properties" {"name" {"type" "string"}
                                       "address" {"$ref" "#/$defs/address"}
                                       "contactMethod" {"type" "string" "enum" ["phone" "email"]}
                                       "phone" {"type" "string"}
                                       "email" {"type" "string"}}
                         "allOf" [{"anyOf" [{"required" ["phone"]} {"required" ["email"]}]}]
                         "if" {"properties" {"contactMethod" {"const" "phone"}} "required" ["contactMethod"]}
                         "then" {"required" ["phone"]}
                         "else" {"required" ["email"]}
                         "additionalProperties" false}})])

(def resources
  [{:uri "test://static-text" :name "static_text" :description "Static text resource"
    :mime-type "text/plain"
    :handler (fn [_] "This is a static text resource for testing.")}
   {:uri "test://static-binary" :name "static_binary" :description "Static binary resource"
    :mime-type "application/octet-stream"
    :handler (fn [_] (byte-array (map unchecked-byte [0x00 0x01 0x02 0x03 0x7F 0x80 0xFE 0xFF])))}])

(def resource-templates
  [{:uri-template "test://template/{id}/data" :name "template_data" :description "Template resource"
    :mime-type "text/plain"
    :handler (fn [{:strs [id]} _] (str "Template resource data for id " id))}])

(def prompts
  [{:name "test_simple_prompt" :description "A prompt without arguments"
    :handler (fn [_ _] "This is a simple prompt for testing.")}
   {:name "test_prompt_with_arguments" :description "A prompt with two required arguments"
    :arguments [{:name "arg1" :description "First argument" :required true}
                {:name "arg2" :description "Second argument" :required true}]
    :handler (fn [{:strs [arg1 arg2]} _] (str "Prompt with arguments: arg1='" arg1 "', arg2='" arg2 "'"))
    :complete {"arg1" (fn [value _ _] {"values" [value] "total" 1 "hasMore" false})
               "arg2" (fn [value _ _] {"values" [value] "total" 1 "hasMore" false})}}
   {:name "test_prompt_with_embedded_resource" :description "A prompt embedding a resource"
    :arguments [{:name "resourceUri" :description "The resource to embed" :required true}]
    :handler (fn [{:strs [resourceUri]} _]
               [{"role" "user" "content" (content/embedded-resource resourceUri "This is embedded resource content."
                                                                    {:mime-type "text/plain"})}
                "Please process the embedded resource above."])}
   {:name "test_prompt_with_image" :description "A prompt with an image"
    :handler (fn [_ _]
               [{"role" "user" "content" (content/image png "image/png")}
                "Please analyze the image above."])}
   {:name "test_input_required_result_prompt" :description "A prompt that asks for context"
    :handler (fn [_ ctx]
               (if-let [c (accepted ctx "user_context" "context")]
                 (str "Prompt with context: " c)
                 (server/input-required
                  {"user_context" (input/elicit "Provide context"
                                                {"type" "object" "properties" {"context" {"type" "string"}}})})))}])

(defn fixture
  "The fixture server for profile \"latest\" or \"legacy\"."
  [profile]
  (server/server {:name "presa-conformance" :version "0.1.0"
                  :versions (if (= "legacy" profile) protocol/versions [protocol/latest-version])
                  :logging true
                  :list-changed true
                  :subscribe (= "legacy" profile)
                  :tasks (when-not (= "legacy" profile) {:ttl-ms 3600000 :poll-interval-ms 100})
                  :tools tools
                  :resources resources
                  :resource-templates resource-templates
                  :prompts prompts}))

(defn -main [& _]
  (let [port (parse-long (or (System/getenv "MCP_PORT") "0"))
        profile (or (System/getenv "MCP_FIXTURE_PROFILE") "latest")
        {:keys [url stop!]} (http/serve! (fixture profile) {:port port})]
    (println (json/write-str {"conformanceReady" true "url" url} :escape-slash false))
    (flush)
    ;; serve until stdin closes
    (try (while (not= -1 (.read System/in))) (catch Exception _))
    (stop!)
    (shutdown-agents)))
