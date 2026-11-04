(ns ^:no-doc presa.transport.http.client
  "Internal to presa.transport.http; not part of the API. The Streamable HTTP
  client transport over java.net.http."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [presa.auth.client :as auth-client]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.transport.http.headers :refer [encode-header-value name-param param-headers write-json]])
  (:import (java.net URI)
           (java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers)
           (java.time Duration)))

(defn- filter-tools
  "Drop tools whose header annotations are invalid from a tools/list
  result, remembering the valid annotations by tool name."
  [message annotations log]
  (if-let [tools (get-in message ["result" "tools"])]
    (assoc-in message ["result" "tools"]
              (vec (keep (fn [tool]
                           (let [found (param-headers (get tool "inputSchema"))]
                             (if (= :presa.transport.http/invalid found)
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

(defn- auth-headers
  "The authorization headers of a request with token: the provider's
  :headers (DPoP adds a proof per request), else Authorization: Bearer."
  [token-provider endpoint token method]
  (when token
    (if-let [f (:headers token-provider)]
      (f {:token token :method method :url endpoint})
      {"authorization" (str "Bearer " token)})))


;; A connection: what url's transport shares between requests
;;
;; :client :uri :endpoint :headers :token-provider :log :on-message :on-close
;; :state        {:session-id :version :initialize-id :session-stream
;;                :stream-ready :list-id}
;; :annotations  tool name -> its x-mcp-header annotations
;; :streams      request id -> {:body stream}, open POST event streams;
;;               closing one cancels its request
;; :closed       true once close! has run
;; :answered     ids of the responses delivered, to tell a stream that
;;               ended before its response from one that carried it

(defn- with-headers
  "Add each header of headers to builder; returns builder."
  ^java.net.http.HttpRequest$Builder [^java.net.http.HttpRequest$Builder builder headers]
  (doseq [[k v] headers] (.header builder (str k) (str v)))
  builder)

(defn- token
  "The provider's current token, or nil without a provider."
  [{:keys [token-provider endpoint]}]
  (when token-provider ((:token token-provider) {:url endpoint})))

(defn- response-header [^HttpResponse resp k]
  (.orElse (.firstValue (.headers resp) k) nil))

(defn- deliver!
  "Hand message m to the client, or to a tools listing of the transport's
  own, noting a response's id and an initialize result's version."
  [{:keys [state annotations answered log on-message]} m]
  (when (#{:response :error} (rpc/kind m))
    (swap! answered conj (get m "id")))
  (let [m (filter-tools m annotations log)]
    (if *listing*
      (*listing* m)
      (do
        (when (and (= (get m "id") (:initialize-id @state)) (get-in m ["result" "protocolVersion"]))
          (swap! state assoc :version (get-in m ["result" "protocolVersion"])))
        (on-message m)))))

(defn- open-get
  "A GET event stream for an initialize-era session: the session's own
  stream, or a resumed POST stream. The response, or nil when the server
  answers with anything but an event stream."
  [{:keys [^HttpClient client uri state headers token-provider endpoint] :as conn} last-event-id]
  (let [builder (-> (HttpRequest/newBuilder uri)
                    (.header "accept" "text/event-stream")
                    (.GET))]
    (when-let [sid (:session-id @state)] (.header builder "mcp-session-id" sid))
    (when-let [v (:version @state)] (.header builder "mcp-protocol-version" v))
    (with-headers builder (auth-headers token-provider endpoint (token conn) "GET"))
    (when last-event-id (.header builder "last-event-id" last-event-id))
    (with-headers builder headers)
    (let [^HttpResponse resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofInputStream))
          content-type (str/lower-case (or (response-header resp "content-type") ""))]
      (if (and (= 200 (.statusCode resp)) (str/starts-with? content-type "text/event-stream"))
        resp
        (do (.close ^java.io.InputStream (.body resp)) nil)))))

(defn- for-request
  "A deliver function for a request's own stream: what arrives there was
  sent for that request."
  [conn id]
  (fn [m] (deliver! conn (vary-meta m assoc :presa/related-request id))))

(defn- resume!
  "The server ended a POST stream before the response: reconnect with GET
  and Last-Event-ID, after retry-ms, up to five times."
  [{:keys [closed answered] :as conn} id {:keys [last-event-id retry-ms]}]
  (loop [seen {:last-event-id last-event-id :retry-ms retry-ms} attempts 0]
    (when (and (not @closed) (not (contains? @answered id)) (< attempts 5))
      (Thread/sleep (long (or (:retry-ms seen) 1000)))
      (when-let [^HttpResponse resp (open-get conn (:last-event-id seen))]
        (let [more (try (read-events (.body resp) (for-request conn id)) (catch java.io.IOException _ {}))]
          (recur (merge seen more) (inc attempts)))))))

(defn- session-stream!
  "The standalone stream an initialize-era server uses for requests and
  notifications outside a request; stops when the server has none (405).
  ready is delivered once the first attempt is answered, so later requests
  can wait for it."
  [{:keys [closed state] :as conn} ready]
  (future
    (loop [seen {}]
      (when-not @closed
        (when-let [^HttpResponse resp (try (open-get conn (:last-event-id seen))
                                           (catch Exception _ nil)
                                           (finally (deliver ready true)))]
          (swap! state assoc :session-stream (.body resp))
          (let [more (try (read-events (.body resp) #(deliver! conn %)) (catch java.io.IOException _ {}))
                seen (merge seen more)]
            (when-not @closed
              (Thread/sleep (long (or (:retry-ms seen) 1000)))
              (recur seen))))))))

;; Posting a message

(defn- send-post
  "POST message with token; hands a DPoP-Nonce response header to the
  provider's :nonce!."
  [{:keys [^HttpClient client uri headers token-provider endpoint state annotations]} message token]
  (let [builder (-> (HttpRequest/newBuilder uri)
                    (with-headers (merge (request-headers message state annotations) headers))
                    (with-headers (auth-headers token-provider endpoint token "POST")))]
    (.POST builder (HttpRequest$BodyPublishers/ofString (write-json message)))
    (let [^HttpResponse resp (.send client (.build builder) (HttpResponse$BodyHandlers/ofInputStream))]
      (when-let [nonce (response-header resp "dpop-nonce")]
        (when-let [f (:nonce! token-provider)] (f endpoint nonce)))
      resp)))

(defn- send-post*
  "send-post, once more with the same token and a proof carrying the new
  nonce after a 401 use_dpop_nonce."
  [{:keys [token-provider] :as conn} message token]
  (let [^HttpResponse resp (send-post conn message token)
        header (response-header resp "www-authenticate")]
    (if (and token (:nonce! token-provider) (= 401 (.statusCode resp))
             (some? (response-header resp "dpop-nonce"))
             (= "use_dpop_nonce" (get-in (when header (auth-client/bearer-challenge header))
                                         [:params "error"])))
      (do (.close ^java.io.InputStream (.body resp))
          (send-post conn message token))
      resp)))

(defn- challenge-of
  "{:status :challenge :header} for a 401, or a 403 insufficient_scope;
  nil for any other response."
  [^HttpResponse resp]
  (let [status (.statusCode resp)
        header (response-header resp "www-authenticate")
        c (when header (auth-client/bearer-challenge header))]
    (when (or (= 401 status)
              (and (= 403 status) (= "insufficient_scope" (get-in c [:params "error"]))))
      {:status status :challenge c :header header})))

(defn- refused
  "Tell the client that request id was refused, with the status and
  challenge."
  [conn id {:keys [status header]} & [cause]]
  (when (some? id)
    (deliver! conn (rpc/error id -32000 (str "HTTP " status (when cause (str ": " cause)))
                              (cond-> {"status" status}
                                header (assoc "challenge" header))))))

(defn- authorized-response
  "The response to message. A 401, or a 403 asking for more scope, goes to
  the token provider once, then the request is sent again. nil when the
  request was refused; refused has told the client."
  [{:keys [token-provider endpoint] :as conn} message]
  (let [id (get message "id")
        token (token conn)
        first-try (send-post* conn message token)]
    (if-let [refusal (challenge-of first-try)]
      (do (.close ^java.io.InputStream (.body first-try))
          (if-not token-provider
            (do (refused conn id refusal) nil)
            (let [new-token (try ((:refresh token-provider)
                                  (assoc refusal :token token :url endpoint))
                                 (catch Exception e
                                   (refused conn id refusal (or (ex-message e) "authorization failed"))
                                   :presa.transport.http/failed))]
              (when-not (= :presa.transport.http/failed new-token)
                (let [again (send-post* conn message new-token)]
                  (if-let [refusal (challenge-of again)]
                    (do (.close ^java.io.InputStream (.body again)) (refused conn id refusal) nil)
                    again))))))
      first-try)))

(defn- read-response!
  "Deliver what resp carries for message: an event stream (resumed when it
  ends before the response), a JSON response, or an error for a failing
  status."
  [{:keys [state streams answered] :as conn} message ^HttpResponse resp]
  (let [id (get message "id")
        status (.statusCode resp)
        content-type (str/lower-case (or (response-header resp "content-type") ""))]
    (when-let [sid (response-header resp "mcp-session-id")]
      (swap! state assoc :session-id sid))
    (cond
      (str/starts-with? content-type "text/event-stream")
      (let [body (.body resp)
            _ (when (some? id) (swap! streams update id assoc :body body))
            seen (try (read-events body (if (and (some? id) (contains? message "method"))
                                          (for-request conn id)
                                          #(deliver! conn %)))
                      (catch java.io.IOException e
                        (when-not (contains? @streams id) (throw e))
                        :presa.transport.http/cancelled)
                      (finally (when (some? id) (swap! streams dissoc id))))]
        (when (and (some? id) (map? seen) (:session-id @state)
                   (not (contains? @answered id)))
          (resume! conn id seen)))

      (str/starts-with? content-type "application/json")
      (let [body (slurp (.body resp) :encoding "UTF-8")
            m (try (rpc/parse-line body) (catch Exception _ nil))]
        (if (and (map? m) (#{:response :error} (rpc/kind m)))
          (deliver! conn m)
          (when (some? id)
            (deliver! conn (rpc/error id -32000 (str "HTTP " status " with an unreadable body")
                                      {"status" status})))))

      :else
      (do (.close ^java.io.InputStream (.body resp))
          (when (and (some? id) (not (<= 200 status 299)))
            (deliver! conn (rpc/error id -32000 (str "HTTP " status) {"status" status})))))))

(defn- post [conn message]
  (when-let [resp (authorized-response conn message)]
    (read-response! conn message resp)))

(defn- list-tools!
  "Learn the tools' x-mcp-header annotations before calling one this
  transport has not seen listed."
  [{:keys [state] :as conn} message]
  (let [meta (get-in message ["params" "_meta"])]
    (loop [cursor nil pages 0]
      (let [id (str "presa-tools-" (:list-id (swap! state update :list-id (fnil inc 0))) "-" pages)
            result (atom nil)
            request (rpc/request id "tools/list" (cond-> {}
                                                   meta (assoc "_meta" (dissoc meta "progressToken"))
                                                   cursor (assoc "cursor" cursor)))]
        (binding [*listing* (fn [m] (when (= id (get m "id")) (reset! result m)))]
          (post conn request))
        (let [next-cursor (get-in @result ["result" "nextCursor"])]
          (when (and (string? next-cursor) (< pages 100))
            (recur next-cursor (inc pages))))))))

;; The transport

(defn- send!
  "Send message on its own thread, or cancel a request by closing its
  stream."
  [{:keys [closed state streams annotations log on-message] :as conn} message]
  (when @closed
    (throw (ex-info "The transport is closed" {:kind :closed})))
  (when (= "initialize" (get message "method"))
    (swap! state assoc :initialize-id (get message "id")))
  (if-let [{:keys [^java.io.InputStream body]}
           (and (= "notifications/cancelled" (get message "method"))
                (get @streams (get-in message ["params" "requestId"])))]
    ;; over HTTP, closing the response stream (and with it the connection)
    ;; cancels the request
    (do (swap! streams dissoc (get-in message ["params" "requestId"]))
        (try (.close body) (catch Exception _)))
    ;; Each message is posted on its own thread. The promise a later request
    ;; waits on is made here, in send order, so a request sent just after
    ;; initialized cannot be posted before the session stream opens.
    (let [ready (when (= "notifications/initialized" (get message "method"))
                  (let [p (promise)] (swap! state assoc :stream-ready p) p))]
      (future
        (try (when (and (= "tools/call" (get message "method"))
                        (not (contains? @annotations (get-in message ["params" "name"]))))
               (try (list-tools! conn message) (catch Exception _)))
             ;; a request sent just after initialized waits for the session
             ;; stream, where the server may answer it
             (when-let [ready (and (not= "notifications/initialized" (get message "method"))
                                   (:stream-ready @state))]
               (deref ready 2000 nil))
             (post conn message)
             (when ready
               (if (:session-id @state)
                 (session-stream! conn ready)
                 (deliver ready true)))
             (catch Throwable e
               (log "HTTP request failed: " (.getName (class e)) ": " (ex-message e)
                    (when-let [c (ex-cause e)] (str " (" (.getName (class c)) ": " (ex-message c) ")")))
               (some-> ready (deliver true))
               (when-let [id (get message "id")]
                 (on-message (rpc/error id -32000 (str "HTTP request failed: " (ex-message e)))))))))))

(defn- close!
  "Close the session stream, end an initialize-era session with DELETE, and
  tell the client. Only the first call does anything."
  [{:keys [closed state ^HttpClient client uri token-provider endpoint on-close] :as conn}]
  (when (compare-and-set! closed false true)
    (when-let [^java.io.InputStream s (:session-stream @state)]
      (try (.close s) (catch Exception _)))
    (when-let [sid (:session-id @state)]
      (try
        (.send client (-> (HttpRequest/newBuilder uri)
                          (.header "mcp-session-id" sid)
                          (with-headers (auth-headers token-provider endpoint (token conn) "DELETE"))
                          (.DELETE)
                          (.build))
               (HttpResponse$BodyHandlers/discarding))
        (catch Exception _)))
    (when on-close (on-close))))

(defn url
  "A presa.client transport to the Streamable HTTP endpoint at endpoint.
  Options:

  - :headers         extra headers on every request
  - :token-provider  OAuth: {:token (fn [ctx]) :refresh (fn [refusal])}, such
                     as presa.auth.client/provider. Its token goes in
                     Authorization: Bearer, or in the headers its optional
                     :headers (fn [{:token :method :url}]) returns (DPoP); a
                     401, or a 403 insufficient_scope, goes to :refresh (with
                     :status, :challenge, :token) and the request is sent once
                     more. A second refusal is -32000 with the status and
                     challenge. A DPoP-Nonce response header goes to its
                     optional :nonce! (fn [url nonce]), and a 401
                     use_dpop_nonce is retried once with the same token.
  - :connect-timeout-ms  (default 10000)
  - :log             (fn [& parts]) for diagnostics"
  ([endpoint] (url endpoint {}))
  ([endpoint {:keys [headers connect-timeout-ms log token-provider] :or {connect-timeout-ms 10000 log (fn [& _])}}]
   (when (and token-provider (some #(= "authorization" (str/lower-case (str %))) (keys headers)))
     (throw (ex-info "Pass a token provider or an Authorization header, not both" {:kind :invalid-input})))
   (fn [{:keys [on-message on-close]}]
     (let [conn {:client (-> (HttpClient/newBuilder)
                             (.version HttpClient$Version/HTTP_1_1)
                             (.connectTimeout (Duration/ofMillis connect-timeout-ms))
                             (.build))
                 :uri (URI. endpoint)
                 :endpoint endpoint
                 :headers headers
                 :token-provider token-provider
                 :log log
                 :on-message on-message
                 :on-close on-close
                 :state (atom {})
                 :annotations (atom {})
                 :streams (atom {})
                 :closed (atom false)
                 :answered (atom #{})}]
       {:send! (fn [message] (send! conn message))
        :close! (fn [] (close! conn))}))))
