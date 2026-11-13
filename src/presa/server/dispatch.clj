(ns ^:no-doc presa.server.dispatch
  "Internal to presa.server; not part of the API. Resolving a request's
  revision from _meta or the session, initialize, and routing to methods."
  (:require [clojure.string :as str]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.request-state :as request-state]
            [presa.server.core :refer [error now-ms]]
            [presa.server.catalog :refer [capabilities legacy-versions snapshot stateless-versions]]
            [presa.server.access :refer [match-template may-read? required-scopes]]
            [presa.server.shape :refer [input-methods shape]]
            [presa.server.skills :refer [directory-read skills-get skills-list]]
            [presa.server.tasks :refer [tasks-cancel tasks-get tasks-update]]
            [presa.server.methods :refer [completion-complete initialize-result ping prompts-get prompts-list resources-list resources-read server-discover templates-list tools-call tools-list]]
            [presa.server.subscriptions :refer [max-session-subscriptions subscriptions-listen]]))

(def method-table
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
   "subscriptions/listen" {:f subscriptions-listen :capability :presa.server/subscriptions :legacy? false}
   "tasks/get" {:f tasks-get :capability :presa.server/tasks :legacy? false}
   "tasks/update" {:f tasks-update :capability :presa.server/tasks :legacy? false}
   "tasks/cancel" {:f tasks-cancel :capability :presa.server/tasks :legacy? false}
   ;; the Skills extension
   "skills/list" {:f skills-list :capability :presa.server/skills :legacy? true}
   "skills/get" {:f skills-get :capability :presa.server/skills :legacy? true}
   "resources/directory/read" {:f directory-read :capability :presa.server/skills :legacy? true}})

(defn method-fn [srv method legacy?]
  (let [{:keys [f capability] :as m} (get method-table method)]
    (if (and m
             (if legacy? (:legacy? m) (:stateless? m true))
             (cond
               (nil? capability) true
               (= :presa.server/subscriptions capability) (or (:list-changed srv) (:subscribe srv) (:task-store srv))
               (= :presa.server/tasks capability) (some? (:task-store srv))
               (= :presa.server/skills capability) (some? (:skills srv))
               :else (contains? (capabilities srv) capability)))
      f
      (throw (error :method-not-found (str "Method not found: " method))))))

;; Resolving a request's revision

(def meta-key
  "The syntax of a _meta key: an optional dotted prefix ending in /, then a
  name."
  #"^(?:(?:[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?)(?:\.(?:[A-Za-z](?:[A-Za-z0-9-]*[A-Za-z0-9])?))*/)?(?:[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?)?$")

(defn bounded-id?
  "A string of at most 256 bytes or an int64 integer: what request ids and
  progress tokens may be."
  [v]
  (or (and (string? v) (<= (count (.getBytes ^String v "UTF-8")) 256))
      (and (integer? v) (<= Long/MIN_VALUE v Long/MAX_VALUE))))

(defn request-meta [params]
  (let [m (get params "_meta" {})]
    (when-not (map? m)
      (throw (error :invalid-params "_meta must be an object")))
    m))

(defn stateless-context
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

(defn input-admission
  "The :input-responses and :state of a 2026-07-28 retry, checked."
  [srv method params principal]
  (if (= "tasks/update" method)
    ;; its inputResponses answer a task, not a retry
    {:input-responses {} :state nil}
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
                (try (request-state/verify (:state-secret srv) method params token (now-ms) principal)
                     (catch clojure.lang.ExceptionInfo _
                       (throw (error :invalid-params "Invalid or expired request state")))))})))

(defn negotiate
  "The version to answer initialize with: the one the client asked for when
  the server serves it, else the newest initialize-era one it serves."
  [srv requested]
  (let [legacy (legacy-versions srv)]
    (or (some #{requested} legacy) (first legacy))))

(defn initialize
  "Settle a session from an initialize request: [session respond]. With
  :list-changed, the session hears list changes through notify for as long
  as it lasts (see `close-session!`)."
  [srv session params notify]
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
      ;; a new session replaces the connection's old one and what it listened to
      (when-let [old (:session-id session)]
        (swap! (:listeners srv) (fn [m] (into {} (remove (fn [[k _]] (and (vector? k) (= old (first k))))) m))))
      (when (and (:list-changed srv) notify)
        (swap! (:listeners srv) assoc [session-id :list-changed]
               {:kinds #{"tools" "prompts" "resources"}
                :send (fn [method params] (notify (rpc/notification method (not-empty params))))}))
      [{:version version :client-info client-info :client-capabilities capabilities
        :log-level (:log-level srv) :session-id session-id}
       (fn [] (initialize-result srv version))])))

;; Dispatch

(defn error-response [id e]
  (let [{:keys [code message data]} (:presa/error (ex-data e))]
    (rpc/error id code message data)))

(defn request
  "[session respond] for a request with an id."
  [srv session {:strs [id method params] :as message} extra]
  (let [srv (snapshot srv)]
    (when-not (bounded-id? id)
      (throw (error :invalid-request "Request id must be a string of at most 256 bytes or an int64 integer")))
    (when (and (contains? message "params") (not (map? params)))
      (throw (error :invalid-params "Request params must be an object")))
    (let [extra (assoc extra :presa.server/logging (:logging srv))
          params (or params {})
          meta (request-meta params)
          version-key (:protocol-version protocol/meta-keys)]
      (cond
        (contains? meta version-key)
        (let [context (merge (stateless-context srv meta) extra {:request-id id})
              f (method-fn srv method false)
              context (merge context (input-admission srv method params (get-in context [:auth :subject])))]
          [session (fn [] (shape srv context method params (f srv params context)))])

        (= "initialize" method)
        (initialize srv session params (or (:session-notify extra) (:notify extra)))

        (and (= "ping" method) (seq (legacy-versions srv)))
        [session (constantly {})]

        (and (:version session) (#{"resources/subscribe" "resources/unsubscribe"} method))
        (let [uri (get params "uri")]
          (when-not (:subscribe srv)
            (throw (error :method-not-found (str "Method not found: " method))))
          (when-not (and (string? uri) (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" uri))
            (throw (error :invalid-params (str method " requires an absolute uri"))))
          (when (and (= "resources/subscribe" method) (not (may-read? srv extra uri)))
            (throw (error :insufficient-scope (str "Insufficient scope for resource " uri)
                          {"error" "insufficient_scope"
                           "scope" (str/join " " (or (when (contains? (:resources srv) uri)
                                                       (required-scopes srv :resource uri))
                                                     (when-let [t (match-template srv uri)]
                                                       (required-scopes srv :resource-template (:uri-template t)))
                                                     (:default-scopes srv)))})))
          (if (= "resources/subscribe" method)
            (let [k [(:session-id session) uri]
                  ;; the session's URI subscriptions, not its list-change entry
                  uri-key? #(and (vector? %) (= (:session-id session) (first %)) (string? (second %)))
                  entry {:kinds #{}
                         :auth (:auth extra)
                         :uris [uri]
                         :send (let [notify (or (:session-notify extra) (:notify extra))]
                                 (fn [method params] (notify (rpc/notification method (not-empty params)))))}
                  ;; check and register in one step
                  after (swap! (:listeners srv)
                               (fn [m] (if (or (contains? m k)
                                               (< (count (filter uri-key? (keys m))) max-session-subscriptions))
                                         (assoc m k entry)
                                         m)))]
              (when-not (contains? after k)
                (throw (error :invalid-params (str "A session may subscribe to at most " max-session-subscriptions " URIs")))))
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

(defn valid-notification?
  "Whether a notification's params and _meta have the right shape. A
  notification is never answered, so one that fails is ignored."
  [{:strs [params] :as message}]
  (and (or (not (contains? message "params")) (map? params))
       (let [meta (get params "_meta" {})]
         (and (map? meta)
              (every? #(and (string? %) (re-matches meta-key %)) (keys meta))))))

(defn cancel-target
  "The request id a well-formed notifications/cancelled names, or nil."
  [message]
  (when (and (= "notifications/cancelled" (get message "method"))
             (valid-notification? message))
    (let [{:strs [requestId reason] :as params} (get message "params")]
      (when (and (bounded-id? requestId)
                 (or (not (contains? params "reason")) (string? reason)))
        requestId))))
