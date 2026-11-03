(ns presa.transport.stdio
  "Serve a presa.server over stdio: newline-delimited JSON-RPC, one message
  per line, requests on stdin and responses on stdout. Nothing else may
  reach stdout, so logs go to stderr, and so does anything a tool handler
  prints to *out*.

  Messages are read in order and each one settles the session before the
  next is read; the work of each request then runs on its own thread, so a
  slow tool call does not hold up the others. Responses can therefore come
  back in a different order from their requests, which JSON-RPC allows.
  Notifications a handler sends (progress, log messages) go out on stdout
  as they happen. notifications/cancelled interrupts the named request's
  thread and drops its response."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [presa.jsonrpc :as rpc]
            [presa.server :as server]))

(defn- stderr-log [& parts]
  (binding [*out* *err*]
    (println (apply str "presa: " parts))
    (flush)))

(defn serve!
  "Serve srv, a presa.server/server map, until the input ends, then wait for
  requests in flight and return. Options:

  - :in   a java.io.Reader to read requests from (default: stdin)
  - :out  a java.io.Writer to write responses to (default: stdout)
  - :log  (fn [& parts]) for diagnostics (default: a line on stderr)

  Tool handlers run with *out* bound to *err*."
  ([srv] (serve! srv {}))
  ([srv {:keys [in out log]}]
   (let [in (java.io.BufferedReader. (or in (io/reader System/in :encoding "UTF-8")))
         out (or out (io/writer System/out :encoding "UTF-8"))
         log (or log stderr-log)
         lock (Object.)
         write! (fn [response]
                  (when response
                    (let [line (rpc/write-line response)]
                      (locking lock
                        (.write ^java.io.Writer out ^String line)
                        (.flush ^java.io.Writer out)))))]
     ;; Requests in flight by id: {:cancel! f :work future}
     (let [running (atom {})]
       (loop [session {}]
         (if-let [line (.readLine in)]
           (if (str/blank? line)
             (recur session)
             (let [message (try (rpc/parse-line line)
                                (catch Exception e
                                  (log "parse error: " (ex-message e))
                                  ::unparsed))]
               (if (= ::unparsed message)
                 (do (write! (rpc/error nil :parse-error "Parse error"))
                     (recur session))
                 (let [{session' :session :keys [respond request-id cancel! cancel]}
                       (server/prepare srv session message {:notify write!})]
                   (when-let [{:keys [cancel! work]} (and (some? cancel) (get @running cancel))]
                     (cancel!)
                     (when work (future-cancel work)))
                   (let [token (Object.)
                         _ (when (some? request-id)
                             (swap! running assoc request-id {:cancel! cancel! :token token}))
                         work (future
                                (binding [*out* *err*]
                                  (try
                                    (write! (respond))
                                    (catch Throwable e
                                      (log "failed to write a response: " (ex-message e)))
                                    (finally
                                      (when (some? request-id)
                                        (swap! running (fn [m]
                                                         (if (= token (get-in m [request-id :token]))
                                                           (dissoc m request-id)
                                                           m))))))))]
                     (when (some? request-id)
                       (swap! running (fn [m]
                                        (if (= token (get-in m [request-id :token]))
                                          (assoc-in m [request-id :work] work)
                                          m)))))
                   (recur session')))))
           (do (run! (fn [{:keys [work]}] (when work (try @work (catch Exception _)))) (vals @running))
               nil)))))))
