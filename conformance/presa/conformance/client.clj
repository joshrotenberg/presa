(ns presa.conformance.client
  "The client the official MCP conformance runner's client leg runs. The
  runner starts one scenario server per scenario and runs this namespace
  with the server URL as the last argument, the scenario name in
  MCP_CONFORMANCE_SCENARIO and optional input in MCP_CONFORMANCE_CONTEXT.
  PRESA_CLIENT_PROTOCOL pins the revision (default 2026-07-28).

  The runner scores the traffic its server records, so this drives
  presa.client the way an application would and adds no protocol behavior
  of its own:

  1. connect, which sends server/discover on 2026-07-28 and initialize on
     the older revisions
  2. tools/list, then call the tools the context names in toolCalls with
     their arguments, or else every listed tool with arguments sampled from
     its input schema; input requests are answered by the handlers below
  3. list and read every resource, and list and get every prompt, when the
     server advertises them

  Modeled on snodo's conformance/client.exs."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [presa.auth.client :as auth-client]
            [presa.client :as client]
            [presa.transport.http :as http]))

(defn- log [label v]
  (binding [*out* *err*]
    (println (str label ": " (pr-str v))))
  v)

(defn- sample
  "A minimal value for a JSON Schema: every declared object property, the
  first enum or const value, a default, or a fixed value per type."
  [schema]
  (cond
    (not (map? schema)) nil
    (contains? schema "const") (get schema "const")
    (seq (get schema "enum")) (first (get schema "enum"))
    (contains? schema "default") (get schema "default")
    :else
    (let [t (get schema "type")
          t (if (sequential? t) (first t) t)]
      (case t
        "object" (into {} (map (fn [[k s]] [k (sample s)])) (get schema "properties"))
        "array" []
        "string" "conformance"
        ("number" "integer") 1
        "boolean" true
        (when (contains? schema "properties") (sample (assoc schema "type" "object")))))))

(def handlers
  {"elicitation/create" (fn [{:strs [mode requestedSchema]}]
                          (if (= "url" mode)
                            {"action" "accept"}
                            {"action" "accept" "content" (sample requestedSchema)}))
   ;; no model runs here: a fixed assistant message stands in for sampling
   "sampling/createMessage" (fn [_] {"role" "assistant"
                                     "content" {"type" "text" "text" "conformance"}
                                     "model" "conformance"})
   "roots/list" (fn [_] {"roots" [{"uri" "file:///conformance" "name" "conformance"}]})})

(defn- attempt [label f]
  (try (log label (f))
       (catch Exception e (log label {:error (ex-message e) :data (ex-data e)}))))

(defn- context []
  (when-let [s (System/getenv "MCP_CONFORMANCE_CONTEXT")]
    (json/read-str s)))

(def client-metadata-url
  "The client ID metadata document the runner's basic-cimd scenario expects."
  "https://conformance-test.local/client-metadata.json")

(defn- token-provider
  "For auth/* scenarios: an OAuth provider with the context's credentials,
  reading the authorization redirect instead of opening a browser."
  [url]
  (let [scenario (str (System/getenv "MCP_CONFORMANCE_SCENARIO"))
        ctx (context)]
    (when (str/starts-with? scenario "auth/")
      (auth-client/provider
       (cond-> {:resource url
                :authorizer (auth-client/redirect-reader)
                :client-metadata-url client-metadata-url
                :client-name "presa-conformance"}
         (get ctx "client_id") (assoc :client-id (get ctx "client_id"))
         (get ctx "client_secret") (assoc :client-secret (get ctx "client_secret"))
         (get ctx "private_key_pem") (assoc :private-key-pem (get ctx "private_key_pem"))
         (str/starts-with? scenario "auth/client-credentials") (assoc :grant :client-credentials)
         ;; workload identity: the context's workload JWT is the assertion
         (get ctx "valid_jwt") (assoc :grant :jwt-bearer :assertion (get ctx "valid_jwt"))
         ;; enterprise-managed: the assertion is an ID-JAG from the IdP
         (get ctx "idp_id_token") (assoc :grant :jwt-bearer
                                         :assertion (auth-client/id-jag {:token-endpoint (get ctx "idp_token_endpoint")
                                                                         :id-token (get ctx "idp_id_token")
                                                                         :client-id (get ctx "idp_client_id")})))))))

(defn run [url]
  (let [protocol (or (System/getenv "PRESA_CLIENT_PROTOCOL") "2026-07-28")
        provider (token-provider url)
        c (client/connect (http/url url (cond-> {:log (fn [& parts] (log "transport" (apply str parts)))}
                                          provider (assoc :token-provider provider)))
                          {:versions [protocol]
                           :client-info {"name" "presa-conformance" "version" "0.1.0"}
                           :handlers handlers
                           :timeout-ms 10000})
        capabilities (:server-capabilities (log "connected" (client/info c)))
        tools (or (attempt "tools/list" #(client/list-tools c)) [])
        calls (get (context) "toolCalls")]
    (try
      (cond
        ;; this scenario checks that a listed schema reaches the caller
        ;; unchanged: echo one tool's inputSchema back to the server
        (= "json-schema-2020-12-preservation" (System/getenv "MCP_CONFORMANCE_SCENARIO"))
        (let [focal (some #(when (= "json_schema_2020_12_tool" (get % "name")) %) tools)]
          (attempt "tools/call json_schema_echo"
                   #(client/call-tool c "json_schema_echo" {"schema" (get focal "inputSchema")})))

        (seq calls)
        (doseq [{:strs [name arguments]} calls]
          (attempt (str "tools/call " name) #(client/call-tool c name (or arguments {}))))
        :else
        (doseq [{:strs [name inputSchema]} (when (sequential? tools) tools)]
          (attempt (str "tools/call " name) #(client/call-tool c name (or (sample inputSchema) {})))))
      (when (contains? capabilities "resources")
        (doseq [{:strs [uri]} (attempt "resources/list" #(client/list-resources c))]
          (attempt (str "resources/read " uri) #(client/read-resource c uri))))
      (when (contains? capabilities "prompts")
        (doseq [{:strs [name arguments]} (attempt "prompts/list" #(client/list-prompts c))]
          (attempt (str "prompts/get " name)
                   #(client/get-prompt c name (into {} (map (fn [a] [(get a "name") "conformance"])) arguments)))))
      (finally (client/close c)))))

(defn -main [& args]
  (let [url (last args)]
    (when-not url
      (log "error" "expected the server URL as the last argument")
      (System/exit 1))
    (try
      (run url)
      (catch Exception e
        (log "failed" {:error (ex-message e) :data (ex-data e)})
        (shutdown-agents)
        (System/exit 1)))
    (shutdown-agents)
    (System/exit 0)))
