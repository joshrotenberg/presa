(ns presa.transport.http
  "MCP Streamable HTTP: a server over http-kit and a client transport over
  java.net.http, both bundled with Babashka.

  The server takes POSTs on one path. A 2026-07-28 request is independent:
  its version comes from _meta and must match the MCP-Protocol-Version
  header, and Mcp-Method (and Mcp-Name for tools/call, prompts/get and
  resources/read) must match the body. The response is JSON, or an event
  stream once the request sends progress or log notifications. An
  initialize-era client gets a session: initialize answers with an
  Mcp-Session-Id that later requests carry, and DELETE ends it.

  Origins other than loopback ones are refused (403) unless allowed, which
  guards a local server against DNS rebinding."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as hk]
            [presa.auth :as auth]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.server :as server])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers HttpResponse
                          HttpResponse$BodyHandlers)
           (java.time Duration)
           (java.util Base64 UUID)))

(defn- write-json [v] (json/write-str v :escape-slash false :escape-unicode false))

;; Header values

(defn encode-header-value
  "A value as an MCP header carries it: printable ASCII without surrounding
  whitespace as is, anything else as =?base64?...?=. Booleans and integers
  in their JSON form."
  [v]
  (let [s (if (string? v) v (write-json v))]
    (if (and (re-matches #"[\x20-\x7E]*" s) (= s (str/trim s)))
      s
      (str "=?base64?" (.encodeToString (Base64/getEncoder) (.getBytes ^String s "UTF-8")) "?="))))

(defn decode-header-value
  "The value of a header, decoding =?base64?...?=; nil when it is malformed."
  [s]
  (when (string? s)
    (let [s (str/trim s)]
      (if-let [[_ b64] (re-matches #"=\?base64\?(.*)\?=" s)]
        ;; strict base64: padded, and only the base64 alphabet
        (when (and (zero? (mod (count b64) 4)) (re-matches #"[A-Za-z0-9+/]*={0,2}" b64))
        (try
          (let [decoder (-> (java.nio.charset.StandardCharsets/UTF_8)
                            (.newDecoder)
                            (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT))]
            (str (.decode decoder (java.nio.ByteBuffer/wrap (.decode (Base64/getDecoder) ^String b64)))))
          (catch Exception _ nil)))
        s))))

(def ^:private name-param
  {"tools/call" "name" "prompts/get" "name" "resources/read" "uri"
   "tasks/get" "taskId" "tasks/update" "taskId" "tasks/cancel" "taskId"})

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

(declare param-headers)

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

(defn- new-session [session]
  {:session (atom session)
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
            gone (promise)
            emit! (fn [m]
                    (when-not (locking lock
                                (or @closed
                                    (do (when-not @streaming
                                          (hk/send! ch {:status 200 :headers (merge sse-headers headers)} false)
                                          (reset! streaming true))
                                        (hk/send! ch (sse-event m) false))))
                      ;; the write failed: the client has gone
                      (deliver gone true)))
            outgoing (:outgoing session)
            client-request (when session
                             (fn [method params timeout-ms]
                               (let [id (str "presa-" (swap! (:next-id session) inc))
                                     p (promise)]
                                 (swap! outgoing assoc id p)
                                 (try
                                   (emit! (rpc/request id method params))
                                   (let [r (deref p timeout-ms ::timeout)]
                                     (when (= ::timeout r)
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
            work (future
                   (binding [*out* *err*]
                     (try
                       (let [response (respond)]
                         (locking lock
                           (when-not @closed
                             (reset! closed true)
                             (cond
                               @streaming (if response
                                            (hk/send! ch (sse-event response) true)
                                            (hk/close ch))
                               response (hk/send! ch (update (json-response (error-status response stateless?) response)
                                                             :headers merge headers)
                                                  true)
                               :else (hk/send! ch {:status 204 :headers headers} true)))))
                       (catch Throwable e
                         (log "failed to answer a request: " (ex-message e))
                         (try (hk/close ch) (catch Throwable _)))
                       (finally
                         (when-let [[registry k] (and (some? request-id) running)]
                           (swap! registry dissoc k))))))]
        ;; running is [registry key]: where a posted notifications/cancelled
        ;; can find this request
        (when-let [[registry k] (and (some? request-id) running)]
          (swap! registry assoc k {:cancel! cancel! :work work}))
        (when (= "subscriptions/listen" (get message "method"))
          ;; keep idle subscription streams open through proxies
          (future
            (loop []
              (Thread/sleep (long keepalive-ms))
              (when (locking lock
                      (when (and @streaming (not @closed))
                        (or (hk/send! ch ": keepalive\n\n" false)
                            (do (deliver gone true) false))))
                (recur)))))
        ;; A failed write is how http-kit learns of some disconnects.
        (future
          (when @gone
            (when-not (locking lock (let [done @closed] (reset! closed true) done))
              (when cancel! (cancel!))
              (future-cancel work))))
        (hk/on-close ch (fn [_]
                          (when-not (locking lock (let [done @closed] (reset! closed true) done))
                            ;; the client went away first: cancel its request
                            (when cancel! (cancel!))
                            (future-cancel work))))))}))

(def default-max-body-bytes 2000000)

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
  - :log              (fn [& parts]) for diagnostics"
  ([srv] (handler srv {}))
  ([srv {:keys [path allowed-origins allowed-hosts log keepalive-ms max-body-bytes cancellation]
         auth-config :auth
         :or {path "/mcp" log (fn [& _]) keepalive-ms 15000 max-body-bytes default-max-body-bytes}}]
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
                            (when (= (:subject entry) (:subject (caller))) entry)))]
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
                 (server/close-session! srv @(:session entry))
                 (when-let [ch @(:stream entry)] (try (hk/close ch) (catch Throwable _)))
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
                 message (if (nil? body) ::too-large (try (rpc/parse-line body) (catch Exception _ ::unparsed)))
                 session (when sid (session-of sid))]
             (case message
               ::too-large (rejection 413 :invalid-request (str "Request body exceeds " max-body-bytes " bytes"))
               ::unparsed (rejection 400 :parse-error "Parse error")
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

;; Client

(def ^:private header-token #"^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$")

(defn param-headers
  "The Mcp-Param-* headers a tool's input schema declares with x-mcp-header,
  as [[header-name property type] ...], or ::invalid when an annotation
  breaks the rules (a client must not call such a tool)."
  [input-schema]
  (let [props (get input-schema "properties")
        found (for [[prop schema] (when (map? props) props)
                    :when (and (map? schema) (contains? schema "x-mcp-header"))]
                [(get schema "x-mcp-header") prop (get schema "type")])
        names (map (comp str/lower-case str first) found)]
    (if (or (some (fn [[h _ t]]
                    (or (not (string? h)) (not (re-matches header-token h))
                        (not (#{"string" "integer" "boolean"} t))))
                  found)
            (not= (count names) (count (set names))))
      ::invalid
      (vec found))))

(defn- filter-tools
  "Drop tools whose header annotations are invalid from a tools/list
  result, remembering the valid annotations by tool name."
  [message annotations log]
  (if-let [tools (get-in message ["result" "tools"])]
    (assoc-in message ["result" "tools"]
              (vec (keep (fn [tool]
                           (let [found (param-headers (get tool "inputSchema"))]
                             (if (= ::invalid found)
                               (do (log "skipping tool " (get tool "name") ": invalid x-mcp-header annotations")
                                   nil)
                               (do (swap! annotations assoc (get tool "name") found)
                                   tool))))
                         tools)))
    message))

(defn- request-headers [message state annotations]
  (let [{:strs [method params]} message
        version (or (get-in params ["_meta" (:protocol-version protocol/meta-keys)])
                    (:version @state))
        name-key (get name-param method)]
    (cond-> {"content-type" "application/json"
             "accept" "application/json, text/event-stream"}
      version (assoc "mcp-protocol-version" version)
      method (assoc "mcp-method" method)
      (and name-key (string? (get params name-key))) (assoc "mcp-name" (encode-header-value (get params name-key)))
      (:session-id @state) (assoc "mcp-session-id" (:session-id @state))
      (= "tools/call" method)
      (merge (into {}
                   (keep (fn [[h prop _]]
                           (let [v (get-in params ["arguments" prop])]
                             (when (some? v) [(str "mcp-param-" h) (encode-header-value v)]))))
                   (get @annotations (get params "name")))))))

(defn- read-events
  "Call f with each message in an event stream, until it ends. Returns the
  stream's last event id and retry interval: {:last-event-id :retry-ms}."
  [^java.io.InputStream in f]
  (with-open [r (io/reader in :encoding "UTF-8")]
    (loop [data [] seen {}]
      (if-let [line (.readLine ^java.io.BufferedReader r)]
        (cond
          (str/blank? line)
          (do (when (seq data)
                (when-let [m (try (rpc/parse-line (str/join "\n" data)) (catch Exception _ nil))]
                  (f m)))
              (recur [] seen))

          (str/starts-with? line "data:")
          (recur (conj data (str/triml (subs line 5))) seen)

          (str/starts-with? line "id:")
          (recur data (assoc seen :last-event-id (str/trim (subs line 3))))

          (str/starts-with? line "retry:")
          (recur data (if-let [ms (parse-long (str/trim (subs line 6)))] (assoc seen :retry-ms ms) seen))

          :else (recur data seen))
        seen))))

(def ^:private ^:dynamic *listing*
  "Bound while the transport lists tools for itself: receives the messages
  of that request instead of the client."
  nil)

(defn url
  "A presa.client transport to the Streamable HTTP endpoint at endpoint.
  Options: :headers (extra headers on every request, such as
  Authorization), :connect-timeout-ms (default 10000), :log."
  ([endpoint] (url endpoint {}))
  ([endpoint {:keys [headers connect-timeout-ms log] :or {connect-timeout-ms 10000 log (fn [& _])}}]
   (fn [{:keys [on-message on-close]}]
     (let [client (-> (HttpClient/newBuilder)
                      (.version HttpClient$Version/HTTP_1_1)
                      (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                      (.build))
           uri (URI. endpoint)
           state (atom {})
           annotations (atom {})
           ;; open event streams by request id, closed to cancel the request
           streams (atom {})
           closed (atom false)
           ;; ids of the responses delivered, to tell a stream that ended
           ;; before its response from one that carried it
           answered (atom #{})
           deliver! (fn [m]
                      (when (#{:response :error} (rpc/kind m))
                        (swap! answered conj (get m "id")))
                      (let [m (filter-tools m annotations log)]
                        (if *listing*
                          (*listing* m)
                          (do
                            (when (and (= (get m "id") (:initialize-id @state)) (get-in m ["result" "protocolVersion"]))
                              (swap! state assoc :version (get-in m ["result" "protocolVersion"])))
                            (on-message m)))))
           open-get (fn [last-event-id]
                      ;; A GET event stream for an initialize-era session:
                      ;; the session's own stream, or a resumed POST stream.
                      (let [builder (-> (HttpRequest/newBuilder uri)
                                        (.header "accept" "text/event-stream")
                                        (.GET))]
                        (when-let [sid (:session-id @state)] (.header builder "mcp-session-id" sid))
                        (when-let [v (:version @state)] (.header builder "mcp-protocol-version" v))
                        (when last-event-id (.header builder "last-event-id" last-event-id))
                        (doseq [[k v] headers] (.header builder (str k) (str v)))
                        (let [^HttpResponse resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofInputStream))
                              content-type (str/lower-case (.orElse (.firstValue (.headers resp) "content-type") ""))]
                          (if (and (= 200 (.statusCode resp)) (str/starts-with? content-type "text/event-stream"))
                            resp
                            (do (.close ^java.io.InputStream (.body resp)) nil)))))
           resume! (fn [id {:keys [last-event-id retry-ms]}]
                     ;; The server ended a POST stream before the response:
                     ;; reconnect with GET and Last-Event-ID, after retry-ms.
                     (loop [seen {:last-event-id last-event-id :retry-ms retry-ms} attempts 0]
                       (when (and (not @closed) (not (contains? @answered id)) (< attempts 5))
                         (Thread/sleep (long (or (:retry-ms seen) 1000)))
                         (when-let [^HttpResponse resp (open-get (:last-event-id seen))]
                           (let [more (try (read-events (.body resp) deliver!) (catch java.io.IOException _ {}))]
                             (recur (merge seen more) (inc attempts)))))))
           session-stream! (fn [ready]
                             ;; The standalone stream an initialize-era server
                             ;; uses for requests and notifications outside a
                             ;; request; stops when the server has none (405).
                             ;; ready is delivered once the first attempt is
                             ;; answered, so later requests can wait for it.
                             (future
                               (loop [seen {}]
                                 (when-not @closed
                                   (when-let [^HttpResponse resp (try (open-get (:last-event-id seen))
                                                                      (catch Exception _ nil)
                                                                      (finally (deliver ready true)))]
                                     (swap! state assoc :session-stream (.body resp))
                                     (let [more (try (read-events (.body resp) deliver!) (catch java.io.IOException _ {}))
                                           seen (merge seen more)]
                                       (when-not @closed
                                         (Thread/sleep (long (or (:retry-ms seen) 1000)))
                                         (recur seen))))))))
           post (fn [message]
                  (let [builder (HttpRequest/newBuilder uri)]
                    (doseq [[k v] (merge (request-headers message state annotations) headers)]
                      (.header builder (str k) (str v)))
                    (.POST builder (HttpRequest$BodyPublishers/ofString (write-json message)))
                    (let [^HttpResponse resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofInputStream))
                          status (.statusCode resp)
                          content-type (str/lower-case (.orElse (.firstValue (.headers resp) "content-type") ""))
                          id (get message "id")]
                      (when-let [sid (.orElse (.firstValue (.headers resp) "mcp-session-id") nil)]
                        (swap! state assoc :session-id sid))
                      (cond
                        (str/starts-with? content-type "text/event-stream")
                        (let [body (.body resp)
                              _ (when (some? id) (swap! streams update id assoc :body body))
                              seen (try (read-events body deliver!)
                                        (catch java.io.IOException e
                                          (when-not (contains? @streams id) (throw e))
                                          ::cancelled)
                                        (finally (when (some? id) (swap! streams dissoc id))))]
                          (when (and (some? id) (map? seen) (:session-id @state)
                                     (not (contains? @answered id)))
                            (resume! id seen)))

                        (str/starts-with? content-type "application/json")
                        (let [body (slurp (.body resp) :encoding "UTF-8")
                              m (try (rpc/parse-line body) (catch Exception _ nil))]
                          (if (and (map? m) (#{:response :error} (rpc/kind m)))
                            (deliver! m)
                            (when (some? id)
                              (deliver! (rpc/error id -32000 (str "HTTP " status " with an unreadable body")
                                                   {"status" status})))))

                        :else
                        (do (.close ^java.io.InputStream (.body resp))
                            (when (and (some? id) (not (<= 200 status 299)))
                              (deliver! (rpc/error id -32000 (str "HTTP " status) {"status" status}))))))))
           list-tools! (fn [message]
                         ;; Learn the tools' x-mcp-header annotations before
                         ;; calling one this transport has not seen listed.
                         (let [meta (get-in message ["params" "_meta"])]
                           (loop [cursor nil pages 0]
                             (let [id (str "presa-tools-" (swap! state update :list-id (fnil inc 0)) "-" pages)
                                   result (atom nil)
                                   request (rpc/request id "tools/list" (cond-> {}
                                                                          meta (assoc "_meta" (dissoc meta "progressToken"))
                                                                          cursor (assoc "cursor" cursor)))]
                               (binding [*listing* (fn [m] (when (= id (get m "id")) (reset! result m)))]
                                 (post request))
                               (let [next-cursor (get-in @result ["result" "nextCursor"])]
                                 (when (and (string? next-cursor) (< pages 100))
                                   (recur next-cursor (inc pages))))))))]
       {:send! (fn [message]
                 (when @closed
                   (throw (ex-info "The transport is closed" {:kind :closed})))
                 (when (= "initialize" (get message "method"))
                   (swap! state assoc :initialize-id (get message "id")))
                 (if-let [{:keys [^java.io.InputStream body]}
                          (and (= "notifications/cancelled" (get message "method"))
                               (get @streams (get-in message ["params" "requestId"])))]
                   ;; over HTTP, closing the response stream (and with it the
                   ;; connection) cancels the request
                   (do (swap! streams dissoc (get-in message ["params" "requestId"]))
                       (try (.close body) (catch Exception _)))
                 (future
                   (try (when (and (= "tools/call" (get message "method"))
                                   (not (contains? @annotations (get-in message ["params" "name"]))))
                          (try (list-tools! message) (catch Exception _)))
                        ;; a request sent just after initialized waits for the
                        ;; session stream, where the server may answer it
                        (when-let [ready (and (not= "notifications/initialized" (get message "method"))
                                              (:stream-ready @state))]
                          (deref ready 2000 nil))
                        (post message)
                        (when (and (= "notifications/initialized" (get message "method"))
                                   (:session-id @state))
                          (let [ready (promise)]
                            (swap! state assoc :stream-ready ready)
                            (session-stream! ready)))
                        (catch Throwable e
                          (log "HTTP request failed: " (.getName (class e)) ": " (ex-message e)
                               (when-let [c (ex-cause e)] (str " (" (.getName (class c)) ": " (ex-message c) ")")))
                          (when-let [id (get message "id")]
                            (on-message (rpc/error id -32000 (str "HTTP request failed: " (ex-message e))))))))))
        :close! (fn []
                  (when (compare-and-set! closed false true)
                    (when-let [^java.io.InputStream s (:session-stream @state)]
                      (try (.close s) (catch Exception _)))
                    (when-let [sid (:session-id @state)]
                      (try
                        (.send client (-> (HttpRequest/newBuilder uri)
                                          (.header "mcp-session-id" sid)
                                          (.DELETE)
                                          (.build))
                               (HttpResponse$BodyHandlers/discarding))
                        (catch Exception _)))
                    (when on-close (on-close))))}))))
