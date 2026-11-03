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
            [presa.connection :as connection]
            [presa.jsonrpc :as rpc]))

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
         write! (fn [message]
                  (let [line (rpc/write-line message)]
                    (locking lock
                      (.write ^java.io.Writer out ^String line)
                      (.flush ^java.io.Writer out))))
         {:keys [receive! close!]} (connection/open srv {:send write! :log log})]
     (loop []
       (if-let [line (.readLine in)]
         (do (when-not (str/blank? line)
               (let [message (try (rpc/parse-line line)
                                  (catch Exception e
                                    (log "parse error: " (ex-message e))
                                    ::unparsed))]
                 (if (= ::unparsed message)
                   (write! (rpc/error nil :parse-error "Parse error"))
                   (receive! message))))
             (recur))
         (close!))))))
