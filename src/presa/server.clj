(ns presa.server
  "An MCP server as data, and request dispatch as pure functions.

  A server is a map built by `server` from a name, a version and tools. It
  serves MCP 2026-07-28 by default: every request carries its protocol
  version and client capabilities in `_meta`, and `server/discover` describes
  the server. Listing an initialize-era revision in `:versions` opts in to
  `initialize` as well, which Codex needs (see docs/hosts.md).

  Dispatch is in two steps so a transport can run tool calls concurrently:
  `prepare` reads one message against the connection's session and settles
  the session at once; the `:respond` function it returns does the work and
  builds the response. `handle` does both in one call."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]))

;; Tools

(defn- wire-tool
  "A tool's definition as tools/list sends it."
  [{:keys [name title description input-schema output-schema annotations]}]
  (cond-> {"name" name
           "inputSchema" (or input-schema {"type" "object"})}
    title (assoc "title" title)
    description (assoc "description" description)
    output-schema (assoc "outputSchema" output-schema)
    annotations (assoc "annotations" annotations)))

(defn- check-tool [tool]
  (when-not (and (map? tool) (string? (:name tool)) (not (str/blank? (:name tool))))
    (throw (ex-info "A tool needs a non-blank string :name" {:kind :invalid-input :tool tool})))
  (when-not (ifn? (:handler tool))
    (throw (ex-info (str "Tool " (:name tool) " needs a :handler function")
                    {:kind :invalid-input :tool (:name tool)})))
  tool)

(defn text
  "A tool result with one text content block."
  [s]
  {"content" [{"type" "text" "text" (str s)}] "isError" false})

(defn structured
  "A tool result carrying the map m as structuredContent, and as JSON text
  for clients that read only content."
  [m]
  {"content" [{"type" "text" "text" (json/write-str m :escape-slash false :escape-unicode false)}]
   "structuredContent" m
   "isError" false})

(defn tool-error
  "A tool result that reports a failure to the model: isError true, with
  message as its text. Use this for errors the model can act on; a JSON-RPC
  error is for a request the server could not take."
  [message]
  {"content" [{"type" "text" "text" (str message)}] "isError" true})

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
    :else (text (json/write-str v :escape-slash false :escape-unicode false))))

;; The server

(defn server
  "A server map from options:

  - :name, :version  the server's implementation name and version (required)
  - :title, :instructions  optional strings sent to clients
  - :tools  a sequence of tool maps, each with
      :name         the tool's name (required)
      :handler      (fn [arguments context]) (required); arguments is the
                    call's arguments map with string keys, context is
                    described at `prepare`; returns what `tool-result` takes,
                    or throws to report an error with isError true
      :description, :title, :input-schema, :output-schema, :annotations
                    sent in tools/list; :input-schema defaults to
                    {\"type\" \"object\"}
  - :versions  the revisions served, from presa.protocol/versions. Defaults
      to [presa.protocol/latest-version]. Include initialize-era revisions
      (presa.protocol/versions serves all) to answer initialize."
  [{:keys [name version title instructions tools versions]
    :or {versions [protocol/latest-version]}}]
  (when-not (and (string? name) (string? version))
    (throw (ex-info "A server needs string :name and :version" {:kind :invalid-input})))
  (when-let [unknown (seq (remove protocol/known-version? versions))]
    (throw (ex-info (str "Unknown protocol versions " (vec unknown))
                    {:kind :invalid-input :versions (vec unknown)})))
  (when (empty? versions)
    (throw (ex-info "A server needs at least one protocol version" {:kind :invalid-input})))
  (let [tools (mapv check-tool tools)
        names (map :name tools)]
    (when-not (apply distinct? nil names)
      (throw (ex-info "Tool names must be distinct" {:kind :invalid-input :names (vec names)})))
    {:info (cond-> {"name" name "version" version}
             title (assoc "title" title))
     :instructions instructions
     :tools (into {} (map (juxt :name identity)) tools)
     :tool-order (vec names)
     ;; newest first, as presa.protocol/versions orders them
     :versions (filterv (set versions) protocol/versions)}))

(defn- stateless-versions [srv] (filterv protocol/stateless? (:versions srv)))
(defn- legacy-versions [srv] (filterv (complement protocol/stateless?) (:versions srv)))

(defn- capabilities [srv]
  (cond-> {}
    (seq (:tools srv)) (assoc "tools" {})))

;; Results and errors

(defn- protocol-error
  "An error that becomes a JSON-RPC error response."
  ([code message] (protocol-error code message nil))
  ([code message data]
   (ex-info message {:presa/error {:code code :message message :data data}})))

(defn- shape
  "A result as the revision of context sends it."
  [srv context result]
  (if (protocol/stateless? (:version context))
    (-> result
        (assoc "resultType" "complete")
        (update "_meta" (fnil assoc {}) (:server-info protocol/meta-keys) (:info srv)))
    result))

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

(defn- tools-list [srv _params _context]
  {"tools" (mapv (comp wire-tool (:tools srv)) (:tool-order srv))})

(defn- tools-call [srv params context]
  (let [tool-name (get params "name")
        arguments (get params "arguments" {})]
    (when-not (and (string? tool-name) (not (str/blank? tool-name)))
      (throw (protocol-error :invalid-params "tools/call requires a string name")))
    (when-not (map? arguments)
      (throw (protocol-error :invalid-params "tools/call arguments must be an object")))
    (let [tool (or (get-in srv [:tools tool-name])
                   (throw (protocol-error :invalid-params (str "Unknown tool: " tool-name))))]
      (try
        (tool-result ((:handler tool) arguments context))
        (catch Exception e
          (tool-error (or (ex-message e) (str (class e)))))))))

(def ^:private stateless-methods
  {"server/discover" server-discover
   "tools/list" tools-list
   "tools/call" tools-call
   "ping" (fn [_ _ _] {})})

(def ^:private legacy-methods
  {"tools/list" tools-list
   "tools/call" tools-call
   "ping" (fn [_ _ _] {})})

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
      (throw (protocol-error :invalid-params (str protocol-version " must be a string"))))
    (when-not (some #{version} supported)
      (throw (protocol-error :unsupported-protocol-version "Unsupported protocol version"
                             {"requested" version "supported" (:versions srv)})))
    (when-not (map? (get meta client-capabilities))
      (throw (protocol-error :invalid-params (str "Missing required " client-capabilities " metadata"))))
    {:version version
     :client-info (get meta client-info)
     :client-capabilities (get meta client-capabilities)
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
      (throw (protocol-error :invalid-request
                             (str "This server does not serve initialize. Supported versions: "
                                  (str/join ", " (:versions srv)))
                             {"supported" (:versions srv)})))
    (when-not (and (string? requested) (map? capabilities) (map? client-info))
      (throw (protocol-error :invalid-params
                             "initialize requires protocolVersion, capabilities and clientInfo")))
    (let [version (negotiate srv requested)]
      [{:version version :client-info client-info :client-capabilities capabilities}
       (fn [] (initialize-result srv version))])))

;; Dispatch

(defn- error-response [id e]
  (let [{:keys [code message data]} (:presa/error (ex-data e))]
    (rpc/error id code message data)))

(defn- request
  "[session respond] for a request with an id."
  [srv session {:strs [id method params]}]
  (let [params (if (map? params) params {})
        meta (request-meta params)
        version-key (:protocol-version protocol/meta-keys)]
    (cond
      (contains? meta version-key)
      (let [context (assoc (stateless-context srv meta) :request-id id)
            f (or (get stateless-methods method)
                  (throw (protocol-error :method-not-found (str "Method not found: " method))))]
        [session (fn [] (shape srv context (f srv params context)))])

      (= "initialize" method)
      (initialize srv params)

      (= "ping" method)
      [session (constantly {})]

      (:version session)
      (let [context (assoc session :meta meta :request-id id)
            f (or (get legacy-methods method)
                  (throw (protocol-error :method-not-found (str "Method not found: " method))))]
        [session (fn [] (f srv params context))])

      (seq (legacy-versions srv))
      (throw (protocol-error :invalid-request
                             (str "Request before initialize, and without " version-key " metadata")))

      :else
      (throw (protocol-error :invalid-params (str "Missing required " version-key " metadata"))))))

(defn prepare
  "Read one message against session, the connection's state ({} for a new
  connection). Returns {:session s :respond f}: s is the session for the next
  message, and f, a function of no arguments, returns the response map, or
  nil when the message needs none (a notification, or a response from the
  client). Settling the session is cheap; the work, such as running a tool,
  happens in f, which never throws.

  A tool handler's context holds :version, :client-info,
  :client-capabilities, :meta (the request's _meta) and :request-id."
  [srv session message]
  (case (rpc/kind message)
    :request
    (let [id (get message "id")]
      (try
        (let [[session' respond] (request srv session message)]
          {:session session'
           :respond (fn []
                      (try
                        (rpc/result id (respond))
                        (catch Exception e
                          (if (:presa/error (ex-data e))
                            (error-response id e)
                            (rpc/error id :internal-error (str "Internal error: " (ex-message e)))))))})
        (catch Exception e
          (let [response (if (:presa/error (ex-data e))
                           (error-response id e)
                           (rpc/error id :internal-error (str "Internal error: " (ex-message e))))]
            {:session session :respond (constantly response)}))))

    ;; notifications/initialized, notifications/cancelled and the rest need
    ;; no answer; responses to requests presa never sent are dropped.
    (:notification :response :error)
    {:session session :respond (constantly nil)}

    :invalid
    (let [id (when (map? message) (get message "id"))
          id (when (or (string? id) (integer? id)) id)]
      {:session session
       :respond (constantly (rpc/error id :invalid-request "Invalid JSON-RPC 2.0 message"))})))

(defn handle
  "Handle one message: [session response], where response is nil when the
  message needs none. See `prepare`."
  [srv session message]
  (let [{:keys [session respond]} (prepare srv session message)]
    [session (respond)]))
