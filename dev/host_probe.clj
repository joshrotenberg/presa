;; A throwaway MCP stdio server that logs every line it receives and sends,
;; used to find out which protocol revision a host speaks as a client. See
;; docs/hosts.md for how it was run and what it showed.
;;
;; PROBE_LOG is the log file. PROBE_MODE picks how the probe answers the
;; handshake:
;;
;; - echo:      answer server/discover as a 2026-07-28 server, and initialize
;;              with the version the client asked for
;; - legacy:    reject server/discover with -32601, answer initialize
;; - stateless: answer initialize with 2026-07-28
;; - reject:    reject initialize with -32601
(ns host-probe
  (:require [clojure.data.json :as json] [clojure.java.io :as io]))

(def log-file (or (System/getenv "PROBE_LOG") "probe.log"))
(def mode (or (System/getenv "PROBE_MODE") "echo"))

(defn log [dir s]
  (spit log-file (str (java.time.Instant/now) " " dir " " s "\n") :append true))

(defn send! [m]
  (let [s (json/write-str m)]
    (log ">>" s)
    (println s)
    (flush)))

(defn not-found [id]
  (send! {"jsonrpc" "2.0" "id" id "error" {"code" -32601 "message" "Method not found"}}))

(log "START" (str "mode=" mode))
(doseq [line (line-seq (io/reader System/in))]
  (log "<<" line)
  (let [m (try (json/read-str line) (catch Exception _ nil))
        id (get m "id")
        method (get m "method")]
    (when (and m id method)
      (case method
        "initialize"
        (if (= mode "reject")
          (not-found id)
          (send! {"jsonrpc" "2.0" "id" id
                  "result" {"protocolVersion" (if (= mode "stateless")
                                                "2026-07-28"
                                                (get-in m ["params" "protocolVersion"]))
                            "capabilities" {"tools" {}}
                            "serverInfo" {"name" "probe" "version" "0"}}}))
        "server/discover"
        (if (= mode "legacy")
          (not-found id)
          (send! {"jsonrpc" "2.0" "id" id
                  "result" {"resultType" "complete"
                            "supportedVersions" ["2026-07-28"]
                            "capabilities" {"tools" {}}
                            "serverInfo" {"name" "probe" "version" "0"}}}))
        "tools/list"
        (send! {"jsonrpc" "2.0" "id" id
                "result" {"tools" [{"name" "probe_echo"
                                    "description" "Echo text back"
                                    "inputSchema" {"type" "object"
                                                   "properties" {"text" {"type" "string"}}
                                                   "required" ["text"]}}]}})
        "tools/call"
        (send! {"jsonrpc" "2.0" "id" id
                "result" {"content" [{"type" "text"
                                      "text" (str "probe says: " (get-in m ["params" "arguments" "text"]))}]}})
        (not-found id)))))
(log "EOF" "stdin closed")
