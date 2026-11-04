(ns presa.server
  "An MCP server as data, and request dispatch as pure functions.

  A server is a map built by `server` from a name, a version, and tools,
  resources, resource templates and prompts. It serves MCP 2026-07-28 by
  default: every request carries its protocol version and client
  capabilities in `_meta`, and `server/discover` describes the server.
  Listing an initialize-era revision in `:versions` opts in to `initialize`
  as well, which Codex needs (see docs/hosts.md).

  Dispatch is in two steps so a transport can run requests concurrently:
  `prepare` reads one message against the connection's session and settles
  the session at once; the `:respond` function it returns does the work and
  builds the response. `handle` does both in one call."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [presa.content :as content]
            [presa.input :as input]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.request-state :as request-state]
            [presa.schema :as schema]
            [presa.uri-template :as uri-template])
  (:import (java.util Base64)))

;; Errors

(defn error
  "An exception that becomes a JSON-RPC error response when a handler throws
  it. code is a key of presa.protocol/error-codes or an integer."
  ([code message] (error code message nil))
  ([code message data]
   (ex-info message {:presa/error {:code code :message message :data data}})))

;; Results

(defn- json-text [v]
  (json/write-str v :escape-slash false :escape-unicode false))

(defn text
  "A tool result with one text content block."
  [s]
  {"content" [(content/text s)] "isError" false})

(defn structured
  "A tool result carrying the map m as structuredContent, and as JSON text
  for clients that read only content."
  [m]
  {"content" [(content/text (json-text m))]
   "structuredContent" m
   "isError" false})

(defn content
  "A tool result from a sequence of content blocks (see presa.content)."
  [blocks]
  {"content" (vec blocks) "isError" false})

(defn tool-error
  "A tool result that reports a failure to the model: isError true, with
  message as its text. Use this for errors the model can act on; `error` is
  for a request the server could not take."
  [message]
  {"content" [(content/text message)] "isError" true})

(defn- tool-result
  "A handler's return value as a tools/call result. A string becomes text, a
  map with \"content\" is a result already, any other map is structured
  content, nil is an empty result, and anything else becomes JSON text."
  [v]
  (cond
    (string? v) (text v)
    (and (map? v) (contains? v "content")) (merge {"isError" false} v)
    (map? v) (structured v)
    (nil? v) {"content" [] "isError" false}
    :else (text (json-text v))))

(defn input-required
  "What a tool, resource or prompt handler returns to ask the client for
  input before it can finish. requests maps ids of the server's choosing to
  input requests (see presa.input). state is an optional JSON value the
  server gets back with the answers, signed so the client cannot change it.

  The handler is called again with the answers: (:input-responses context)
  maps the same ids to the client's results, and (:state context) holds
  state. Answers can be partial; ask again for what is missing. On
  2026-07-28 this becomes an input_required result the client answers on a
  new request; on an initialize-era session presa sends the requests to the
  client itself and calls the handler again on the same request."
  ([requests] (input-required requests nil))
  ([requests state]
   (when-not (and (map? requests) (every? string? (keys requests))
                  (every? #(input/request-methods (get % "method")) (vals requests)))
     (throw (ex-info "input-required takes a map of string ids to input requests"
                     {:kind :invalid-input :requests requests})))
   {::input-required {:requests requests :state state}}))

(defn input-response
  "The client's answer to the input request with id, or nil."
  [context id]
  (get (:input-responses context) id))

(defn- input-required? [v] (and (map? v) (contains? v ::input-required)))

;; Definitions

(defn- check-name [kind m k]
  (when-not (and (map? m) (string? (get m k)) (not (str/blank? (get m k))))
    (throw (ex-info (str "A " kind " needs a non-blank string " k) {:kind :invalid-input kind m}))))

(defn- check-handler [kind m label]
  (when-not (ifn? (:handler m))
    (throw (ex-info (str "The " kind " " label " needs a :handler function")
                    {:kind :invalid-input kind label}))))

(defn- check-complete [kind m label]
  (when-let [c (:complete m)]
    (when-not (and (map? c) (every? string? (keys c)) (every? ifn? (vals c)))
      (throw (ex-info (str "The " kind " " label " :complete must map argument names to functions")
                      {:kind :invalid-input kind label})))))

(defn- index
  "Definitions by key, with their order, checking that keys are distinct."
  [kind k defs]
  (let [ks (map k defs)]
    (when-not (apply distinct? ::none ks)
      (throw (ex-info (str "The " kind " " (name k) " values must be distinct")
                      {:kind :invalid-input :values (vec ks)})))
    [(into {} (map (juxt k identity)) defs) (vec ks)]))

(defn- tool-def [tool]
  (check-name "tool" tool :name)
  (check-handler "tool" tool (:name tool))
  tool)

(defn- resource-def [resource]
  (check-name "resource" resource :uri)
  (check-name "resource" resource :name)
  (check-handler "resource" resource (:uri resource))
  resource)

(defn- template-def [template]
  (check-name "resource template" template :uri-template)
  (check-name "resource template" template :name)
  (check-handler "resource template" template (:uri-template template))
  (check-complete "resource template" template (:uri-template template))
  (assoc template ::match (uri-template/matcher (:uri-template template))))

(defn- prompt-def [prompt]
  (check-name "prompt" prompt :name)
  (check-handler "prompt" prompt (:name prompt))
  (check-complete "prompt" prompt (:name prompt))
  (doseq [arg (:arguments prompt)]
    (check-name "prompt argument" arg :name))
  prompt)

(defn server
  "A server map from options:

  - :name, :version  the server's implementation name and version (required)
  - :title, :instructions  optional strings sent to clients
  - :tools  tool maps:
      :name     (required)
      :handler  (fn [arguments context]) (required). arguments is the call's
                arguments map with string keys; context is described at
                `prepare`. Returns a string (text), a map (structured
                content), a full result map with \"content\" (see `content`,
                `tool-error`), or nil; a handler that throws reports an
                isError result, unless it throws `error`.
      :description, :title, :input-schema (default {\"type\" \"object\"}),
      :output-schema, :annotations, :meta
  - :resources  resource maps:
      :uri, :name (required)
      :handler  (fn [context]) (required). Returns a string (text), a byte
                array (blob), a contents map or a sequence of them (see
                presa.content/resource-contents).
      :description, :title, :mime-type, :size, :annotations, :meta
  - :resource-templates  template maps:
      :uri-template, :name (required)
      :handler  (fn [variables context]) (required). variables are the
                template's values from the URI, with string keys. Returns what
                a resource handler does, or nil when there is no such
                resource.
      :complete  {argument-name (fn [value arguments context])}, see below
      :description, :title, :mime-type, :annotations, :meta
  - :prompts  prompt maps:
      :name     (required)
      :handler  (fn [arguments context]) (required). Returns a string (one user
                message), a sequence of messages (strings or message maps
                with \"role\" and \"content\"), or a map with \"messages\".
      :arguments  [{:name :description :required}]
      :complete  {argument-name (fn [value arguments context])}
      :description, :title, :meta
  - :versions  the revisions served, from presa.protocol/versions. Defaults
      to [presa.protocol/latest-version]. Include initialize-era revisions
      (presa.protocol/versions serves all) to answer initialize.
  - :page-size  the most items a list result holds; more come with a
      nextCursor (default: everything on one page)
  - :cache  {:ttl-ms n :scope \"private\" or \"public\"}, the caching hints of
      2026-07-28 list, read and discover results (default 0 and private)
  - :validate?  check tool arguments against :input-schema, and structured
      results against :output-schema, with presa.schema (default true).
      Invalid arguments are an isError result the model can read; invalid
      output is an internal error.
  - :state-secret  the key that signs requestState tokens, a string or bytes
      of at least 32 bytes (default: random for this server map, so tokens
      do not survive a restart or reach another instance)
  - :state-ttl-ms  how long a requestState token stays valid (default 300000)
  - :list-changed  true to advertise listChanged for tools, prompts and
      resources and serve subscriptions/listen; then `list-changed!` tells
      listening clients that a list changed
  - :subscribe  true to advertise resources.subscribe, so clients can listen
      for `resource-updated!` on resources they name
  - :logging  true to advertise the logging capability, so `log!` sends
      messages to clients that ask for them
  - :log-level  the level an initialize-era session logs at until the client
      sends logging/setLevel (default \"info\"); 2026-07-28 clients choose a
      level on each request, and get no messages without one

  A completion function gets the value typed so far, the other arguments the
  client has filled in, and the context, and returns a sequence of strings or
  a map {\"values\" [...] \"total\" n \"hasMore\" b}. At most 100 values are
  sent."
  [{:keys [name version title instructions tools resources resource-templates prompts
           versions page-size cache logging log-level validate? state-secret state-ttl-ms
           list-changed subscribe]
    :or {versions [protocol/latest-version] log-level "info" validate? true state-ttl-ms 300000}}]
  (when-not (and (string? name) (string? version))
    (throw (ex-info "A server needs string :name and :version" {:kind :invalid-input})))
  (when-let [unknown (seq (remove protocol/known-version? versions))]
    (throw (ex-info (str "Unknown protocol versions " (vec unknown))
                    {:kind :invalid-input :versions (vec unknown)})))
  (when (empty? versions)
    (throw (ex-info "A server needs at least one protocol version" {:kind :invalid-input})))
  (when (and page-size (not (pos-int? page-size)))
    (throw (ex-info ":page-size must be a positive integer" {:kind :invalid-input})))
  (when-not (protocol/log-level-rank log-level)
    (throw (ex-info (str ":log-level must be one of " protocol/log-levels) {:kind :invalid-input})))
  (when (and (:scope cache) (not (#{"private" "public"} (:scope cache))))
    (throw (ex-info ":cache :scope must be \"private\" or \"public\"" {:kind :invalid-input})))
  (let [[tools tool-order] (index "tool" :name (map tool-def tools))
        [resources resource-order] (index "resource" :uri (map resource-def resources))
        templates (mapv template-def resource-templates)
        [prompts prompt-order] (index "prompt" :name (map prompt-def prompts))]
    (index "resource template" :uri-template templates)
    {:info (cond-> {"name" name "version" version}
             title (assoc "title" title))
     :instructions instructions
     ;; the definitions, which add-tool! and friends change while serving;
     ;; each request reads one snapshot (see `snapshot`)
     :catalog (atom {:tools tools
                     :tool-order tool-order
                     :resources resources
                     :resource-order resource-order
                     :templates templates
                     :prompts prompts
                     :prompt-order prompt-order
                     :catalog-version {"tools" 0 "resources" 0 "prompts" 0}})
     :page-size page-size
     :logging (boolean logging)
     :list-changed (boolean list-changed)
     :subscribe (boolean subscribe)
     ;; open subscriptions/listen streams, by a key of their own
     :listeners (atom {})
     :validate? (boolean validate?)
     :state-secret (if state-secret
                     (request-state/secret-bytes state-secret)
                     (request-state/random-secret))
     :state-ttl-ms state-ttl-ms
     :log-level log-level
     :cache {:ttl-ms (or (:ttl-ms cache) 0) :scope (or (:scope cache) "private")}
     ;; newest first, as presa.protocol/versions orders them
     :versions (filterv (set versions) protocol/versions)}))

(defn- snapshot
  "srv with the current definitions merged in, read once."
  [srv]
  (if-let [c (:catalog srv)] (merge srv @c) srv))

(defn- stateless-versions [srv] (filterv protocol/stateless? (:versions srv)))
(defn- legacy-versions [srv] (filterv (complement protocol/stateless?) (:versions srv)))

(defn- completions? [srv]
  (boolean (some :complete (concat (:templates srv) (vals (:prompts srv))))))

(defn- capabilities [srv]
  (cond-> {}
    (seq (:tools srv)) (assoc "tools" (if (:list-changed srv) {"listChanged" true} {}))
    (or (seq (:resources srv)) (seq (:templates srv)))
    (assoc "resources" (cond-> {}
                         (:list-changed srv) (assoc "listChanged" true)
                         (:subscribe srv) (assoc "subscribe" true)))
    (seq (:prompts srv)) (assoc "prompts" (if (:list-changed srv) {"listChanged" true} {}))
    (completions? srv) (assoc "completions" {})
    (:logging srv) (assoc "logging" {})))

;; Shaping results by revision

(def ^:private cached-methods
  #{"server/discover" "tools/list" "resources/list" "resources/templates/list"
    "resources/read" "prompts/list"})

(def ^:private input-methods
  "The methods that may answer input_required."
  #{"tools/call" "resources/read" "prompts/get"})

(defn- now-ms [] (System/currentTimeMillis))

(defn- input-required-result
  "An input_required result for a 2026-07-28 request, after checking that
  the client declared what the requests need."
  [srv context method params {:keys [requests state]}]
  (when-not (input-methods method)
    (throw (error :internal-error "Input-required result is not permitted for this operation")))
  (when (and (empty? requests) (nil? state))
    (throw (error :internal-error "Invalid input-required result")))
  (when-let [missing (input/missing-capabilities requests (:client-capabilities context))]
    (throw (error :missing-client-capability "Missing required client capability"
                  {"requiredCapabilities" missing})))
  (cond-> {"resultType" "input_required"}
    (seq requests) (assoc "inputRequests" requests)
    (some? state) (assoc "requestState"
                         (request-state/sign (:state-secret srv) method params state
                                             (:state-ttl-ms srv) (now-ms)))))

(defn- shape
  "A result as the revision of context sends it."
  [srv context method params result]
  (cond
    (not (protocol/stateless? (:version context)))
    result

    (input-required? result)
    (-> (input-required-result srv context method params (::input-required result))
        (assoc "_meta" {(:server-info protocol/meta-keys) (:info srv)}))

    :else
    (cond-> (-> result
                (assoc "resultType" "complete")
                (update "_meta" (fnil assoc {}) (:server-info protocol/meta-keys) (:info srv)))
      (cached-methods method) (assoc "ttlMs" (if (or (seq (:input-responses context)) (some? (:state context)))
                                               ;; a result that took input is not one to reuse
                                               0
                                               (get-in srv [:cache :ttl-ms]))
                                     "cacheScope" (get-in srv [:cache :scope])))))

;; Pagination

(def ^:private list-kinds
  {"tools/list" "tools" "resources/list" "resources"
   "resources/templates/list" "resources" "prompts/list" "prompts"})

(defn- encode-cursor [method offset version]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.getBytes (str "presa1|" method "|" offset "|" version) "UTF-8")))

(defn- decode-cursor
  "[offset catalog-version] from a cursor for method."
  [method cursor]
  (let [invalid #(throw (error :invalid-params "Invalid pagination cursor"))]
    (when-not (string? cursor)
      (throw (error :invalid-params "Cursor must be a string")))
    (when-not (<= (count cursor) 512) (invalid))
    (let [decoded (try (String. (.decode (Base64/getUrlDecoder) ^String cursor) "UTF-8")
                       (catch Exception _ (invalid)))
          [prefix m offset version] (str/split decoded #"\|" 4)
          offset (when (and offset (re-matches #"\d{1,9}" offset)) (parse-long offset))
          version (when (and version (re-matches #"\d{1,18}" version)) (parse-long version))]
      (if (and (= "presa1" prefix) (= method m) offset version)
        [offset version]
        (invalid)))))

(defn- page
  "[items next-cursor] for one page of items."
  [srv method params items]
  (let [current (get-in srv [:catalog-version (list-kinds method)] 0)
        [offset version] (if (contains? params "cursor")
                           (decode-cursor method (get params "cursor"))
                           [0 current])
        size (or (:page-size srv) (count items))
        items (vec items)]
    ;; a cursor from before the list changed would skip or repeat items
    (when (or (not= version current) (> offset (count items)))
      (throw (error :invalid-params "Pagination cursor has expired")))
    (let [end (min (count items) (+ offset size))]
      [(subvec items offset end)
       (when (< end (count items)) (encode-cursor method end current))])))

(defn- paged [srv method params k items]
  (let [[items cursor] (page srv method params items)]
    (cond-> {k items}
      cursor (assoc "nextCursor" cursor))))

;; Wire definitions

(defn- wire [m fields]
  (reduce (fn [acc [k wire-key]]
            (if-some [v (get m k)] (assoc acc wire-key v) acc))
          {}
          fields))

(defn- wire-tool [tool]
  (merge {"inputSchema" {"type" "object"}}
         (wire tool [[:name "name"] [:title "title"] [:description "description"]
                     [:input-schema "inputSchema"] [:output-schema "outputSchema"]
                     [:annotations "annotations"] [:meta "_meta"]])))

(defn- wire-resource [resource]
  (wire resource [[:uri "uri"] [:name "name"] [:title "title"] [:description "description"]
                  [:mime-type "mimeType"] [:size "size"] [:annotations "annotations"]
                  [:meta "_meta"]]))

(defn- wire-template [template]
  (wire template [[:uri-template "uriTemplate"] [:name "name"] [:title "title"]
                  [:description "description"] [:mime-type "mimeType"]
                  [:annotations "annotations"] [:meta "_meta"]]))

(defn- wire-prompt [prompt]
  (cond-> (wire prompt [[:name "name"] [:title "title"] [:description "description"]
                        [:meta "_meta"]])
    (seq (:arguments prompt))
    (assoc "arguments" (mapv #(wire % [[:name "name"] [:title "title"]
                                       [:description "description"] [:required "required"]])
                             (:arguments prompt)))))

;; Methods

(defn- server-discover [srv _params _context]
  ;; serverInfo travels in the result's _meta, which `shape` adds
  (cond-> {"supportedVersions" (:versions srv)
           "capabilities" (capabilities srv)}
    (:instructions srv) (assoc "instructions" (:instructions srv))))

(defn- initialize-result [srv version]
  (cond-> {"protocolVersion" version
           "capabilities" (capabilities srv)
           "serverInfo" (:info srv)}
    (:instructions srv) (assoc "instructions" (:instructions srv))))

(defn- arguments-map [params k method]
  (let [arguments (get params k {})]
    (when-not (map? arguments)
      (throw (error :invalid-params (str method " arguments must be an object"))))
    arguments))

(defn- required-name [params k method]
  (let [v (get params k)]
    (when-not (and (string? v) (not (str/blank? v)))
      (throw (error :invalid-params (str method " requires a string " k))))
    v))


(declare client-request!)

(def ^:private max-input-rounds 8)

(defn- run-handler
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
          (let [{:keys [requests state]} (::input-required v)]
            (when (> round max-input-rounds)
              (throw (error :internal-error (str "The handler asked for input more than " max-input-rounds " times"))))
            (recur (assoc context
                          :state state
                          :input-responses (into {}
                                                 (map (fn [[id {:strs [method params]}]]
                                                        [id (client-request! context method params)]))
                                                 requests))
                   (inc round))))))))

(defn- object-schema? [schema]
  (and (map? schema) (= "object" (get schema "type"))))

(defn- era-tool?
  "Whether the revision of context can carry tool: initialize-era revisions
  require object schemas for inputSchema and outputSchema, which
  2026-07-28 does not."
  [context tool]
  (or (protocol/stateless? (:version context))
      (and (or (nil? (:input-schema tool)) (object-schema? (:input-schema tool)))
           (or (nil? (:output-schema tool)) (object-schema? (:output-schema tool))))))

(defn- tools-list [srv params context]
  (paged srv "tools/list" params "tools"
         (->> (:tool-order srv)
              (map (:tools srv))
              (filter #(era-tool? context %))
              (map wire-tool))))

(defn- tools-call [srv params context]
  (let [tool-name (required-name params "name" "tools/call")
        arguments (arguments-map params "arguments" "tools/call")
        tool (or (let [t (get-in srv [:tools tool-name])] (when (and t (era-tool? context t)) t))
                 (throw (error :invalid-params (str "Unknown tool: " tool-name))))]
    (if-let [invalid (and (:validate? srv) (:input-schema tool)
                          (schema/validate arguments (:input-schema tool)))]
      (tool-error (str "Invalid arguments " (schema/describe invalid)))
      (let [v (try
                (run-handler context #((:handler tool) arguments %))
                (catch Exception e
                  (if (:presa/error (ex-data e))
                    (throw e)
                    (tool-error (or (ex-message e) (str (class e)))))))
            result (if (input-required? v) v (tool-result v))]
        (when (and (:validate? srv) (:output-schema tool) (not (input-required? result))
                   (not (get result "isError")))
          (if-not (contains? result "structuredContent")
            (throw (error :internal-error "Tool omitted structured output required by its output schema"))
            (when-let [invalid (schema/validate (get result "structuredContent") (:output-schema tool))]
              (throw (error :internal-error (str "Tool output failed schema validation " (schema/describe invalid)))))))
        result))))

(defn- resources-list [srv params _context]
  (paged srv "resources/list" params "resources"
         (map (comp wire-resource (:resources srv)) (:resource-order srv))))

(defn- templates-list [srv params _context]
  (paged srv "resources/templates/list" params "resourceTemplates"
         (map wire-template (:templates srv))))

(defn- contents
  "A resource handler's return value as resources/read contents."
  [uri mime-type v]
  (cond
    (and (map? v) (contains? v "contents")) (vec (get v "contents"))
    (map? v) [v]
    (and (sequential? v) (every? map? v)) (vec v)
    :else [(content/resource-contents uri v {:mime-type mime-type})]))

(defn- not-found [uri]
  (error :invalid-params "Resource not found" {"uri" uri}))

(defn- resources-read [srv params context]
  (let [uri (required-name params "uri" "resources/read")]
    (when-not (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" uri)
      (throw (error :invalid-params "resources/read uri must be an absolute URI")))
    (if-let [resource (get-in srv [:resources uri])]
      (let [v (run-handler context (:handler resource))]
        (if (input-required? v) v {"contents" (contents uri (:mime-type resource) v)}))
      (if-let [[template variables] (some (fn [t] (when-let [vs ((::match t) uri)] [t vs]))
                                          (:templates srv))]
        (let [v (run-handler (assoc context :uri uri) #((:handler template) variables %))]
          (cond
            (nil? v) (throw (not-found uri))
            (input-required? v) v
            :else {"contents" (contents uri (:mime-type template) v)}))
        (throw (not-found uri))))))

(defn- prompts-list [srv params _context]
  (paged srv "prompts/list" params "prompts" (map (comp wire-prompt (:prompts srv)) (:prompt-order srv))))

(defn- message [m]
  (cond
    (string? m) {"role" "user" "content" (content/text m)}
    (and (map? m) (contains? m "role") (contains? m "content")) m
    :else (throw (ex-info "A prompt message is a string or a map with role and content" {:message m}))))

(defn- prompts-get [srv params context]
  (let [prompt-name (required-name params "name" "prompts/get")
        arguments (arguments-map params "arguments" "prompts/get")
        prompt (or (get-in srv [:prompts prompt-name])
                   (throw (error :invalid-params (str "Unknown prompt: " prompt-name))))]
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

(defn- completion-result [v]
  (let [{:strs [values total hasMore]} (if (map? v) v {"values" v})
        values (vec (map str values))
        sent (vec (take 100 values))]
    {"completion" (cond-> {"values" sent}
                    (some? total) (assoc "total" total)
                    (and (nil? total) (> (count values) 100)) (assoc "total" (count values))
                    (some? hasMore) (assoc "hasMore" (boolean hasMore))
                    (and (nil? hasMore) (> (count values) 100)) (assoc "hasMore" true))}))

(defn- completion-complete [srv params context]
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
      (if-let [f (get-in target [:complete arg-name])]
        (completion-result (f value others context))
        (completion-result [])))))

(defn- ping [_ _ _] {})

(declare notify!)

(def ^:private flags
  {"toolsListChanged" "tools" "promptsListChanged" "prompts" "resourcesListChanged" "resources"})

(defn- subscriptions-listen
  "Hold a subscriptions/listen request open: acknowledge what the server
  will send, deliver matching notifications, and answer when the server
  ends the stream (a cancelled stream gets no answer)."
  [srv params context]
  (let [requested (get params "notifications")
        uris (get requested "resourceSubscriptions")
        id (:request-id context)
        sub-key (:subscription-id protocol/meta-keys)]
    (when-not (map? requested)
      (throw (error :invalid-params "subscriptions/listen requires a notifications object")))
    (doseq [k (keys flags)]
      (when (and (contains? requested k) (not (boolean? (get requested k))))
        (throw (error :invalid-params (str k " must be a boolean")))))
    (when (and (contains? requested "resourceSubscriptions")
               (not (and (sequential? uris) (<= (count uris) 1000)
                         (every? #(and (string? %) (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" %)) uris))))
      (throw (error :invalid-params "resourceSubscriptions must be a list of absolute URIs")))
    (let [caps (capabilities srv)
          kinds (set (for [[flag kind] flags
                           :when (and (true? (get requested flag))
                                      (get-in caps [kind "listChanged"]))]
                       kind))
          uris (when (and (:subscribe srv) (seq uris)) (vec uris))
          ack (cond-> (into {} (for [[flag kind] flags :when (kinds kind)] [flag true]))
                uris (assoc "resourceSubscriptions" uris))
          tag {sub-key id}
          listener-key (Object.)
          acked (promise)]
      ;; Register before acknowledging, so a stream acknowledged is a stream
      ;; end-subscriptions! can end; events wait for the acknowledgement,
      ;; which stays the first message on the stream.
      (swap! (:listeners srv) assoc listener-key
             {:kinds kinds
              :uris uris
              :send (fn [method params]
                      (deref acked 5000 nil)
                      (notify! context method (assoc params "_meta" tag)))
              :stop (:stopped context)})
      (try
        (notify! context "notifications/subscriptions/acknowledged" {"notifications" ack "_meta" tag})
        (finally (deliver acked true)))
      (try
        @(:stopped context)
        {"_meta" tag}
        (finally
          (swap! (:listeners srv) dissoc listener-key))))))

(def ^:private method-table
  "Each method: its function, the capability the server must have, and
  whether the initialize-era revisions serve it."
  {"server/discover" {:f server-discover :legacy? false}
   ;; 2026-07-28 removed ping; initialize-era sessions still have it
   "ping" {:f ping :legacy? true :stateless? false}
   "tools/list" {:f tools-list :capability "tools" :legacy? true}
   "tools/call" {:f tools-call :capability "tools" :legacy? true}
   "resources/list" {:f resources-list :capability "resources" :legacy? true}
   "resources/templates/list" {:f templates-list :capability "resources" :legacy? true}
   "resources/read" {:f resources-read :capability "resources" :legacy? true}
   "prompts/list" {:f prompts-list :capability "prompts" :legacy? true}
   "prompts/get" {:f prompts-get :capability "prompts" :legacy? true}
   "completion/complete" {:f completion-complete :capability "completions" :legacy? true}
   "subscriptions/listen" {:f subscriptions-listen :capability ::subscriptions :legacy? false}})

(defn- method-fn [srv method legacy?]
  (let [{:keys [f capability] :as m} (get method-table method)]
    (if (and m
             (if legacy? (:legacy? m) (:stateless? m true))
             (cond
               (nil? capability) true
               (= ::subscriptions capability) (or (:list-changed srv) (:subscribe srv))
               :else (contains? (capabilities srv) capability)))
      f
      (throw (error :method-not-found (str "Method not found: " method))))))

;; Resolving a request's revision

(def ^:private meta-key
  "The syntax of a _meta key: an optional dotted prefix ending in /, then a
  name."
  #"^(?:(?:[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?)(?:\.(?:[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?))*/)?(?:[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)?$")

(defn- bounded-id?
  "A string of at most 256 bytes or an int64 integer: what request ids and
  progress tokens may be."
  [v]
  (or (and (string? v) (<= (count (.getBytes ^String v "UTF-8")) 256))
      (and (integer? v) (<= Long/MIN_VALUE v Long/MAX_VALUE))))

(defn- request-meta [params]
  (let [m (get params "_meta" {})]
    (when-not (map? m)
      (throw (error :invalid-params "_meta must be an object")))
    m))

(defn- stateless-context
  "The context of a request that carries its version in _meta, or a protocol
  error."
  [srv meta]
  (let [{:keys [protocol-version client-info client-capabilities log-level]} protocol/meta-keys
        version (get meta protocol-version)
        supported (stateless-versions srv)
        level (get meta log-level)
        info (get meta client-info)]
    (when-not (every? #(and (string? %) (re-matches meta-key %)) (keys meta))
      (throw (error :invalid-params "_meta contains an invalid key or non-JSON value")))
    (when (and (contains? meta "progressToken") (not (bounded-id? (get meta "progressToken"))))
      (throw (error :invalid-params "progressToken must be a string of at most 256 bytes or an int64 integer")))
    (when (and (contains? meta log-level) (not (protocol/log-level-rank level)))
      (throw (error :invalid-params "Invalid MCP request log level")))
    (when-not (string? version)
      (throw (error :invalid-params (str protocol-version " must be a string"))))
    (when-not (some #{version} supported)
      (throw (error :unsupported-protocol-version "Unsupported protocol version"
                    {"requested" version "supported" (:versions srv)})))
    (when (and (contains? meta client-info)
               (not (and (map? info) (string? (get info "name")) (string? (get info "version")))))
      (throw (error :invalid-params "Client info must include string name and version")))
    (when-not (contains? meta client-capabilities)
      (throw (error :invalid-params "Missing required client capabilities metadata")))
    (when-not (map? (get meta client-capabilities))
      (throw (error :invalid-params "Client capabilities must be an object")))
    {:version version
     :client-info info
     :client-capabilities (get meta client-capabilities)
     :log-level level
     :meta meta}))

(defn- input-admission
  "The :input-responses and :state of a 2026-07-28 retry, checked."
  [srv method params]
  (let [responses (get params "inputResponses")
        token (get params "requestState")]
    (when (contains? params "inputResponses")
      (when-not (map? responses)
        (throw (error :invalid-params "inputResponses must be an object")))
      (when-not (and (every? string? (keys responses)) (every? map? (vals responses)))
        (throw (error :invalid-params "inputResponses must map string IDs to bare result objects"))))
    (when (and (contains? params "requestState") (not (string? token)))
      (throw (error :invalid-params "requestState must be a string")))
    (when (and (or (contains? params "inputResponses") (contains? params "requestState"))
               (not (input-methods method)))
      (throw (error :invalid-params (str method " does not take inputResponses or requestState"))))
    {:input-responses (or responses {})
     :state (when (string? token)
              (try (request-state/verify (:state-secret srv) method params token (now-ms))
                   (catch clojure.lang.ExceptionInfo _
                     (throw (error :invalid-params "Invalid or expired request state")))))}))

(defn- negotiate
  "The version to answer initialize with: the one the client asked for when
  the server serves it, else the newest initialize-era one it serves."
  [srv requested]
  (let [legacy (legacy-versions srv)]
    (or (some #{requested} legacy) (first legacy))))

(defn- initialize
  "Settle a session from an initialize request: [session respond]. With
  :list-changed, the session hears list changes through notify for as long
  as it lasts (see `close-session!`)."
  [srv params notify]
  (let [requested (get params "protocolVersion")
        capabilities (get params "capabilities")
        client-info (get params "clientInfo")]
    (when (empty? (legacy-versions srv))
      (throw (error :invalid-request
                    (str "This server does not serve initialize. Supported versions: "
                         (str/join ", " (:versions srv)))
                    {"supported" (:versions srv)})))
    (when-not (and (string? requested) (map? capabilities) (map? client-info))
      (throw (error :invalid-params
                    "initialize requires protocolVersion, capabilities and clientInfo")))
    (let [version (negotiate srv requested)
          session-id (str (java.util.UUID/randomUUID))]
      (when (and (:list-changed srv) notify)
        (swap! (:listeners srv) assoc [session-id :list-changed]
               {:kinds #{"tools" "prompts" "resources"}
                :send (fn [method params] (notify (rpc/notification method (not-empty params))))}))
      [{:version version :client-info client-info :client-capabilities capabilities
        :log-level (:log-level srv) :session-id session-id}
       (fn [] (initialize-result srv version))])))

;; Dispatch

(defn- error-response [id e]
  (let [{:keys [code message data]} (:presa/error (ex-data e))]
    (rpc/error id code message data)))

(defn- request
  "[session respond] for a request with an id."
  [srv session {:strs [id method params] :as message} extra]
  (let [srv (snapshot srv)]
    (when-not (bounded-id? id)
      (throw (error :invalid-request "Request id must be a string of at most 256 bytes or an int64 integer")))
    (when (and (contains? message "params") (not (map? params)))
      (throw (error :invalid-params "Request params must be an object")))
    (let [extra (assoc extra ::logging (:logging srv))
          params (or params {})
          meta (request-meta params)
          version-key (:protocol-version protocol/meta-keys)]
      (cond
        (contains? meta version-key)
        (let [context (merge (stateless-context srv meta) extra {:request-id id})
              f (method-fn srv method false)
              context (merge context (input-admission srv method params))]
          [session (fn [] (shape srv context method params (f srv params context)))])

        (= "initialize" method)
        (initialize srv params (:notify extra))

        (and (= "ping" method) (seq (legacy-versions srv)))
        [session (constantly {})]

        (and (:version session) (#{"resources/subscribe" "resources/unsubscribe"} method))
        (let [uri (get params "uri")]
          (when-not (:subscribe srv)
            (throw (error :method-not-found (str "Method not found: " method))))
          (when-not (and (string? uri) (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" uri))
            (throw (error :invalid-params (str method " requires an absolute uri"))))
          (if (= "resources/subscribe" method)
            (swap! (:listeners srv) assoc [(:session-id session) uri]
                   {:kinds #{}
                    :uris [uri]
                    :send (let [notify (:notify extra)]
                            (fn [method params] (notify (rpc/notification method (not-empty params)))))})
            (swap! (:listeners srv) dissoc [(:session-id session) uri]))
          [session (constantly {})])

        (and (:version session) (= "logging/setLevel" method))
        (let [level (get params "level")]
          (when-not (:logging srv)
            (throw (error :method-not-found (str "Method not found: " method))))
          (when-not (protocol/log-level-rank level)
            (throw (error :invalid-params (str "level must be one of " (str/join ", " protocol/log-levels)))))
          [(assoc session :log-level level) (constantly {})])

        (:version session)
        (let [_ (when (or (contains? params "inputResponses") (contains? params "requestState"))
                  (throw (error :invalid-params "Multi round-trip inputs are not supported on this revision")))
              context (merge session extra {:meta meta :request-id id})
              f (method-fn srv method true)]
          [session (fn [] (f srv params context))])

        :else
        (throw (error :invalid-params (str "Missing required " version-key " metadata")))))))

(defn- valid-notification?
  "Whether a notification's params and _meta have the right shape. A
  notification is never answered, so one that fails is ignored."
  [{:strs [params] :as message}]
  (and (or (not (contains? message "params")) (map? params))
       (let [meta (get params "_meta" {})]
         (and (map? meta)
              (every? #(and (string? %) (re-matches meta-key %)) (keys meta))))))

(defn- cancel-target
  "The request id a well-formed notifications/cancelled names, or nil."
  [message]
  (when (and (= "notifications/cancelled" (get message "method"))
             (valid-notification? message))
    (let [{:strs [requestId reason] :as params} (get message "params")]
      (when (and (bounded-id? requestId)
                 (or (not (contains? params "reason")) (string? reason)))
        requestId))))

(defn prepare
  "Read one message against session, the connection's state ({} for a new
  connection). Returns a map:

  - :session  the session for the next message
  - :respond  a function of no arguments that does the work, such as running
              a tool, and returns the response map, or nil when the message
              needs none (a notification, a response from the client, or a
              request cancelled before it finished). It never throws.
  - :request-id and :cancel!  for a request: its id, and a function that
              marks it cancelled, so :respond returns nil and the handler's
              (:cancelled? context) returns true
  - :cancel   for notifications/cancelled: the id of the request to cancel

  Settling the session is cheap and happens here; a transport reads the next
  message only after prepare returns, and may run :respond on another thread.

  opts:
  - :notify  (fn [notification]) sends a notification map to the client
             while the request runs; without it, notifications are dropped
  - :request (fn [method params timeout-ms]) sends the client a request and
             returns its response map; see `client-request!`

  A handler's context holds :version, :client-info, :client-capabilities,
  :meta (the request's _meta), :request-id, :log-level, :notify and
  :cancelled?, and for a resource template, :uri; see `notify!`,
  `progress!` and `log!`."
  ([srv session message] (prepare srv session message {}))
  ([srv session message {:keys [notify] send-request :request}]
   (case (rpc/kind message)
     :request
     (let [id (get message "id")
           cancelled (atom false)
           stopped (promise)
           extra {:notify (or notify (fn [_]))
                  :request send-request
                  :server srv
                  :stopped stopped
                  :cancelled? (fn [] @cancelled)}
           fail (fn [e]
                  (if (:presa/error (ex-data e))
                    (error-response id e)
                    (rpc/error id :internal-error (str "Internal error: " (ex-message e)))))
           prepared (try
                      (let [[session' respond] (request srv session message extra)]
                        {:session session'
                         :respond (fn []
                                    (let [response (try (rpc/result id (respond))
                                                        (catch Exception e (fail e)))]
                                      (when-not @cancelled response)))})
                      (catch Exception e
                        (let [response (fail e)]
                          {:session session :respond (constantly response)})))]
       (assoc prepared
              :request-id id
              :method (get message "method")
              :cancel! (fn [] (reset! cancelled true) (deliver stopped :cancelled))))

     ;; notifications/initialized and the rest need no answer; responses to
     ;; requests presa never sent are dropped.
     (:notification :response :error)
     (cond-> {:session session :respond (constantly nil)}
       (cancel-target message) (assoc :cancel (cancel-target message)))

     :invalid
     (let [id (when (map? message) (get message "id"))
           id (when (or (string? id) (integer? id)) id)]
       {:session session
        :respond (constantly (rpc/error id :invalid-request "Invalid JSON-RPC 2.0 message"))}))))

(defn handle
  "Handle one message: [session response], where response is nil when the
  message needs none. See `prepare`."
  ([srv session message] (handle srv session message {}))
  ([srv session message opts]
   (let [{:keys [session respond]} (prepare srv session message opts)]
     [session (respond)])))

;; Notifications from handlers

(defn notify!
  "Send the client a notification while the request of context runs."
  [context method params]
  ((:notify context) (rpc/notification method params)))

(defn progress!
  "Report progress on the request of context, when the client asked for it
  with a progressToken in _meta. progress is a number that increases with
  each call; total and message are optional."
  ([context progress] (progress! context progress {}))
  ([context progress {:keys [total message]}]
   (when-let [token (get (:meta context) "progressToken")]
     (notify! context "notifications/progress"
              (cond-> {"progressToken" token "progress" progress}
                total (assoc "total" total)
                message (assoc "message" message))))))

(defn log!
  "Send the client a log message (notifications/message) from the request of
  context, when the server has :logging and level is at or above the level
  the client asked for. level is a string from presa.protocol/log-levels;
  data is any JSON value; logger optionally names the source. Returns true
  when the message was sent."
  ([context level data] (log! context level data nil))
  ([context level data logger]
   (let [rank (or (protocol/log-level-rank level)
                  (throw (ex-info (str "Unknown log level " level) {:kind :invalid-input :level level})))
         wanted (some-> (:log-level context) protocol/log-level-rank)]
     (if (and (::logging context) wanted (>= rank wanted))
       (do (notify! context "notifications/message"
                    (cond-> {"level" level "data" data}
                      logger (assoc "logger" logger)))
           true)
       false))))

(def ^:private client-request-capabilities
  {"roots/list" "roots"
   "sampling/createMessage" "sampling"
   "elicitation/create" "elicitation"})

(defn client-request!
  "Send the client a request from the handler of an initialize-era request,
  and return the result map: roots/list, sampling/createMessage,
  elicitation/create, or ping. Throws ex-info with :kind :protocol for an
  error response, :kind :unsupported when the client did not declare the
  capability the method needs, or the revision has no requests from server
  to client (2026-07-28 asks for input with input_required results instead),
  :kind :timeout, or :kind :closed.

  opts: :timeout-ms (default 60000)."
  ([context method params] (client-request! context method params {}))
  ([context method params {:keys [timeout-ms] :or {timeout-ms 60000}}]
   (when (or (protocol/stateless? (:version context)) (nil? (:request context)))
     (throw (ex-info (str "This connection cannot send " method " to the client")
                     {:kind :unsupported :method method})))
   (when-let [capability (get client-request-capabilities method)]
     (when-not (contains? (:client-capabilities context) capability)
       (throw (ex-info (str "The client did not declare the " capability " capability")
                       {:kind :unsupported :method method :capability capability}))))
   (let [params (if (and (= "2025-06-18" (:version context)) (= "elicitation/create" method)
                         (= "form" (get params "mode")))
                  (dissoc params "mode")
                  params)
         response ((:request context) method params timeout-ms)]
     (cond
       (= :presa.connection/closed response)
       (throw (ex-info "The connection closed before the client answered" {:kind :closed :method method}))

       (contains? response "error")
       (let [{:strs [code message data]} (get response "error")]
         (throw (ex-info (str "MCP error " code ": " message)
                         {:kind :protocol :code code :message message :data data})))

       :else (get response "result")))))

;; Subscriptions

(defn- server-of [srv-or-context]
  (if (contains? srv-or-context :listeners) srv-or-context (:server srv-or-context)))

(defn list-changed!
  "Tell clients listening for it that a list changed. kind is \"tools\",
  \"prompts\" or \"resources\". Takes the server, or a handler's context."
  [srv-or-context kind]
  (let [method (str "notifications/" kind "/list_changed")]
    (doseq [{:keys [kinds send]} (vals @(:listeners (server-of srv-or-context)))
            :when (contains? kinds kind)]
      (try (send method {}) (catch Exception _)))))

(defn resource-updated!
  "Tell clients listening to uri, or to a resource uri is under, that it
  changed. Takes the server, or a handler's context."
  [srv-or-context uri]
  (doseq [{:keys [uris send]} (vals @(:listeners (server-of srv-or-context)))
          :when (some #(or (= % uri) (str/starts-with? uri (str (str/replace % #"/$" "") "/"))) uris)]
    (try (send "notifications/resources/updated" {"uri" uri}) (catch Exception _))))

(defn close-session!
  "Forget what an initialize-era session listens to, when its connection
  ends. Transports call this."
  [srv session]
  (when-let [id (:session-id session)]
    (swap! (:listeners srv) (fn [m] (into {} (remove (fn [[k _]] (and (vector? k) (= id (first k))))) m)))))

(defn end-subscriptions!
  "End every open subscriptions/listen stream with its final result, as on
  shutdown."
  [srv]
  (doseq [{:keys [stop]} (vals @(:listeners srv)) :when stop]
    (deliver stop :ended)))

;; Changing a running server's definitions

(defn- change!
  "Apply f to the catalog, bump kind's version and tell listening clients."
  [srv kind f]
  (swap! (:catalog srv) (fn [c] (-> (f c) (update-in [:catalog-version kind] inc))))
  (when (:list-changed srv)
    (list-changed! srv kind))
  srv)

(defn- put-ordered [c m-key order-key k v]
  (cond-> (assoc-in c [m-key k] v)
    (not (contains? (get c m-key) k)) (update order-key conj k)))

(defn- drop-ordered [c m-key order-key k]
  (-> c
      (update m-key dissoc k)
      (update order-key (fn [ks] (filterv #(not= k %) ks)))))

(defn add-tool!
  "Add tool (a map as `server` takes) to a running server, replacing a tool
  of the same name in place. Clients listening for tool list changes are
  told when the server has :list-changed. Returns srv."
  [srv tool]
  (change! srv "tools" #(put-ordered % :tools :tool-order (:name tool) (tool-def tool))))

(defn remove-tool!
  "Remove the tool named name from a running server. Returns srv."
  [srv name]
  (change! srv "tools" #(drop-ordered % :tools :tool-order name)))

(defn add-resource!
  "Add a resource to a running server, replacing one with the same URI.
  Returns srv."
  [srv resource]
  (change! srv "resources" #(put-ordered % :resources :resource-order (:uri resource) (resource-def resource))))

(defn remove-resource!
  "Remove the resource at uri from a running server. Returns srv."
  [srv uri]
  (change! srv "resources" #(drop-ordered % :resources :resource-order uri)))

(defn add-resource-template!
  "Add a resource template to a running server, replacing one with the same
  URI template. Returns srv."
  [srv template]
  (let [t (template-def template)]
    (change! srv "resources"
             (fn [c] (update c :templates
                             (fn [ts]
                               (if (some #(= (:uri-template t) (:uri-template %)) ts)
                                 (mapv #(if (= (:uri-template t) (:uri-template %)) t %) ts)
                                 (conj ts t))))))))

(defn remove-resource-template!
  "Remove the resource template with uri-template. Returns srv."
  [srv uri-template]
  (change! srv "resources"
           (fn [c] (update c :templates (fn [ts] (filterv #(not= uri-template (:uri-template %)) ts))))))

(defn add-prompt!
  "Add a prompt to a running server, replacing one of the same name.
  Returns srv."
  [srv prompt]
  (change! srv "prompts" #(put-ordered % :prompts :prompt-order (:name prompt) (prompt-def prompt))))

(defn remove-prompt!
  "Remove the prompt named name from a running server. Returns srv."
  [srv name]
  (change! srv "prompts" #(drop-ordered % :prompts :prompt-order name)))

(defn tool
  "The current definition of the tool named name, or nil."
  [srv name]
  (get-in (snapshot srv) [:tools name]))

(defn resource
  "The current definition of the resource at uri, or nil."
  [srv uri]
  (get-in (snapshot srv) [:resources uri]))

(defn prompt
  "The current definition of the prompt named name, or nil."
  [srv name]
  (get-in (snapshot srv) [:prompts name]))
