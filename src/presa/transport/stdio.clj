(ns presa.transport.stdio
  "Serve a presa.server over stdio: newline-delimited JSON-RPC, one message
  per line, requests on stdin and responses on stdout. Nothing else may
  reach stdout, so logs go to stderr, and so does anything a tool handler
  prints to *out*.

  Messages are read in order and each one settles the session before the
  next is read; the work of each request then runs on its own thread, so a
  slow tool call does not hold up the others. Responses can therefore come
  back in a different order from their requests, which JSON-RPC allows."
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
     (loop [session {}
            in-flight []]
       (if-let [line (.readLine in)]
         (if (str/blank? line)
           (recur session in-flight)
           (let [message (try (rpc/parse-line line)
                              (catch Exception e
                                (log "parse error: " (ex-message e))
                                ::unparsed))]
             (if (= ::unparsed message)
               (do (write! (rpc/error nil :parse-error "Parse error"))
                   (recur session in-flight))
               (let [{session' :session respond :respond} (server/prepare srv session message)
                     work (future
                            (binding [*out* *err*]
                              (try
                                (write! (respond))
                                (catch Throwable e
                                  (log "failed to write a response: " (ex-message e))))))]
                 (recur session' (conj (filterv (complement realized?) in-flight) work))))))
         (do (run! deref in-flight)
             nil))))))
