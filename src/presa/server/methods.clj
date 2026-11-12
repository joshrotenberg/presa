(ns ^:no-doc presa.server.methods
  "Internal to presa.server; not part of the API. The methods for tools,
  resources, prompts, completion and discovery."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [presa.content :as content]
            [presa.protocol :as protocol]
            [presa.schema :as schema]
            [presa.tasks :as tasks]
            [presa.server.core :refer [check-output! client-request! emit! error input-required? tool-error tool-result]]
            [presa.server.catalog :refer [capabilities]]
            [presa.server.access :refer [check-permitted! permitted?]]
            [presa.server.shape :refer [paged wire-prompt wire-resource wire-template wire-tool]]
            [presa.server.tasks :refer [start-task! task-policy]]))

;; Methods

(defn server-discover [srv _params _context]
  ;; serverInfo travels in the result's _meta, which `shape` adds
  (cond-> {"supportedVersions" (:versions srv)
           "capabilities" (capabilities srv)}
    (:instructions srv) (assoc "instructions" (:instructions srv))))

(defn initialize-result [srv version]
  (cond-> {"protocolVersion" version
           "capabilities" (capabilities srv)
           "serverInfo" (:info srv)}
    (:instructions srv) (assoc "instructions" (:instructions srv))))

(defn arguments-map [params k method]
  (let [arguments (get params k {})]
    (when-not (map? arguments)
      (throw (error :invalid-params (str method " arguments must be an object"))))
    arguments))

(defn required-name [params k method]
  (let [v (get params k)]
    (when-not (and (string? v) (not (str/blank? v)))
      (throw (error :invalid-params (str method " requires a string " k))))
    v))

(def max-input-rounds 8)

(defn run-handler
  "Call handler, a function of the context. On 2026-07-28 an input_required
  value is returned as is, for `shape` to send; on an initialize-era
  session presa asks the client and calls handler again."
  [context handler]
  (if (protocol/stateless? (:version context))
    (handler context)
    (loop [context (assoc context :input-responses {}) round 1]
      (let [v (handler context)]
        (if-not (input-required? v)
          v
          (let [{:keys [requests state]} (:presa.server/input-required v)]
            (when (> round max-input-rounds)
              (throw (error :internal-error (str "The handler asked for input more than " max-input-rounds " times"))))
            (recur (assoc context
                          :state state
                          :input-responses (into {}
                                                 (map (fn [[id {:strs [method params]}]]
                                                        [id (client-request! context method params)]))
                                                 requests))
                   (inc round))))))))

(defn object-schema? [schema]
  (and (map? schema) (= "object" (get schema "type"))))

(defn era-tool?
  "Whether the revision of context can carry tool: initialize-era revisions
  require object schemas for inputSchema and outputSchema, which
  2026-07-28 does not."
  [context tool]
  (or (protocol/stateless? (:version context))
      (and (or (nil? (:input-schema tool)) (object-schema? (:input-schema tool)))
           (or (nil? (:output-schema tool)) (object-schema? (:output-schema tool))))))

(defn tools-list [srv params context]
  (paged srv "tools/list" params "tools"
         (->> (:tool-order srv)
              (map (:tools srv))
              (filter #(era-tool? context %))
              (filter #(permitted? srv context :tool (:name %)))
              (map wire-tool))))

(defn keywordize-args?
  "Whether tool's handler gets keyword argument keys: the tool's
  :keywordize-args? when it has one, else the server's."
  [srv tool]
  (if (contains? tool :keywordize-args?) (boolean (:keywordize-args? tool)) (boolean (:keywordize-args? srv))))

(defn tools-call [srv params context]
  (let [tool-name (required-name params "name" "tools/call")
        arguments (arguments-map params "arguments" "tools/call")
        tool (or (let [t (get-in srv [:tools tool-name])] (when (and t (era-tool? context t)) t))
                 (throw (error :invalid-params (str "Unknown tool: " tool-name))))
        _ (check-permitted! srv context :tool tool-name (str "tool " tool-name))
        policy (when-not (and (:validate? srv) (:input-schema tool)
                              ((:validator srv) arguments (:input-schema tool)))
                 (task-policy srv tool params context))
        declared? (tasks/declared? (:client-capabilities context))]
    (when (and (= :required policy) (not declared?))
      (throw (error :missing-client-capability "Missing required client capability" tasks/required-capabilities)))
    (if-let [invalid (and (:validate? srv) (:input-schema tool)
                          ((:validator srv) arguments (:input-schema tool)))]
      (tool-error (str "Invalid arguments " (schema/describe invalid)))
      ;; validated as sent; the handler (and a task's) sees them converted
      (let [arguments (if (keywordize-args? srv tool) (walk/keywordize-keys arguments) arguments)]
        (if (and declared? (#{:optional :required} policy))
          (start-task! srv tool arguments context)
          (let [v (try
                    (run-handler context #((:handler tool) arguments %))
                    ;; Throwable: an assert or a stack overflow is a tool error too
                    (catch Throwable e
                      (if (:presa/error (ex-data e))
                        (throw e)
                        (do (emit! srv {:event :tool/error :tool tool-name :request-id (:request-id context)
                                        :exception e})
                            (tool-error (or (ex-message e) (str (class e))))))))
                result (if (input-required? v) v (tool-result v))]
            (when-not (input-required? result)
              (check-output! srv tool result))
            result))))))

(defn resources-list [srv params context]
  (paged srv "resources/list" params "resources"
         (->> (:resource-order srv)
              (filter #(permitted? srv context :resource %))
              (map (comp wire-resource (:resources srv))))))

(defn templates-list [srv params context]
  (paged srv "resources/templates/list" params "resourceTemplates"
         (->> (:templates srv)
              (filter #(permitted? srv context :resource-template (:uri-template %)))
              (map wire-template))))

(defn contents
  "A resource handler's return value as resources/read contents."
  [uri mime-type v]
  (cond
    (and (map? v) (contains? v "contents")) (vec (get v "contents"))
    (map? v) [v]
    (and (sequential? v) (every? map? v)) (vec v)
    :else [(content/resource-contents uri v {:mime-type mime-type})]))

(defn not-found [uri]
  (error :invalid-params "Resource not found" {"uri" uri}))

(defn resources-read [srv params context]
  (let [uri (required-name params "uri" "resources/read")]
    (when-not (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" uri)
      (throw (error :invalid-params "resources/read uri must be an absolute URI")))
    (if-let [resource (get-in srv [:resources uri])]
      (let [_ (check-permitted! srv context :resource uri (str "resource " uri))
            v (run-handler context (:handler resource))]
        (if (input-required? v) v {"contents" (contents uri (:mime-type resource) v)}))
      (if-let [[template variables] (some (fn [t] (when-let [vs ((:presa.server/match t) uri)] [t vs]))
                                          (:templates srv))]
        (let [_ (check-permitted! srv context :resource-template (:uri-template template)
                                  (str "resource " uri))
              v (run-handler (assoc context :uri uri) #((:handler template) variables %))]
          (cond
            (nil? v) (throw (not-found uri))
            (input-required? v) v
            :else {"contents" (contents uri (:mime-type template) v)}))
        (throw (not-found uri))))))

(defn prompts-list [srv params context]
  (paged srv "prompts/list" params "prompts"
         (->> (:prompt-order srv)
              (filter #(permitted? srv context :prompt %))
              (map (comp wire-prompt (:prompts srv))))))

(defn message [m]
  (cond
    (string? m) {"role" "user" "content" (content/text m)}
    (and (map? m) (contains? m "role") (contains? m "content")) m
    :else (throw (ex-info "A prompt message is a string or a map with role and content" {:message m}))))

(defn prompts-get [srv params context]
  (let [prompt-name (required-name params "name" "prompts/get")
        arguments (arguments-map params "arguments" "prompts/get")
        prompt (or (get-in srv [:prompts prompt-name])
                   (throw (error :invalid-params (str "Unknown prompt: " prompt-name))))
        _ (check-permitted! srv context :prompt prompt-name (str "prompt " prompt-name))]
    (when-not (every? string? (vals arguments))
      (throw (error :invalid-params "prompts/get arguments must map strings to strings")))
    (when-let [missing (seq (for [{arg :name required :required} (:arguments prompt)
                                  :when (and required (not (contains? arguments arg)))]
                              arg))]
      (throw (error :invalid-params "Missing required prompt arguments" {"missing" (vec missing)})))
    (let [v (run-handler context #((:handler prompt) arguments %))
          result (cond
                   (input-required? v) v
                   (and (map? v) (contains? v "messages"))
                   (update v "messages" #(mapv message %))
                   :else {"messages" (mapv message (if (string? v) [v] v))})]
      (cond-> result
        (and (:description prompt) (not (input-required? result)) (not (contains? result "description")))
        (assoc "description" (:description prompt))))))

(defn completion-result [v]
  (let [{:strs [values total hasMore]} (if (map? v) v {"values" v})
        values (vec (map str values))
        sent (vec (take 100 values))]
    {"completion" (cond-> {"values" sent}
                    (some? total) (assoc "total" total)
                    (and (nil? total) (> (count values) 100)) (assoc "total" (count values))
                    (some? hasMore) (assoc "hasMore" (boolean hasMore))
                    (and (nil? hasMore) (> (count values) 100)) (assoc "hasMore" true))}))

(defn completion-complete [srv params context]
  (let [ref (get params "ref")
        argument (get params "argument")
        arg-name (get argument "name")
        value (get argument "value" "")
        others (let [a (get-in params ["context" "arguments"])] (if (map? a) a {}))]
    (when-not (and (map? ref) (map? argument) (string? arg-name) (string? value))
      (throw (error :invalid-params "completion/complete requires ref and argument {name, value}")))
    (let [target (case (get ref "type")
                   "ref/prompt" (or (get-in srv [:prompts (get ref "name")])
                                    (throw (error :invalid-params (str "Unknown prompt: " (get ref "name")))))
                   "ref/resource" (or (some #(when (= (get ref "uri") (:uri-template %)) %) (:templates srv))
                                      (throw (error :invalid-params (str "Unknown resource template: " (get ref "uri")))))
                   (throw (error :invalid-params (str "Unknown ref type: " (get ref "type")))))]
      ;; completing a prompt or template needs the scopes using it needs
      (if (= "ref/prompt" (get ref "type"))
        (check-permitted! srv context :prompt (:name target) (str "prompt " (:name target)))
        (check-permitted! srv context :resource-template (:uri-template target)
                          (str "resource template " (:uri-template target))))
      (if-let [f (get-in target [:complete arg-name])]
        (completion-result (f value others context))
        (completion-result [])))))

(defn ping [_ _ _] {})
