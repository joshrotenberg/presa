(ns presa.nrepl.client
  "An nREPL client: bencoded maps over a socket. A request carries an op and
  an id; the server answers with any number of messages carrying that id,
  the last with \"done\" in its status. Messages are maps with string keys."
  (:require [presa.nrepl.bencode :as bencode])
  (:import (java.io BufferedInputStream BufferedOutputStream InputStream OutputStream)
           (java.net InetSocketAddress Socket)
           (java.util.concurrent LinkedBlockingQueue TimeUnit)))

(def closed
  "What a request's queue yields once the connection is gone."
  {"status" ["done" "connection-closed"]})

(defn status? [message s] (boolean (some #{s} (get message "status"))))

(defn done? [message] (status? message "done"))

(defn- read-loop [{:keys [^InputStream in pending open?]}]
  (try
    (loop []
      (when-let [m (bencode/read! in)]
        (when-let [^LinkedBlockingQueue q (get @pending (get m "id"))]
          (.put q m)
          (when (done? m) (swap! pending dissoc (get m "id"))))
        (recur)))
    (catch Exception _ nil)
    (finally
      (reset! open? false)
      (let [[waiting _] (reset-vals! pending {})]
        (doseq [[_ ^LinkedBlockingQueue q] waiting] (.put q closed))))))

(defn connect
  "Connect to the nREPL server at :host (default 127.0.0.1) and :port,
  within :connect-timeout-ms (default 5000). Returns a connection for
  `send!`, `request` and `close`."
  [{:keys [host port connect-timeout-ms] :or {host "127.0.0.1" connect-timeout-ms 5000}}]
  (let [socket (doto (Socket.) (.connect (InetSocketAddress. ^String host (int port)) (int connect-timeout-ms)))
        conn {:socket socket
              :in (BufferedInputStream. (.getInputStream socket))
              :out (BufferedOutputStream. (.getOutputStream socket))
              :pending (atom {})
              :ids (atom 0)
              :open? (atom true)
              :host host
              :port port}]
    (doto (Thread. ^Runnable (fn [] (read-loop conn)) "presa-nrepl-reader")
      (.setDaemon true)
      (.start))
    conn))

(defn open? [conn] @(:open? conn))

(defn close [{:keys [^Socket socket]}]
  (try (.close socket) (catch Exception _ nil))
  nil)

(defn send!
  "Send message (a map with string keys and at least \"op\") under a new id.
  Returns {:id id :queue q}: q is a LinkedBlockingQueue that yields each
  answer in order, the last one `done?`; when the connection ends first it
  yields `closed`."
  [{:keys [^OutputStream out pending ids] :as conn} message]
  (let [id (str (swap! ids inc))
        q (LinkedBlockingQueue.)]
    (swap! pending assoc id q)
    (try
      (locking out
        (bencode/write! out (assoc message "id" id))
        (.flush out))
      (catch Exception e
        (swap! pending dissoc id)
        (throw (ex-info (str "nREPL connection to " (:host conn) ":" (:port conn) " failed: " (ex-message e))
                        {:kind :nrepl-connection} e))))
    (when-not (open? conn) (.put q closed))
    {:id id :queue q}))

(defn poll
  "The next answer of a `send!`, or nil when none arrives within timeout-ms."
  [{:keys [^LinkedBlockingQueue queue]} timeout-ms]
  (.poll queue (long timeout-ms) TimeUnit/MILLISECONDS))

(defn request
  "Send message and return its answers merged into one map, later values
  winning and statuses collected into a set under \"status\". Throws when
  the answers do not finish within timeout-ms (default 10000) or the
  connection closes. For ops with one logical answer (clone, describe,
  lookup, interrupt, stdin); read an eval's answers with `send!` and `poll`."
  ([conn message] (request conn message 10000))
  ([conn message timeout-ms]
   (let [sent (send! conn message)
         deadline (+ (System/currentTimeMillis) (long timeout-ms))]
     (loop [acc {}]
       (let [m (poll sent (max 0 (- deadline (System/currentTimeMillis))))
             acc (merge acc m {"status" (into (set (get acc "status")) (get m "status"))})]
         (cond
           (nil? m) (throw (ex-info (str "nREPL " (get message "op") " timed out") {:kind :nrepl-timeout}))
           (status? m "connection-closed") (throw (ex-info "nREPL connection closed" {:kind :nrepl-connection}))
           (done? m) acc
           :else (recur acc)))))))
