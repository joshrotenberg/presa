(ns presa.client
  "Call MCP servers: in process, over stdio, or (later) over HTTP.

  `connect` opens a transport, negotiates a revision and returns a client.
  It tries MCP 2026-07-28 first with server/discover, and falls back to
  initialize when the server does not answer as a 2026-07-28 server and the
  client allows an initialize-era revision. Requests then carry what the
  negotiated revision needs: _meta on every 2026-07-28 request, nothing extra
  after initialize.

  A transport is a function of one map, {:on-message f :on-close g}, that
  opens a connection and returns {:send! (fn [message]) :close! (fn [])}.
  It calls f with each parsed message from the server, from any thread,
  and g once when the connection ends. `in-process` and
  presa.transport.stdio/command make them.

  Errors are ex-info: :kind :protocol for a JSON-RPC error from the server
  (with :code, :message and :data), :kind :timeout when no response came in
  time, :kind :closed when the connection ended first."
  (:require [clojure.string :as str]
            [presa.connection :as connection]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]))

;; Transports

(defn in-process
  "A transport to srv, a presa.server/server map, in this process."
  [srv]
  (fn [{:keys [on-message on-close]}]
    (let [wire (fn [m] (rpc/parse-line (rpc/write-line m)))
          conn (connection/open srv {:send #(on-message (wire %))})
          closed (atom false)]
      {:send! (fn [message]
                (when @closed
                  (throw (ex-info "The connection is closed" {:kind :closed})))
                ;; Round-trip through JSON so a client sees only what the wire
                ;; would carry.
                ((:receive! conn) (wire message)))
       :close! (fn []
                 (when (compare-and-set! closed false true)
                   ((:close! conn))
                   (when on-close (on-close))))})))

;; The client

(def default-timeout-ms 60000)

(defn- protocol-error [{:strs [code message data]}]
  (ex-info (str "MCP error " code ": " message)
           {:kind :protocol :code code :message message :data data}))

(defn- deliver-response [client message]
  (let [id (get message "id")]
    (when-let [p (get @(:pending client) id)]
      (swap! (:pending client) dissoc id)
      (deliver p message))))

(declare invalidate!)

(defn- route-notification [client {:strs [method params] :as message}]
  (invalidate! client message)
  (when-let [f (get @(:subscriptions client)
                    (get-in params ["_meta" (:subscription-id protocol/meta-keys)]))]
    (f message))
  (let [{:keys [on-log on-notification]} (:opts client)]
    (case method
      "notifications/progress"
      (when-let [f (get @(:progress client) (get params "progressToken"))]
        (f params))

      "notifications/message"
      (when on-log (on-log params))

      nil)
    (when on-notification (on-notification message))))

(defn- answer-server-request
  "Answer a request the server sent (initialize-era servers ask clients for
  roots, sampling and elicitation this way)."
  [client {:strs [id method params]}]
  (future
    (let [f (get-in client [:opts :handlers method])
          response (if f
                     (try (rpc/result id (f params))
                          (catch Exception e
                            (if-let [{:keys [code message data]} (:presa/error (ex-data e))]
                              (rpc/error id code message data)
                              (rpc/error id :internal-error (or (ex-message e) "Internal error")))))
                     (rpc/error id :method-not-found (str "Method not found: " method)))]
      (try ((:send! @(:conn client)) response) (catch Exception _)))))

(defn- on-message [client message]
  (case (rpc/kind message)
    (:response :error) (deliver-response client message)
    :notification (route-notification client message)
    :request (answer-server-request client message)
    nil))

(defn- on-close [client]
  (reset! (:closed client) true)
  (let [pending @(:pending client)]
    (reset! (:pending client) {})
    (doseq [[_ p] pending]
      (deliver p ::closed))))

(defn- stateless? [client]
  (protocol/stateless? (:version @(:state client))))

(defn- request-meta [client opts]
  (let [{:keys [client-info capabilities]} (:opts client)
        log-level (if (contains? opts :log-level)
                    (:log-level opts)
                    (get-in client [:opts :log-level]))
        k protocol/meta-keys]
    (cond-> {(:protocol-version k) protocol/latest-version
             (:client-info k) client-info
             (:client-capabilities k) capabilities}
      log-level (assoc (:log-level k) log-level))))

(defn- send-request
  "Send method with params as a request and wait for its response map."
  [client method params {:keys [timeout-ms on-progress meta] :as opts}]
  (when @(:closed client)
    (throw (ex-info "The connection is closed" {:kind :closed})))
  (let [id (swap! (:next-id client) inc)
        token (when on-progress (str "presa-progress-" id))
        meta (cond-> (merge (when (:stateless? opts) (request-meta client opts)) meta)
               token (assoc "progressToken" token))
        params (cond-> (or params {})
                 (seq meta) (update "_meta" merge meta))
        p (promise)
        timeout-ms (or timeout-ms (get-in client [:opts :timeout-ms]) default-timeout-ms)]
    (swap! (:pending client) assoc id p)
    (when token (swap! (:progress client) assoc token on-progress))
    (try
      ((:send! @(:conn client)) (rpc/request id method params))
      (let [response (deref p timeout-ms ::timeout)]
        (cond
          (= ::timeout response)
          (do (swap! (:pending client) dissoc id)
              (try ((:send! @(:conn client))
                    (rpc/notification "notifications/cancelled" {"requestId" id "reason" "timeout"}))
                   (catch Exception _))
              (throw (ex-info (str method " timed out after " timeout-ms " ms")
                              {:kind :timeout :method method :timeout-ms timeout-ms})))

          (= ::closed response)
          (throw (ex-info "The connection closed before the response" {:kind :closed :method method}))

          :else response))
      (finally
        (when token (swap! (:progress client) dissoc token))))))

(def ^:private max-input-rounds 10)

(defn- answer-inputs
  "Answers to a 2026-07-28 input_required result's inputRequests, from the
  client's :handlers."
  [client requests]
  (into {}
        (map (fn [[id {:strs [method params]}]]
               (let [f (or (get-in client [:opts :handlers method])
                           (throw (ex-info (str "The server asked for " method " and the client has no handler for it")
                                           {:kind :unsupported :method method})))]
                 [id (f params)])))
        requests))

(defn- request*
  "request without the cache."
  [client method params opts]
  (loop [params params round 1]
    (let [response (send-request client method params (assoc opts :stateless? (stateless? client)))
          result (get response "result")]
      (cond
        (contains? response "error")
        (throw (protocol-error (get response "error")))

        (and (map? result) (= "input_required" (get result "resultType")))
        (do (when (> round max-input-rounds)
              (throw (ex-info (str method " asked for input more than " max-input-rounds " times")
                              {:kind :protocol :method method})))
            (recur (cond-> (assoc (dissoc (or params {}) "requestState" "_meta")
                                  "inputResponses" (answer-inputs client (get result "inputRequests")))
                     (contains? result "requestState") (assoc "requestState" (get result "requestState")))
                   (inc round)))

        :else result))))

;; Caching by ttlMs

(def ^:private cache-kinds
  {"server/discover" nil "tools/list" "tools" "prompts/list" "prompts"
   "resources/list" "resources" "resources/templates/list" "resources"
   "resources/read" "resources"})

(defn- cache-key [client method params]
  (when (and (:cache client) (contains? cache-kinds method) (stateless? client))
    [method (dissoc (or params {}) "_meta")]))

(defn- now-ms [] (System/currentTimeMillis))

(defn- cached [client k]
  (when-let [{:keys [result expires]} (get @(:cache client) k)]
    (if (< (now-ms) expires)
      result
      (do (swap! (:cache client) dissoc k) nil))))

(defn- store! [client k result]
  (let [ttl (get result "ttlMs")]
    (when (and (integer? ttl) (pos? ttl))
      (swap! (:cache client) assoc k {:result result :expires (+ (now-ms) ttl)}))))

(defn- invalidate!
  "Drop cached results a notification makes stale."
  [client {:strs [method params]}]
  (when-let [cache (:cache client)]
    (case method
      ("notifications/tools/list_changed" "notifications/prompts/list_changed"
       "notifications/resources/list_changed")
      (let [kind (second (re-find #"notifications/(\w+)/list_changed" method))]
        (swap! cache (fn [m] (into {} (remove (fn [[[cm] _]] (and (= kind (cache-kinds cm)) (not= "resources/read" cm)))) m))))

      "notifications/resources/updated"
      (let [uri (get params "uri")]
        (swap! cache (fn [m] (into {} (remove (fn [[[cm p] _]]
                                                (and (= "resources/read" cm)
                                                     (let [u (get p "uri")]
                                                       (or (= u uri) (str/starts-with? (str uri) (str u "/")))))))
                                   m))))
      nil)))

(defn clear-cache!
  "Forget every cached result."
  [client]
  (some-> (:cache client) (reset! {}))
  nil)

(defn request
  "Send a request and return its result map, or throw for a JSON-RPC error.
  When a 2026-07-28 server answers input_required, the client answers each
  input request with its :handlers and sends the request again, with the
  answers and the server's requestState, until the result is complete.
  params is a map with string keys. opts:

  - :timeout-ms   how long to wait (default the client's, 60000)
  - :on-progress  (fn [params]) for notifications/progress about this request
  - :log-level    the least severe log level to receive, on 2026-07-28
                  (default the client's :log-level)
  - :meta         more _meta entries
  - :no-cache     true to ask the server even when the cache holds a result

  With :cache on the client, 2026-07-28 discover, list and read results
  are kept for their ttlMs and served from memory until then, and list
  changes and resource updates from a subscription drop what they make
  stale."
  ([client method] (request client method nil {}))
  ([client method params] (request client method params {}))
  ([client method params opts]
   ;; :no-cache skips the lookup; the fresh result still replaces the entry
   (let [k (cache-key client method params)]
     (or (when (and k (not (:no-cache opts))) (cached client k))
         (let [result (request* client method params opts)]
           (when k (store! client k result))
           result)))))

(defn notify
  "Send the server a notification."
  ([client method] (notify client method nil))
  ([client method params]
   ((:send! @(:conn client)) (rpc/notification method params))))

;; Negotiation

(defn- discover
  "The server/discover result when the server answers as a 2026-07-28
  server, else nil."
  [client]
  (loop [attempt 1]
    (let [response (try (send-request client "server/discover" {}
                                      {:stateless? true
                                       :timeout-ms (get-in client [:opts :probe-timeout-ms] 10000)})
                        (catch clojure.lang.ExceptionInfo e
                          (when-not (= :timeout (:kind (ex-data e))) (throw e))
                          nil))
          result (get response "result")
          error (get response "error")]
      (cond
        (and (map? result)
             (sequential? (get result "supportedVersions"))
             (some #{protocol/latest-version} (get result "supportedVersions")))
        result

        ;; -32022 naming a revision this client speaks: ask again with it
        (and (= -32022 (get error "code"))
             (some #{protocol/latest-version} (get-in error ["data" "supported"]))
             (< attempt 3))
        (recur (inc attempt))

        :else nil))))

(defn- initialize! [client versions]
  (let [requested (first versions)
        {:keys [client-info capabilities]} (:opts client)
        response (send-request client "initialize"
                               {"protocolVersion" requested
                                "capabilities" capabilities
                                "clientInfo" client-info}
                               {:timeout-ms (get-in client [:opts :timeout-ms] default-timeout-ms)})]
    (when-let [error (get response "error")]
      (throw (protocol-error error)))
    (let [result (get response "result")
          version (get result "protocolVersion")]
      (when-not (some #{version} versions)
        (throw (ex-info (str "The server answered initialize with " version
                             ", which this client does not speak")
                        {:kind :unsupported-version :version version :supported versions})))
      (notify client "notifications/initialized")
      result)))

(defn- handler-capabilities [handlers]
  (cond-> {}
    (contains? handlers "elicitation/create") (assoc "elicitation" {"form" {}})
    (contains? handlers "sampling/createMessage") (assoc "sampling" {})
    (contains? handlers "roots/list") (assoc "roots" {})))

(defn connect
  "Open transport and negotiate a revision. Returns a client. Options:

  - :client-info   {\"name\" .. \"version\" ..} (default presa's)
  - :capabilities  the client's capabilities map (default {})
  - :versions      revisions to try, newest first (default
                   presa.protocol/versions)
  - :timeout-ms    the default request timeout (default 60000)
  - :probe-timeout-ms  how long to wait for server/discover (default 10000)
  - :log-level     the least severe log level to receive, sent with each
                   2026-07-28 request; on an initialize-era connection, use
                   `set-log-level`
  - :on-log        (fn [params]) for notifications/message
  - :on-notification  (fn [message]) for every notification
  - :cache         true to cache 2026-07-28 results by their ttlMs; see
                   `request`
  - :handlers      {method (fn [params])} answering what a server asks
                   for: \"elicitation/create\", \"sampling/createMessage\",
                   \"roots/list\". They answer input requests in 2026-07-28
                   input_required results and requests from initialize-era
                   servers alike. Without :capabilities, the client declares
                   the capabilities its handlers cover."
  ([transport] (connect transport {}))
  ([transport opts]
   (let [opts (merge {:client-info {"name" "presa" "version" "0.0.0"}
                      :capabilities (handler-capabilities (:handlers opts))
                      :versions protocol/versions}
                     opts)
         client {:opts opts
                 :conn (atom nil)
                 :pending (atom {})
                 :progress (atom {})
                 :subscriptions (atom {})
                 :cache (when (:cache opts) (atom {}))
                 :next-id (atom 0)
                 :closed (atom false)
                 :state (atom {})}
         conn (transport {:on-message #(on-message client %)
                          :on-close #(on-close client)})
         versions (filterv (set (:versions opts)) protocol/versions)]
     (reset! (:conn client) conn)
     (try
       (if-let [result (and (some protocol/stateless? versions) (discover client))]
         (reset! (:state client)
                 {:version protocol/latest-version
                  :server-info (or (get result "serverInfo")
                                   (get-in result ["_meta" (:server-info protocol/meta-keys)]))
                  :server-capabilities (get result "capabilities")
                  :instructions (get result "instructions")
                  :supported-versions (get result "supportedVersions")})
         (let [legacy (filterv (complement protocol/stateless?) versions)]
           (when (empty? legacy)
             (throw (ex-info "The server does not speak 2026-07-28, and the client allows no other revision"
                             {:kind :unsupported-version :supported versions})))
           (let [result (initialize! client legacy)]
             (reset! (:state client)
                     {:version (get result "protocolVersion")
                      :server-info (get result "serverInfo")
                      :server-capabilities (get result "capabilities")
                      :instructions (get result "instructions")}))))
       client
       (catch Throwable e
         ((:close! conn))
         (throw e))))))

(defn close
  "Close the client's connection."
  [client]
  ((:close! @(:conn client))))

(defn info
  "What negotiation found: {:version :server-info :server-capabilities
  :instructions}."
  [client]
  @(:state client))

;; Calls

(defn- all-pages
  "Every item of a paginated list method, following nextCursor."
  [client method k params opts]
  (loop [cursor nil acc [] pages 0]
    (when (> pages 1000)
      (throw (ex-info (str method " returned more than 1000 pages") {:kind :protocol :method method})))
    (let [result (request client method (cond-> (or params {}) cursor (assoc "cursor" cursor)) opts)
          acc (into acc (get result k))
          next-cursor (get result "nextCursor")]
      (if (and (string? next-cursor) (not= cursor next-cursor))
        (recur next-cursor acc (inc pages))
        acc))))

(defn ping
  "Check that the server answers. 2026-07-28 has no ping, so on that
  revision this sends server/discover."
  [client & [opts]]
  (request client (if (stateless? client) "server/discover" "ping") {} opts))

(defn list-tools
  "Every tool the server lists, following pagination."
  [client & [opts]]
  (all-pages client "tools/list" "tools" {} opts))

(defn call-tool
  "Call a tool and return its result map (\"content\", \"isError\", maybe
  \"structuredContent\"). A tool that fails returns isError true rather than
  throwing."
  ([client name] (call-tool client name {} {}))
  ([client name arguments] (call-tool client name arguments {}))
  ([client name arguments opts]
   (request client "tools/call" {"name" name "arguments" (or arguments {})} opts)))

(defn list-resources [client & [opts]]
  (all-pages client "resources/list" "resources" {} opts))

(defn list-resource-templates [client & [opts]]
  (all-pages client "resources/templates/list" "resourceTemplates" {} opts))

(defn read-resource
  "The contents of the resource at uri: a vector of maps with \"uri\" and
  \"text\" or \"blob\"."
  [client uri & [opts]]
  (get (request client "resources/read" {"uri" uri} opts) "contents"))

(defn list-prompts [client & [opts]]
  (all-pages client "prompts/list" "prompts" {} opts))

(defn get-prompt
  ([client name] (get-prompt client name {} {}))
  ([client name arguments] (get-prompt client name arguments {}))
  ([client name arguments opts]
   (request client "prompts/get" {"name" name "arguments" (or arguments {})} opts)))

(defn complete
  "Completion values for an argument. ref is {\"type\" \"ref/prompt\" \"name\" ..}
  or {\"type\" \"ref/resource\" \"uri\" ..}."
  ([client ref argument-name value] (complete client ref argument-name value {} {}))
  ([client ref argument-name value arguments] (complete client ref argument-name value arguments {}))
  ([client ref argument-name value arguments opts]
   (get (request client "completion/complete"
                 (cond-> {"ref" ref "argument" {"name" argument-name "value" value}}
                   (seq arguments) (assoc "context" {"arguments" arguments}))
                 opts)
        "completion")))

(defn set-log-level
  "Ask an initialize-era server for log messages at level and above. On
  2026-07-28 the level travels with each request instead (see :log-level)."
  [client level]
  (when (stateless? client)
    (throw (ex-info "logging/setLevel is not part of 2026-07-28; pass :log-level instead"
                    {:kind :invalid-input})))
  (request client "logging/setLevel" {"level" level}))

(defn tool-fns
  "The server's tools as functions, by name: each takes an arguments map
  (and optionally request opts) and returns the tool's result map."
  [client]
  (into {}
        (map (fn [{:strs [name]}]
               [name (fn
                       ([arguments] (call-tool client name arguments))
                       ([arguments opts] (call-tool client name arguments opts)))]))
        (list-tools client)))

(defn text
  "The text of a tool result's text content blocks, joined with newlines."
  [result]
  (->> (get result "content")
       (filter #(= "text" (get % "type")))
       (map #(get % "text"))
       (str/join "\n")))

(defn listen
  "Open a 2026-07-28 subscriptions/listen stream. notifications says what to
  listen for: {\"toolsListChanged\" true \"promptsListChanged\" true
  \"resourcesListChanged\" true \"resourceSubscriptions\" [uri ..]}.
  on-notification gets each notification on the stream, the server's
  acknowledgement (notifications/subscriptions/acknowledged) first.

  Returns {:acknowledged p :stop! f :done p}: acknowledged is a promise of
  the acknowledgement's params; (stop!) cancels the stream; done is a
  promise of the final result when the server ends the stream, or :stopped,
  or the exception that ended it."
  [client notifications on-notification]
  (when-not (stateless? client)
    (throw (ex-info "subscriptions/listen is part of 2026-07-28 only" {:kind :unsupported})))
  (let [id (str "presa-listen-" (swap! (:next-id client) inc))
        acknowledged (promise)
        done (promise)
        stopped (atom false)
        p (promise)
        params (assoc {"notifications" notifications} "_meta" (request-meta client {}))]
    (swap! (:subscriptions client) assoc id
           (fn [message]
             ;; the callback sees the acknowledgement before the promise
             ;; says it came
             (on-notification message)
             (when (= "notifications/subscriptions/acknowledged" (get message "method"))
               (deliver acknowledged (get message "params")))))
    (swap! (:pending client) assoc id p)
    ((:send! @(:conn client)) (rpc/request id "subscriptions/listen" params))
    (future
      (let [response @p]
        (swap! (:subscriptions client) dissoc id)
        (deliver acknowledged nil)
        (deliver done (cond
                        @stopped :stopped
                        (= ::closed response) (ex-info "The connection closed" {:kind :closed})
                        (contains? response "error") (protocol-error (get response "error"))
                        :else (get response "result")))))
    {:acknowledged acknowledged
     :done done
     :stop! (fn []
              (when (compare-and-set! stopped false true)
                (try ((:send! @(:conn client))
                      (rpc/notification "notifications/cancelled" {"requestId" id "reason" "stopped"}))
                     (catch Exception _))
                (swap! (:pending client) dissoc id)
                (deliver p {"result" nil})))}))
