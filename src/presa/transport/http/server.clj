(ns ^:no-doc presa.transport.http.server
  "Internal to presa.transport.http; not part of the API. The Streamable HTTP
  server over http-kit."
  (:require [clojure.string :as str]
            [org.httpkit.server :as hk]
            [presa.auth :as auth]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.http.headers :refer [decode-header-value name-param param-headers write-json]])
  (:import (java.net URI)
           (java.util UUID)))

;; Server

(def ^:private loopback-hosts #{"localhost" "127.0.0.1" "[::1]" "::1"})

(defn- loopback-origin? [origin]
  (try
    (let [u (URI. origin)]
      (and (#{"http" "https"} (.getScheme u))
           (nil? (.getUserInfo u))
           (contains? loopback-hosts (.getHost u))))
    (catch Exception _ false)))

(defn- allowed? [allow v]
  (cond (nil? allow) false
        (set? allow) (contains? allow v)
        (fn? allow) (boolean (allow v))
        :else false))

(defn- json-response
  ([status body] (json-response status body {}))
  ([status body headers]
   {:status status
    :headers (merge {"content-type" "application/json"} headers)
    :body (write-json body)}))

(defn- rejection [status code message]
  (json-response status (rpc/error nil code message)))

(defn- error-status
  "The HTTP status of a response on 2026-07-28. Initialize-era clients read
  any non-2xx status as a transport failure, so they get 200."
  [response stateless?]
  (if-let [code (and stateless? (get-in response ["error" "code"]))]
    (case (int code)
      -32700 400 -32600 400 -32601 404 -32602 400
      -32603 (if (get-in response ["error" "data" "capacity"]) 503 500)
      -32020 400 -32021 400 -32022 400
      200)
    200))

(defn- header [req k] (get-in req [:headers k]))

(defn- media-types [s]
  (set (map #(str/lower-case (str/trim (first (str/split % #";")))) (str/split (or s "") #","))))

(defn- header-mismatch [id message & [data]]
  (json-response 400 (rpc/error id :header-mismatch message data)))

(defn- check-stateless-headers
  "nil when a 2026-07-28 request's headers match its body, else the
  rejection."
  [req {:strs [id method params]}]
  (let [body-version (get-in params ["_meta" (:protocol-version protocol/meta-keys)])
        version (header req "mcp-protocol-version")
        mcp-method (header req "mcp-method")
        name-key (get name-param method)]
    (cond
      (nil? version)
      (header-mismatch id "Missing or repeated required header: mcp-protocol-version")

      (and (string? body-version) (not= (str/trim version) body-version))
      (header-mismatch id "Protocol header does not match request metadata"
                       {"header" version "body" body-version})

      (nil? mcp-method)
      (header-mismatch id "Missing or repeated required header: mcp-method")

      (not= (str/trim mcp-method) method)
      (header-mismatch id "mcp-method does not match the request body")

      (and name-key (nil? (header req "mcp-name")))
      (header-mismatch id "Missing or repeated required header: mcp-name")

      (and name-key (nil? (decode-header-value (header req "mcp-name"))))
      (header-mismatch id "mcp-name is malformed")

      (and name-key (not= (decode-header-value (header req "mcp-name")) (get params name-key)))
      (header-mismatch id "mcp-name does not match the request body"))))

(defn- check-param-headers
  "nil when a tools/call request's Mcp-Param-* headers match the arguments
  its tool's input schema mirrors with x-mcp-header, else the rejection."
  [srv req {:strs [id params]}]
  (let [tool (server/tool srv (get params "name"))
        found (when tool (param-headers (:input-schema tool)))]
    (when (sequential? found)
      (some (fn [[h prop _]]
              (let [k (str "mcp-param-" (str/lower-case h))
                    v (get-in params ["arguments" prop])
                    raw (header req k)
                    decoded (decode-header-value raw)]
                (cond
                  (and (some? v) (nil? raw))
                  (header-mismatch id (str "Missing or repeated required header: " k))

                  (nil? raw) nil

                  (or (nil? decoded)
                      (and (not (str/starts-with? (str/trim raw) "=?base64?"))
                           (not (re-matches #"[\x20-\x7E\t]*" raw))))
                  (header-mismatch id (str k " is malformed"))

                  (not (cond
                         (string? v) (= decoded v)
                         (boolean? v) (= decoded (str v))
                         (integer? v) (= (parse-long (str/trim decoded)) (long v))
                         :else true))
                  (header-mismatch id (str k " does not match the request body")))))
            found))))

(def ^:private sse-headers
  {"content-type" "text/event-stream"
   "cache-control" "no-cache"
   "x-accel-buffering" "no"})

(defn- sse-event [message] (str "data: " (write-json message) "\n\n"))

(defn- now-ms [] (System/currentTimeMillis))

(defn- new-session [session]
  {:session (atom session)
   ;; when a request last used or finished on the session
   :last-seen (atom (now-ms))
   :lock (Object.)
   :outgoing (atom {})
   :running (atom {})
   :next-id (atom 0)
   ;; the session's GET event stream, when the client holds one open
   :stream (atom nil)})

(defn- session-notify
  "Send a session-level message on the session's GET stream, when open;
  without one it is dropped, as the client asked for no stream."
  [entry]
  (fn [message]
    (when-let [ch @(:stream entry)]
      (when-not (hk/send! ch (sse-event message) false)
        (compare-and-set! (:stream entry) ch nil)))))

(defn- session-stream
  "Open the GET event stream of an initialize-era session."
  [req entry]
  (hk/as-channel
   req
   {:on-open (fn [ch]
               (hk/send! ch {:status 200 :headers sse-headers} false)
               ;; a newer stream replaces an older one
               (when-let [old (first (reset-vals! (:stream entry) ch))]
                 (try (hk/close old) (catch Throwable _))))
    :on-close (fn [ch _] (compare-and-set! (:stream entry) ch nil))}))

(defn- execute
  "Run one request over an http-kit channel. session is a session entry
  (initialize-era) or nil (2026-07-28). on-session is called with the
  prepared result before the work starts, to register a new session."
  [srv req message {:keys [session stateless? on-session headers log keepalive-ms identity running]}]
  (hk/as-channel
   req
   {:on-open
    (fn [ch]
      (let [lock (Object.)
            streaming (atom false)
            closed (atom false)
            ;; stop! ends the exchange early: set once work exists, it is
            ;; idempotent through closed
            stop (atom nil)
            gone (atom false)
            ;; A failed write is how http-kit learns of some disconnects;
            ;; one before stop! exists is acted on when it is set.
            client-gone! (fn [] (reset! gone true) (when-let [f @stop] (f)))
            emit! (fn [m]
                    (when-not (locking lock
                                (or @closed
                                    (do (when-not @streaming
                                          (hk/send! ch {:status 200 :headers (merge sse-headers headers)} false)
                                          (reset! streaming true))
                                        (hk/send! ch (sse-event m) false))))
                      (client-gone!)))
            outgoing (:outgoing session)
            client-request (when session
                             (fn [method params timeout-ms]
                               (let [id (str "presa-" (swap! (:next-id session) inc))
                                     p (promise)]
                                 (swap! outgoing assoc id p)
                                 (try
                                   (emit! (rpc/request id method params))
                                   (let [r (deref p timeout-ms :presa.transport.http/timeout)]
                                     (when (= :presa.transport.http/timeout r)
                                       (throw (ex-info (str method " timed out") {:kind :timeout :method method})))
                                     r)
                                   (finally (swap! outgoing dissoc id))))))
            opts {:notify emit!
                  :auth identity
                  :request client-request
                  :session-notify (when session (session-notify session))}
            prepared (if session
                       (locking (:lock session)
                         (let [p (server/prepare srv @(:session session) message opts)]
                           (reset! (:session session) (:session p))
                           p))
                       (server/prepare srv {} message opts))
            {:keys [respond request-id cancel!]} prepared
            _ (when on-session (on-session prepared))
            ;; running is [registry key]: where a posted notifications/cancelled
            ;; can find this request. It is registered before work starts, so
            ;; work's finally always finds the entry to remove.
            registered (when (some? request-id) running)
            _ (when-let [[registry k] registered]
                (swap! registry assoc k {:cancel! cancel!}))
            work (future
                   (binding [*out* *err*]
                     (try
                       (let [response (respond)
                             reply (fn [response]
                                     (cond
                                       @streaming (if response
                                                    (hk/send! ch (sse-event response) true)
                                                    (hk/close ch))
                                       response (hk/send! ch (update (json-response (error-status response stateless?) response)
                                                                     :headers merge headers)
                                                          true)
                                       :else (hk/send! ch {:status 204 :headers headers} true)))]
                         (locking lock
                           (when-not @closed
                             (reset! closed true)
                             (try (reply response)
                                  (catch Throwable e
                                    ;; writing JSON fails before anything is sent, so
                                    ;; the request can still be answered
                                    (log "failed to write a response: " (ex-message e))
                                    (reply (rpc/error (get response "id") :internal-error
                                                      (str "Internal error: the response could not be written: "
                                                           (ex-message e)))))))))
                       (catch Throwable e
                         (log "failed to answer a request: " (ex-message e))
                         (try (hk/close ch) (catch Throwable _)))
                       (finally
                         (when-let [[registry k] registered]
                           (swap! registry dissoc k))
                         (when session (reset! (:last-seen session) (now-ms)))))))]
        ;; add work to the entry, unless work has already finished and
        ;; removed it
        (when-let [[registry k] registered]
          (swap! registry (fn [m] (if (contains? m k) (assoc-in m [k :work] work) m))))
        (when (= "subscriptions/listen" (get message "method"))
          ;; keep idle subscription streams open through proxies
          (future
            (loop []
              (Thread/sleep (long keepalive-ms))
              (when (locking lock
                      (when (and @streaming (not @closed))
                        (or (hk/send! ch ": keepalive\n\n" false)
                            (do (client-gone!) false))))
                (recur)))))
        (let [stop! (fn []
                      (when-not (locking lock (let [done @closed] (reset! closed true) done))
                        ;; the client went away first: cancel its request
                        (when cancel! (cancel!))
                        (future-cancel work)))]
          (reset! stop stop!)
          (when @gone (stop!))
          (hk/on-close ch (fn [_] (stop!))))))}))

(def default-max-body-bytes
  "The largest request body the server reads unless :max-body-bytes says
  otherwise; a larger one gets 413."
  2000000)

(defn- read-body
  "The body as a string, or nil when it is longer than max-bytes."
  [body max-bytes]
  (if (nil? body)
    ""
    (let [^java.io.InputStream in (if (string? body)
                                    (java.io.ByteArrayInputStream. (.getBytes ^String body "UTF-8"))
                                    body)
          out (java.io.ByteArrayOutputStream.)
          buf (byte-array 8192)]
      (loop [total 0]
        (let [n (.read in buf)]
          (cond
            (neg? n) (String. (.toByteArray out) "UTF-8")
            (> (+ total n) max-bytes) nil
            :else (do (.write out buf 0 n) (recur (+ total n)))))))))

(defn- idle?
  "Whether a session entry has nothing in flight, no open GET stream, and
  has not been used for idle-ms."
  [entry idle-ms now]
  (and (empty? @(:running entry))
       (nil? @(:stream entry))
       (>= (- now @(:last-seen entry)) idle-ms)))

(defn- close-entry!
  "Close a session entry: end its server session and its GET stream."
  [srv entry]
  (server/close-session! srv @(:session entry))
  (when-let [ch @(:stream entry)] (try (hk/close ch) (catch Throwable _))))

(defn- sweep!
  "Remove and close the idle sessions."
  [srv sessions idle-ms]
  (let [now (now-ms)]
    (doseq [[sid entry] @sessions
            :when (idle? entry idle-ms now)]
      ;; remove first, so a concurrent sweep or DELETE closes it once
      (let [[before after] (swap-vals! sessions (fn [m] (if (identical? entry (get m sid)) (dissoc m sid) m)))]
        (when (and (identical? entry (get before sid)) (not (contains? after sid)))
          (close-entry! srv entry))))))

(def default-session-idle-ms
  "How long an initialize-era session may sit idle before it is closed,
  unless :session-idle-ms says otherwise."
  1800000)

(def default-max-sessions
  "How many initialize-era sessions the server holds unless :max-sessions
  says otherwise."
  1000)

(def default-max-sessions-per-subject
  "How many initialize-era sessions one authenticated subject may hold,
  with :auth, unless :max-sessions-per-subject says otherwise."
  100)

(defn handler
  "A ring handler serving srv. Options:

  - :path             the endpoint (default \"/mcp\")
  - :allowed-origins  a set of origins, or a predicate, allowed besides
                      loopback ones; requests without Origin are allowed
  - :allowed-hosts    when given, a set of Host header values (or a
                      predicate) the server accepts; others get 403
  - :max-body-bytes   the largest request body (default 2000000); a larger
                      one gets 413
  - :auth             a presa.auth/resource-server config: serve its
                      metadata document and require a bearer token on every
                      request; handlers see the identity as (:auth context)
  - :cancellation     :subject to let a posted notifications/cancelled stop a
                      2026-07-28 request of the same authenticated subject
                      (needs :auth). Without it a posted cancel is ignored
                      and only closing the connection cancels, since request
                      ids are not unique across callers
  - :keepalive-ms     how often an idle subscription stream gets a comment,
                      which also finds clients that have gone (default 15000)
  - :session-idle-ms  an initialize-era session with no request in flight
                      and no open GET stream is closed once unused this long
                      (default 1800000); idle sessions are swept when an
                      initialize arrives
  - :max-sessions     at this many initialize-era sessions, initialize gets
                      503 (default 1000)
  - :max-sessions-per-subject  with :auth, at this many initialize-era
                      sessions of one subject, that subject's initialize gets
                      503 (default 100)
  - :log              (fn [& parts]) for diagnostics"
  ([srv] (handler srv {}))
  ([srv {:keys [path allowed-origins allowed-hosts log keepalive-ms max-body-bytes cancellation
                session-idle-ms max-sessions max-sessions-per-subject]
         auth-config :auth
         :or {path "/mcp" log (fn [& _]) keepalive-ms 15000 max-body-bytes default-max-body-bytes
              session-idle-ms default-session-idle-ms max-sessions default-max-sessions
              max-sessions-per-subject default-max-sessions-per-subject}}]
   (let [sessions (atom {})
         ;; 2026-07-28 requests in flight by [subject id], for :cancellation :subject
         stateless-running (atom {})]
     (fn [req]
       (let [origin (header req "origin")
             sid (header req "mcp-session-id")
             authn (delay (when auth-config (auth/authenticate auth-config req)))
             caller (fn [] (:auth @authn))
             ;; an initialize-era session belongs to the subject that opened it
             stateless-key (fn [message]
                             (when-let [subject (and (= :subject cancellation) (:subject (caller)))]
                               [stateless-running [subject (get message "id")]]))
             session-of (fn [sid]
                          (when-let [entry (get @sessions sid)]
                            (when (= (:subject entry) (:subject (caller)))
                              (reset! (:last-seen entry) (now-ms))
                              entry)))]
         (cond
           (and auth-config (= (:metadata-path auth-config) (:uri req)))
           (auth/metadata-response auth-config req)

           (not= path (:uri req))
           {:status 404 :headers {"content-type" "text/plain"} :body "Not found"}

           (and origin (not (loopback-origin? origin)) (not (allowed? allowed-origins origin)))
           (rejection 403 :invalid-request "Origin is not allowed")

           (and allowed-hosts (not (allowed? allowed-hosts (header req "host"))))
           (rejection 403 :invalid-request "Host is not allowed")

           (:response @authn)
           (:response @authn)

           (and (= :delete (:request-method req)) sid)
           (if-let [entry (session-of sid)]
             (do (swap! sessions dissoc sid)
                 (close-entry! srv entry)
                 {:status 204})
             (rejection 404 :invalid-request "Session not found"))

           (and (= :get (:request-method req)) sid)
           (if-let [entry (session-of sid)]
             (if (contains? (media-types (header req "accept")) "text/event-stream")
               (session-stream req entry)
               (rejection 406 :invalid-request "Accept must list text/event-stream"))
             (rejection 404 :invalid-request "Session not found"))

           (not= :post (:request-method req))
           (assoc-in (rejection 405 :invalid-request "Streamable HTTP accepts POST only")
                     [:headers "allow"] "POST")

           (not (contains? (media-types (header req "content-type")) "application/json"))
           (rejection 415 :invalid-request "Expected application/json")

           (not (every? (media-types (header req "accept")) ["application/json" "text/event-stream"]))
           (rejection 406 :invalid-request "Accept must list application/json and text/event-stream")

           (some-> (:content-length req) (> max-body-bytes))
           (rejection 413 :invalid-request (str "Request body exceeds " max-body-bytes " bytes"))

           :else
           (let [body (when-let [s (read-body (:body req) max-body-bytes)]
                        ;; a leading byte order mark is not part of the JSON
                        (if (str/starts-with? s (str (char 0xFEFF))) (subs s 1) s))
                 message (if (nil? body) :presa.transport.http/too-large (try (rpc/parse-line body) (catch Exception _ :presa.transport.http/unparsed)))
                 session (when sid (session-of sid))]
             (case message
               :presa.transport.http/too-large (rejection 413 :invalid-request (str "Request body exceeds " max-body-bytes " bytes"))
               :presa.transport.http/unparsed (rejection 400 :parse-error "Parse error")
               (case (rpc/kind message)
                 (:response :error)
                 (do (when-let [p (some-> session :outgoing deref (get (get message "id")))]
                       (deliver p message))
                     {:status 202})

                 :invalid
                 (let [id (get message "id")]
                   (json-response 400 (rpc/error (when (or (string? id) (integer? id)) id)
                                                 :invalid-request "Invalid JSON-RPC 2.0 message")))

                 :notification
                 (do (when-let [id (and (= "notifications/cancelled" (get message "method"))
                                        (get-in message ["params" "requestId"]))]
                       (when-let [{:keys [cancel! work]}
                                  (cond
                                    session (get @(:running session) id)
                                    ;; a 2026-07-28 request, only within the
                                    ;; caller's own scope
                                    (and (= :subject cancellation) (:subject (caller)))
                                    (get @stateless-running [(:subject (caller)) id]))]
                         (cancel!)
                         (when work (future-cancel work))))
                     {:status 202})

                 :request
                 (let [meta-version (get-in message ["params" "_meta" (:protocol-version protocol/meta-keys)])
                       method (get message "method")]
                   (cond
                     (some? meta-version)
                     (or (check-stateless-headers req message)
                         (when (= "tools/call" method) (check-param-headers srv req message))
                         (execute srv req message {:stateless? true :log log :keepalive-ms keepalive-ms :identity (caller)
                                                   :running (stateless-key message)}))

                     (and (= "initialize" method)
                          (do (sweep! srv sessions session-idle-ms)
                              (or (>= (count @sessions) max-sessions)
                                  ;; one subject cannot fill the server
                                  (when-let [subject (and auth-config (:subject (caller)))]
                                    (>= (count (filter #(= subject (:subject %)) (vals @sessions)))
                                        max-sessions-per-subject)))))
                     (json-response 503 (rpc/error (get message "id") :internal-error "Too many sessions"))

                     (= "initialize" method)
                     (let [id (str (UUID/randomUUID))
                           entry (assoc (new-session {}) :subject (:subject (caller)))]
                       (execute srv req message
                                {:session entry
                                 :identity (caller)
                                 :running [(:running entry) (get message "id")]
                                 :log log
                                 :keepalive-ms keepalive-ms
                                 :headers {"mcp-session-id" id}
                                 :on-session (fn [{s :session}]
                                               (when (:version s)
                                                 (swap! sessions assoc id entry)))}))

                     (and sid (nil? session))
                     (rejection 404 :invalid-request "Session not found")

                     session
                     (let [version (header req "mcp-protocol-version")]
                       (if (and version (not= (str/trim version) (:version @(:session session))))
                         (json-response 400 (rpc/error (get message "id") :invalid-request
                                                       "MCP-Protocol-Version does not match the session"))
                         (execute srv req message {:session session :log log :keepalive-ms keepalive-ms :identity (caller)
                                                   :running [(:running session) (get message "id")]})))

                     ;; no _meta version and no session: let the server say
                     ;; what is missing
                     :else
                     (execute srv req message {:stateless? true :log log :keepalive-ms keepalive-ms :identity (caller)
                                               :running (stateless-key message)}))))))))))))

(defn serve!
  "Serve srv over HTTP. Options are `handler`'s plus :host (default
  \"127.0.0.1\") and :port (default 0, a free port). Returns {:port :url
  :stop!}; (stop!) stops the server."
  ([srv] (serve! srv {}))
  ([srv {:keys [host port path] :or {host "127.0.0.1" port 0 path "/mcp"} :as opts}]
   (let [s (hk/run-server (handler srv opts)
                          {:ip host :port port :legacy-return-value? false
                           ;; presa checks the size itself, with a JSON-RPC body
                           :max-body (+ 65536 (or (:max-body-bytes opts) default-max-body-bytes))})
         port (hk/server-port s)
         url-host (if (str/includes? host ":") (str "[" host "]") host)]
     {:port port
      :url (str "http://" url-host ":" port path)
      :stop! (fn []
               ;; end subscription streams with their final result first
               (server/end-subscriptions! srv)
               @(hk/server-stop! s {:timeout 1000})
               nil)})))
