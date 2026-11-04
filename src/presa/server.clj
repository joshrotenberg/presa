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
  (:require [clojure.string :as str]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.request-state :as request-state]
            [presa.schema :as schema]
            [presa.tasks :as tasks]
            [presa.server.core :as core :refer [emit! json-text]]
            [presa.server.catalog :refer [drop-ordered index prompt-def put-ordered resource-def
                                          snapshot template-def tool-def]]
            [presa.server.access :refer [may-read?]]
            [presa.server.dispatch :refer [cancel-target error-response request]]))

(defmacro ^:private defalias
  "Define name as the var target names, with its docstring and arglists."
  [name target]
  (let [m (meta (resolve target))]
    `(def ~(vary-meta name assoc :doc (:doc m) :arglists (list 'quote (:arglists m))) ~target)))

;; Errors and results

(defalias error core/error)
(defalias text core/text)
(defalias structured core/structured)
(defalias content core/content)
(defalias tool-error core/tool-error)
(defalias input-required core/input-required)
(defalias input-response core/input-response)

;; Definitions

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
      :keywordize-args?  overrides the server's :keywordize-args? for this
                tool
      Schemas may use keyword keys and values ({:type :object}); presa
      sends and validates them as JSON Schema with string keys.
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
      results against :output-schema (default true).
      Invalid arguments are an isError result the model can read; invalid
      output is an internal error.
  - :keywordize-args?  pass tool handlers their arguments with keyword
      keys, recursively, as clojure.walk/keywordize-keys does (default
      false: string keys). Arguments are validated before they are
      converted. A tool's own :keywordize-args? overrides this.
  - :validator  (fn [value schema]) -> nil, or a failure {:path [..]
      :keyword k :message m}; default presa.schema/validate, a subset of
      JSON Schema. presa.schema.networknt/validator gives full 2020-12
      validation on the JVM.
  - :state-secret  the key that signs requestState tokens, a string or bytes
      of at least 32 bytes (default: random for this server map, so tokens
      do not survive a restart or reach another instance)
  - :state-ttl-ms  how long a requestState token stays valid (default 300000)
  - :list-changed  true to advertise listChanged for tools, prompts and
      resources and serve subscriptions/listen; then `list-changed!` tells
      listening clients that a list changed
  - :subscribe  true to advertise resources.subscribe, so clients can listen
      for `resource-updated!` on resources they name
  - :max-subscriptions  the most subscriptions/listen streams open at once
      (default 256); another is refused with -32603, which HTTP sends as 503
  - :on-event  (fn [event]) for instrumentation: each event is a map with
      :event and its fields:
        :request/start     :method :request-id :version
        :request/stop      :method :request-id :duration-ms, :outcome
                           (:ok, :error with :error-code, or :cancelled)
        :request/rejected  :method :request-id :error-code, for a request
                           refused before it ran (bad _meta, unknown
                           method, and the like)
        :tool/error        :tool, :request-id (or :task-id for a task),
                           :exception, for a handler that threw (the
                           client gets an isError result)
      Exceptions the function throws are ignored.
  - :required-scopes  {[kind key] [scope ..]}, the scopes a component needs:
      kind is :tool, :prompt (keyed by name), :resource (by URI) or
      :resource-template (by URI template); the caller's scopes come from
      presa.auth over HTTP. A component the caller may not use is left out
      of lists, and a call to it is -32003 with data
      {\"error\" \"insufficient_scope\" \"scope\" ...}
  - :default-scopes  the scopes of a component without an entry (default [])
  - :tasks  options that turn on the Tasks extension
      (io.modelcontextprotocol/tasks): {:ttl-ms 3600000 :poll-interval-ms 250
      :max-tasks 1000}. A tool's :task policy (:sync, the default,
      :optional, :required, or (fn [params context]) returning one) decides
      whether a call from a client that declares Tasks becomes a task; see
      docs/tasks-extension.md
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
           versions page-size cache logging log-level validate? state-secret state-ttl-ms keywordize-args?
           list-changed subscribe max-subscriptions on-event required-scopes default-scopes validator]
    tasks-opts :tasks
    :or {versions [protocol/latest-version] log-level "info" validate? true state-ttl-ms 300000
         max-subscriptions 256}}]
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
    (let [srv {:info (cond-> {"name" name "version" version}
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
     :max-subscriptions max-subscriptions
     :on-event on-event
     :task-store (when tasks-opts (tasks/store tasks-opts))
     :required-scopes (or required-scopes {})
     :default-scopes (vec default-scopes)
     ;; open subscriptions/listen streams, by a key of their own
     :listeners (atom {})
     :validate? (boolean validate?)
     :keywordize-args? (boolean keywordize-args?)
     :validator (or validator schema/validate)
     :state-secret (if state-secret
                     (request-state/secret-bytes state-secret)
                     (request-state/random-secret))
     :state-ttl-ms state-ttl-ms
     :log-level log-level
     :cache {:ttl-ms (or (:ttl-ms cache) 0) :scope (or (:scope cache) "private")}
     ;; newest first, as presa.protocol/versions orders them
     :versions (filterv (set versions) protocol/versions)}]
      (when-let [st (:task-store srv)]
            ;; a task's changes reach the streams listening for it
            (reset! (:on-change st)
                    (fn [task]
                      (doseq [{:keys [task-ids scope send]} (vals @(:listeners srv))
                              :when (and (contains? task-ids (:id task)) (= scope (:scope task)))]
                        (try (send "notifications/tasks" (tasks/notification task)) (catch Exception _ nil))))))
      srv)))


;; Dispatch

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
  - :auth     the verified identity of the caller (presa.auth), which
             handlers see as (:auth context)
  - :session-notify  (fn [notification]) for what an initialize-era
             session hears outside any request (list changes, resource
             updates); defaults to :notify, which on stdio is the same
             connection

  A handler's context holds :version, :client-info, :client-capabilities,
  :meta (the request's _meta), :request-id, :log-level, :notify and
  :cancelled?, and for a resource template, :uri; see `notify!`,
  `progress!` and `log!`."
  ([srv session message] (prepare srv session message {}))
  ([srv session message {:keys [notify session-notify auth] send-request :request}]
   (case (rpc/kind message)
     :request
     (let [id (get message "id")
           cancelled (atom false)
           stopped (promise)
           extra {:notify (or notify (fn [_]))
                  :session-notify session-notify
                  :auth auth
                  :request send-request
                  :server srv
                  :stopped stopped
                  ;; what this request has sent, for the per-request limits
                  ::sent (atom {:progress 0 :logs 0 :last-progress nil})
                  :cancelled? (fn [] @cancelled)}
           fail (fn [e]
                  (if (:presa/error (ex-data e))
                    (error-response id e)
                    (rpc/error id :internal-error (str "Internal error: " (ex-message e)))))
           prepared (try
                      (let [[session' respond] (request srv session message extra)]
                        {:session session'
                         :respond (fn []
                                    (let [t0 (System/nanoTime)
                                          method (get message "method")
                                          _ (emit! srv {:event :request/start :method method :request-id id
                                                        :version (or (:version session') (get-in message ["params" "_meta" (:protocol-version protocol/meta-keys)]))})
                                          response (try (rpc/result id (respond))
                                                        (catch Exception e (fail e)))]
                                      (emit! srv (cond-> {:event :request/stop :method method :request-id id
                                                          :duration-ms (/ (- (System/nanoTime) t0) 1e6)
                                                          :outcome (cond @cancelled :cancelled
                                                                         (contains? response "error") :error
                                                                         :else :ok)}
                                                   (contains? response "error")
                                                   (assoc :error-code (get-in response ["error" "code"]))))
                                      (when-not @cancelled response)))})
                      (catch Exception e
                        (let [response (fail e)]
                          (emit! srv {:event :request/rejected :method (get message "method") :request-id id
                                      :error-code (get-in response ["error" "code"])})
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

(defalias notify! core/notify!)
(defalias client-request! core/client-request!)

(def max-progress-updates
  "The most progress notifications one request sends; later ones are dropped."
  1000)


(def max-progress-message-bytes
  "The longest progress message, in UTF-8 bytes; a longer update is dropped."
  4096)


(def max-log-messages
  "The most log messages one request sends; later ones are dropped."
  1000)


(def max-log-data-bytes
  "The largest log message data, as JSON in UTF-8 bytes; a larger message is
  dropped."
  16384)


(defn- utf8-length [^String s] (alength (.getBytes s "UTF-8")))


(defn progress!
  "Report progress on the request of context, when the client asked for it
  with a progressToken in _meta. progress is a number that increases with
  each call; total and message are optional. Returns true when the update
  was sent: an update that does not increase, one past
  max-progress-updates, or a message longer than
  max-progress-message-bytes is dropped."
  ([context progress] (progress! context progress {}))
  ([context progress {:keys [total message]}]
   (let [token (get (:meta context) "progressToken")
         sent (::sent context)
         ok? (and token (number? progress)
                  (or (nil? message) (<= (utf8-length (str message)) max-progress-message-bytes))
                  (or (nil? sent)
                      (let [{:keys [last-progress] n :progress} @sent]
                        (and (< n max-progress-updates)
                             (or (nil? last-progress) (> progress last-progress))))))]
     (when ok?
       (when sent (swap! sent #(-> % (update :progress inc) (assoc :last-progress progress))))
       (notify! context "notifications/progress"
                (cond-> {"progressToken" token "progress" progress}
                  total (assoc "total" total)
                  message (assoc "message" message))))
     (boolean ok?))))


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
     (if (and (::logging context) wanted (>= rank wanted)
              (or (nil? (::sent context)) (< (:logs @(::sent context)) max-log-messages))
              (<= (utf8-length (json-text data)) max-log-data-bytes))
       (do (some-> (::sent context) (swap! update :logs inc))
           (notify! context "notifications/message"
                    (cond-> {"level" level "data" data}
                      logger (assoc "logger" logger)))
           true)
       false))))


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
  changed. Takes the server, or a handler's context. A listener whose
  caller may not read uri (see :required-scopes) is not told."
  [srv-or-context uri]
  (let [srv (snapshot (server-of srv-or-context))]
    (doseq [{:keys [uris send auth]} (vals @(:listeners srv))
            :when (and (some #(or (= % uri) (str/starts-with? uri (str (str/replace % #"/$" "") "/"))) uris)
                       (may-read? srv {:auth auth} uri))]
      (try (send "notifications/resources/updated" {"uri" uri}) (catch Exception _)))))


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