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
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
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
           versions page-size cache logging log-level validate?]
    :or {versions [protocol/latest-version] log-level "info" validate? true}}]
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
     :tools tools
     :tool-order tool-order
     :resources resources
     :resource-order resource-order
     :templates templates
     :prompts prompts
     :prompt-order prompt-order
     :page-size page-size
     :logging (boolean logging)
     :validate? (boolean validate?)
     :log-level log-level
     :cache {:ttl-ms (or (:ttl-ms cache) 0) :scope (or (:scope cache) "private")}
     ;; newest first, as presa.protocol/versions orders them
     :versions (filterv (set versions) protocol/versions)}))

(defn- stateless-versions [srv] (filterv protocol/stateless? (:versions srv)))
(defn- legacy-versions [srv] (filterv (complement protocol/stateless?) (:versions srv)))

(defn- completions? [srv]
  (boolean (some :complete (concat (:templates srv) (vals (:prompts srv))))))

(defn- capabilities [srv]
  (cond-> {}
    (seq (:tools srv)) (assoc "tools" {})
    (or (seq (:resources srv)) (seq (:templates srv))) (assoc "resources" {})
    (seq (:prompts srv)) (assoc "prompts" {})
    (completions? srv) (assoc "completions" {})
    (:logging srv) (assoc "logging" {})))

;; Shaping results by revision

(def ^:private cached-methods
  #{"server/discover" "tools/list" "resources/list" "resources/templates/list"
    "resources/read" "prompts/list"})

(defn- shape
  "A result as the revision of context sends it."
  [srv context method result]
  (if (protocol/stateless? (:version context))
    (cond-> (-> result
                (assoc "resultType" "complete")
                (update "_meta" (fnil assoc {}) (:server-info protocol/meta-keys) (:info srv)))
      (cached-methods method) (assoc "ttlMs" (get-in srv [:cache :ttl-ms])
                                     "cacheScope" (get-in srv [:cache :scope])))
    result))

;; Pagination

(defn- encode-cursor [method offset]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.getBytes (str "presa1|" method "|" offset) "UTF-8")))

(defn- decode-cursor [method cursor]
  (let [invalid #(throw (error :invalid-params "Invalid cursor"))]
    (when-not (and (string? cursor) (<= (count cursor) 512)) (invalid))
    (let [decoded (try (String. (.decode (Base64/getUrlDecoder) ^String cursor) "UTF-8")
                       (catch Exception _ (invalid)))
          [prefix m offset] (str/split decoded #"\|" 3)
          offset (when (and offset (re-matches #"\d{1,9}" offset)) (parse-long offset))]
      (if (and (= "presa1" prefix) (= method m) offset)
        offset
        (invalid)))))

(defn- page
  "[items next-cursor] for one page of items."
  [srv method params items]
  (let [offset (if (contains? params "cursor") (decode-cursor method (get params "cursor")) 0)
        size (or (:page-size srv) (count items))
        items (vec items)]
    (when (> offset (count items))
      (throw (error :invalid-params "Invalid cursor")))
    (let [end (min (count items) (+ offset size))]
      [(subvec items offset end)
       (when (< end (count items)) (encode-cursor method end))])))

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
  (cond-> {"supportedVersions" (:versions srv)
           "capabilities" (capabilities srv)
           "serverInfo" (:info srv)}
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

(defn- tools-list [srv params _context]
  (paged srv "tools/list" params "tools" (map (comp wire-tool (:tools srv)) (:tool-order srv))))

(defn- tools-call [srv params context]
  (let [tool-name (required-name params "name" "tools/call")
        arguments (arguments-map params "arguments" "tools/call")
        tool (or (get-in srv [:tools tool-name])
                 (throw (error :invalid-params (str "Unknown tool: " tool-name))))]
    (if-let [invalid (and (:validate? srv) (:input-schema tool)
                          (schema/validate arguments (:input-schema tool)))]
      (tool-error (str "Invalid arguments " (schema/describe invalid)))
      (let [result (try
                     (tool-result ((:handler tool) arguments context))
                     (catch Exception e
                       (if (:presa/error (ex-data e))
                         (throw e)
                         (tool-error (or (ex-message e) (str (class e)))))))]
        (when (and (:validate? srv) (:output-schema tool) (not (get result "isError")))
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
    (if-let [resource (get-in srv [:resources uri])]
      {"contents" (contents uri (:mime-type resource) ((:handler resource) context))}
      (if-let [[template variables] (some (fn [t] (when-let [vs ((::match t) uri)] [t vs]))
                                          (:templates srv))]
        (let [v ((:handler template) variables (assoc context :uri uri))]
          (when (nil? v) (throw (not-found uri)))
          {"contents" (contents uri (:mime-type template) v)})
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
    (doseq [{arg :name required :required} (:arguments prompt)]
      (when (and required (not (contains? arguments arg)))
        (throw (error :invalid-params (str "Missing required argument: " arg)))))
    (let [v ((:handler prompt) arguments context)
          result (if (and (map? v) (contains? v "messages"))
                   (update v "messages" #(mapv message %))
                   {"messages" (mapv message (if (string? v) [v] v))})]
      (cond-> result
        (and (:description prompt) (not (contains? result "description")))
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

(def ^:private methods
  "Each method: its function, the capability the server must have, and
  whether the initialize-era revisions serve it."
  {"server/discover" {:f server-discover :legacy? false}
   "ping" {:f ping :legacy? true}
   "tools/list" {:f tools-list :capability "tools" :legacy? true}
   "tools/call" {:f tools-call :capability "tools" :legacy? true}
   "resources/list" {:f resources-list :capability "resources" :legacy? true}
   "resources/templates/list" {:f templates-list :capability "resources" :legacy? true}
   "resources/read" {:f resources-read :capability "resources" :legacy? true}
   "prompts/list" {:f prompts-list :capability "prompts" :legacy? true}
   "prompts/get" {:f prompts-get :capability "prompts" :legacy? true}
   "completion/complete" {:f completion-complete :capability "completions" :legacy? true}})

(defn- method-fn [srv method legacy?]
  (let [{:keys [f capability] :as m} (get methods method)]
    (if (and m
             (or (not legacy?) (:legacy? m))
             (or (nil? capability) (contains? (capabilities srv) capability)))
      f
      (throw (error :method-not-found (str "Method not found: " method))))))

;; Resolving a request's revision

(defn- request-meta [params]
  (let [m (get params "_meta")]
    (if (map? m) m {})))

(defn- stateless-context
  "The context of a request that carries its version in _meta, or a protocol
  error."
  [srv meta]
  (let [{:keys [protocol-version client-info client-capabilities]} protocol/meta-keys
        version (get meta protocol-version)
        supported (stateless-versions srv)]
    (when-not (string? version)
      (throw (error :invalid-params (str protocol-version " must be a string"))))
    (when-not (some #{version} supported)
      (throw (error :unsupported-protocol-version "Unsupported protocol version"
                    {"requested" version "supported" (:versions srv)})))
    (when-not (map? (get meta client-capabilities))
      (throw (error :invalid-params (str "Missing required " client-capabilities " metadata"))))
    {:version version
     :client-info (get meta client-info)
     :client-capabilities (get meta client-capabilities)
     :log-level (let [level (get meta (:log-level protocol/meta-keys))]
                  (when (protocol/log-level-rank level) level))
     :meta meta}))

(defn- negotiate
  "The version to answer initialize with: the one the client asked for when
  the server serves it, else the newest initialize-era one it serves."
  [srv requested]
  (let [legacy (legacy-versions srv)]
    (or (some #{requested} legacy) (first legacy))))

(defn- initialize
  "Settle a session from an initialize request: [session respond]."
  [srv params]
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
    (let [version (negotiate srv requested)]
      [{:version version :client-info client-info :client-capabilities capabilities
        :log-level (:log-level srv)}
       (fn [] (initialize-result srv version))])))

;; Dispatch

(defn- error-response [id e]
  (let [{:keys [code message data]} (:presa/error (ex-data e))]
    (rpc/error id code message data)))

(defn- request
  "[session respond] for a request with an id."
  [srv session {:strs [id method params]} extra]
  (let [extra (assoc extra ::logging (:logging srv))
        params (if (map? params) params {})
        meta (request-meta params)
        version-key (:protocol-version protocol/meta-keys)]
    (cond
      (contains? meta version-key)
      (let [context (merge (stateless-context srv meta) extra {:request-id id})
            f (method-fn srv method false)]
        [session (fn [] (shape srv context method (f srv params context)))])

      (= "initialize" method)
      (initialize srv params)

      (= "ping" method)
      [session (constantly {})]

      (and (:version session) (= "logging/setLevel" method))
      (let [level (get params "level")]
        (when-not (:logging srv)
          (throw (error :method-not-found (str "Method not found: " method))))
        (when-not (protocol/log-level-rank level)
          (throw (error :invalid-params (str "level must be one of " (str/join ", " protocol/log-levels)))))
        [(assoc session :log-level level) (constantly {})])

      (:version session)
      (let [context (merge session extra {:meta meta :request-id id})
            f (method-fn srv method true)]
        [session (fn [] (f srv params context))])

      (seq (legacy-versions srv))
      (throw (error :invalid-request
                    (str "Request before initialize, and without " version-key " metadata")))

      :else
      (throw (error :invalid-params (str "Missing required " version-key " metadata"))))))

(defn- cancel-target
  "The request id a notifications/cancelled message names, or nil."
  [message]
  (when (= "notifications/cancelled" (get message "method"))
    (let [id (get-in message ["params" "requestId"])]
      (when (or (string? id) (integer? id)) id))))

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

  A handler's context holds :version, :client-info, :client-capabilities,
  :meta (the request's _meta), :request-id, :log-level, :notify and
  :cancelled?, and for a resource template, :uri; see `notify!`,
  `progress!` and `log!`."
  ([srv session message] (prepare srv session message {}))
  ([srv session message {:keys [notify]}]
   (case (rpc/kind message)
     :request
     (let [id (get message "id")
           cancelled (atom false)
           extra {:notify (or notify (fn [_]))
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
       (assoc prepared :request-id id :cancel! (fn [] (reset! cancelled true))))

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
