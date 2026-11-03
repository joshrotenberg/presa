(ns presa.transport.stdio
  "MCP over stdio: newline-delimited JSON-RPC, one message per line.
  `serve!` serves a presa.server on this process's stdin and stdout;
  `command` is a presa.client transport to a server subprocess.

  Serving: requests come on stdin and responses go to stdout. Nothing else may
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

;; Client side

(defn command
  "A presa.client transport that starts cmd, a vector of strings, as a
  subprocess and speaks to it over its stdin and stdout. Options:

  - :env     extra environment variables, a map of strings
  - :dir     the working directory
  - :stderr  :inherit (default) to pass the server's stderr through, or
             (fn [line]) to receive each line
  - :close-timeout-ms  how long close waits for the process to exit after
             its stdin closes, before destroying it (default 2000)"
  ([cmd] (command cmd {}))
  ([cmd {:keys [env dir stderr close-timeout-ms] :or {stderr :inherit close-timeout-ms 2000}}]
   (fn [{:keys [on-message on-close]}]
     (let [pb (java.lang.ProcessBuilder. ^java.util.List (mapv str cmd))
           _ (when dir (.directory pb (io/file dir)))
           _ (when env (let [e (.environment pb)] (doseq [[k v] env] (.put e (str k) (str v)))))
           _ (when (= :inherit stderr) (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT))
           process (.start pb)
           out (io/writer (.getOutputStream process) :encoding "UTF-8")
           lock (Object.)
           closed (atom false)
           daemon (fn [f] (doto (Thread. ^Runnable f) (.setDaemon true) (.start)))
           reader (daemon
                   (fn []
                     (try
                       (with-open [in (io/reader (.getInputStream process) :encoding "UTF-8")]
                         (doseq [line (line-seq in)]
                           (when-not (str/blank? line)
                             (when-let [message (try (rpc/parse-line line) (catch Exception _ nil))]
                               (try (on-message message) (catch Throwable _))))))
                       (catch Exception _)
                       (finally
                         (reset! closed true)
                         (when on-close (on-close))))))]
       (when (fn? stderr)
         (daemon (fn []
                   (try (with-open [err (io/reader (.getErrorStream process) :encoding "UTF-8")]
                          (doseq [line (line-seq err)] (stderr line)))
                        (catch Exception _)))))
       {:send! (fn [message]
                 (when @closed
                   (throw (ex-info "The server process has exited" {:kind :closed})))
                 (let [line (rpc/write-line message)]
                   (locking lock
                     (.write ^java.io.Writer out ^String line)
                     (.flush ^java.io.Writer out))))
        :close! (fn []
                  (try (.close ^java.io.Writer out) (catch Exception _))
                  (when-not (.waitFor process (long close-timeout-ms) java.util.concurrent.TimeUnit/MILLISECONDS)
                    (.destroy process)
                    (.waitFor process (long 1000) java.util.concurrent.TimeUnit/MILLISECONDS))
                  (.join ^Thread reader 1000))}))))
