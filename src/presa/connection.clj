(ns presa.connection
  "One client's connection to a presa.server, independent of the transport:
  it keeps the session, runs each request on its own thread, and cancels
  requests on notifications/cancelled. stdio and the in-process client
  transport feed it messages and give it a function that sends messages
  back; the HTTP server keeps its sessions itself."
  (:require [presa.jsonrpc :as rpc]
            [presa.server :as server]))

(defn open
  "Open a connection to srv. Options:

  - :send  (fn [message]) delivers a response or notification to the client
           (required). It may be called from several threads at once. A
           notification or request sent while a request runs has metadata
           :presa/related-request, that request's id.
  - :log   (fn [& parts]) for diagnostics
  - :session  the initial session (default {})
  - :drain-timeout-ms  how long close! lets requests in flight finish
           before it cancels the rest: each gets its cancel!, so
           (:cancelled? context) is true, and its thread is interrupted.
           nil (the default) waits for all of them; 0 cancels at once

  Returns a map:

  - :receive!  (fn [message]) takes one parsed message from the client. It
               settles the session before it returns, and runs the work on
               another thread.
  - :close!    (fn []) waits for requests in flight (see
               :drain-timeout-ms) and returns. Requests sent to the client
               still waiting get :kind :closed.
  - :session   (fn []) the current session

  Handlers run with *out* bound to *err*, so printing never reaches a
  protocol stream on stdout."
  [srv {:keys [send log session drain-timeout-ms] :or {log (fn [& _]) session {}}}]
  (let [session (atom session)
        lock (Object.)
        ;; requests in flight by id: {:cancel! f :work future :token t}
        running (atom {})
        ;; requests sent to the client, by id: promise of the response
        outgoing (atom {})
        next-id (atom 0)
        ;; what a request sends while it runs carries its id, so a client
        ;; transport that keeps metadata can route it to that request
        tag (fn [related m] (if (some? related) (vary-meta m assoc :presa/related-request related) m))
        request (fn [related method params timeout-ms]
                  (let [id (str "presa-" (swap! next-id inc))
                        p (promise)]
                    (swap! outgoing assoc id p)
                    (try
                      (send (tag related (rpc/request id method params)))
                      (let [response (deref p timeout-ms ::timeout)]
                        (when (= ::timeout response)
                          (send (rpc/notification "notifications/cancelled" {"requestId" id "reason" "timeout"}))
                          (throw (ex-info (str method " timed out after " timeout-ms " ms")
                                          {:kind :timeout :method method})))
                        response)
                      (finally (swap! outgoing dissoc id)))))
        settle (fn [message]
                 (locking lock
                   (let [related (when (= :request (rpc/kind message)) (get message "id"))
                         prepared (server/prepare srv @session message
                                                  {:notify #(send (tag related %))
                                                   :session-notify send
                                                   :request (partial request related)})]
                     (reset! session (:session prepared))
                     prepared)))
        answer (fn [message]
                 (when-let [p (and (#{:response :error} (rpc/kind message))
                                   (get @outgoing (get message "id")))]
                   (deliver p message)
                   true))
        receive! (fn [message]
                   (if (answer message)
                     nil
                     (let [{:keys [respond request-id cancel! cancel method]} (settle message)]
                       (when-let [{:keys [cancel! work]} (and (some? cancel) (get @running cancel))]
                         (cancel!)
                         (when work (future-cancel work)))
                       (if (nil? request-id)
                       ;; notifications and invalid messages: nothing to run
                         (when-let [response (respond)] (send response))
                         (let [token (Object.)
                               mine? (fn [m] (= token (get-in m [request-id :token])))
                               _ (swap! running assoc request-id {:cancel! cancel! :token token :method method})
                               work (future
                                      (binding [*out* *err*]
                                        (try
                                          (when-let [response (respond)]
                                            (try (send response)
                                                 (catch Throwable e
                                                   (log "failed to send a response: " (ex-message e))
                                                 ;; the writer fails before writing anything,
                                                 ;; so the request can still be answered
                                                   (send (rpc/error request-id :internal-error
                                                                    (if (:expose-errors? srv) (str "Internal error: the response could not be written: " (ex-message e)) "Internal error"))))))
                                          (catch Throwable e
                                            (log "failed to send a response: " (ex-message e)))
                                          (finally
                                            (swap! running #(if (mine? %) (dissoc % request-id) %))))))]
                           (swap! running #(if (mine? %) (assoc-in % [request-id :work] work) %))))
                       nil)))]
    {:receive! receive!
     :close! (fn []
               (server/close-session! srv @session)
               (doseq [[_ p] @outgoing] (deliver p ::closed))
               ;; subscription streams never finish on their own
               (doseq [{:keys [method cancel! work]} (vals @running)
                       :when (= "subscriptions/listen" method)]
                 (cancel!)
                 (when work (future-cancel work)))
               (if drain-timeout-ms
                 (let [deadline (+ (System/currentTimeMillis) drain-timeout-ms)]
                   (run! (fn [{:keys [work]}]
                           (when work
                             (try (deref work (max 0 (- deadline (System/currentTimeMillis))) nil)
                                  (catch Exception _))))
                         (vals @running))
                   ;; what is still running after the deadline is cancelled
                   (doseq [{:keys [cancel! work]} (vals @running)]
                     (cancel!)
                     (when work (future-cancel work))))
                 (run! (fn [{:keys [work]}] (when work (try @work (catch Exception _))))
                       (vals @running)))
               nil)
     :session (fn [] @session)}))
