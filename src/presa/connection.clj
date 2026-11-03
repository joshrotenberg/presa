(ns presa.connection
  "One client's connection to a presa.server, independent of the transport:
  it keeps the session, runs each request on its own thread, and cancels
  requests on notifications/cancelled. stdio, HTTP and the in-process client
  transport feed it messages and give it a function that sends messages
  back."
  (:require [presa.server :as server]))

(defn open
  "Open a connection to srv. Options:

  - :send  (fn [message]) delivers a response or notification to the client
           (required). It may be called from several threads at once.
  - :log   (fn [& parts]) for diagnostics
  - :session  the initial session (default {})

  Returns a map:

  - :receive!  (fn [message]) takes one parsed message from the client. It
               settles the session before it returns, and runs the work on
               another thread.
  - :close!    (fn []) waits for requests in flight and returns
  - :session   (fn []) the current session

  Handlers run with *out* bound to *err*, so printing never reaches a
  protocol stream on stdout."
  [srv {:keys [send log session] :or {log (fn [& _]) session {}}}]
  (let [session (atom session)
        lock (Object.)
        ;; requests in flight by id: {:cancel! f :work future :token t}
        running (atom {})
        settle (fn [message]
                 (locking lock
                   (let [prepared (server/prepare srv @session message {:notify send})]
                     (reset! session (:session prepared))
                     prepared)))
        receive! (fn [message]
                   (let [{:keys [respond request-id cancel! cancel]} (settle message)]
                     (when-let [{:keys [cancel! work]} (and (some? cancel) (get @running cancel))]
                       (cancel!)
                       (when work (future-cancel work)))
                     (if (nil? request-id)
                       ;; notifications and invalid messages: nothing to run
                       (when-let [response (respond)] (send response))
                       (let [token (Object.)
                             mine? (fn [m] (= token (get-in m [request-id :token])))
                             _ (swap! running assoc request-id {:cancel! cancel! :token token})
                             work (future
                                    (binding [*out* *err*]
                                      (try
                                        (when-let [response (respond)]
                                          (send response))
                                        (catch Throwable e
                                          (log "failed to send a response: " (ex-message e)))
                                        (finally
                                          (swap! running #(if (mine? %) (dissoc % request-id) %))))))]
                         (swap! running #(if (mine? %) (assoc-in % [request-id :work] work) %))))
                     nil))]
    {:receive! receive!
     :close! (fn []
               (run! (fn [{:keys [work]}] (when work (try @work (catch Exception _))))
                     (vals @running))
               nil)
     :session (fn [] @session)}))
