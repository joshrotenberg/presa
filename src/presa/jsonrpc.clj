(ns presa.jsonrpc
  "JSON-RPC 2.0 messages as maps with string keys, and the newline framing
  MCP's stdio transport uses: one message per line, with no newline inside a
  message."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [presa.protocol :as protocol]))

(defn request
  "A request map. id is a string or an integer; params, when given, is a map."
  ([id method] (request id method nil))
  ([id method params]
   (cond-> {"jsonrpc" "2.0" "id" id "method" method}
     (some? params) (assoc "params" params))))

(defn notification
  "A notification map: a request without an id."
  ([method] (notification method nil))
  ([method params]
   (cond-> {"jsonrpc" "2.0" "method" method}
     (some? params) (assoc "params" params))))

(defn result
  "A successful response to the request with id."
  [id result]
  {"jsonrpc" "2.0" "id" id "result" result})

(defn error
  "An error response. code is an integer or a key of
  presa.protocol/error-codes; id is nil when the request's id is unknown."
  ([id code message] (error id code message nil))
  ([id code message data]
   (let [code (if (keyword? code)
                (or (get protocol/error-codes code)
                    (throw (ex-info (str "Unknown error code " code) {:kind :invalid-input :code code})))
                code)]
     {"jsonrpc" "2.0"
      "id" id
      "error" (cond-> {"code" code "message" message}
                (some? data) (assoc "data" data))})))

(defn kind
  "What message m is: :request, :notification, :response, :error, or
  :invalid when it is not a JSON-RPC 2.0 message."
  [m]
  (cond
    (not (and (map? m) (= "2.0" (get m "jsonrpc")))) :invalid
    (contains? m "method") (cond
                             (not (string? (get m "method"))) :invalid
                             (contains? m "id") :request
                             :else :notification)
    (and (contains? m "result") (not (contains? m "error"))) :response
    (and (contains? m "error") (not (contains? m "result"))) :error
    :else :invalid))

(defn write-line
  "m as one line of JSON ending in a newline. JSON escapes newlines inside
  strings, so the only newline is the one that ends the line."
  [m]
  (str (json/write-str m :escape-slash false :escape-unicode false) "\n"))

(defn- parse-error [message cause]
  (ex-info message {:kind :parse-error} cause))

(defn parse-line
  "Parse one line of JSON, without its newline, into a value with string
  keys. Throws ex-info with :kind :parse-error for a blank line, a line that
  is not JSON, or a line with anything but whitespace after its value
  (clojure.data.json alone would ignore it)."
  [line]
  (when (str/blank? line)
    (throw (parse-error "Empty line" nil)))
  ;; clojure.data.json unreads more than one character, so the reader needs a
  ;; pushback buffer larger than the default of one.
  (let [reader (java.io.PushbackReader. (java.io.StringReader. line) 64)
        value (try (json/read reader)
                   (catch Exception e
                     (throw (parse-error (str "Not a JSON message: " (ex-message e)) e))))]
    (loop []
      (let [c (.read reader)]
        (cond
          (= -1 c) value
          (Character/isWhitespace (char c)) (recur)
          :else (throw (parse-error "More than one JSON value on the line" nil)))))))

(def too-long
  "What read-line-limited returns for a line past its limit."
  ::too-long)

(defn read-line-limited
  "The next line of in without its line ending, `too-long` for a line past
  max-bytes in UTF-8 (whose rest is skipped), or nil at the end of the
  input. The stdio transports, and the HTTP client's event streams, read
  with this, so a peer cannot make them hold an unbounded line."
  [^java.io.BufferedReader in max-bytes]
  (let [sb (StringBuilder.)]
    (loop [too-long? false]
      (let [c (.read in)]
        (cond
          (= -1 c) (cond too-long? too-long
                         (pos? (.length sb)) (str sb)
                         :else nil)
          (= 10 c) (let [line (str sb)
                         line (if (str/ends-with? line "\r") (subs line 0 (dec (count line))) line)]
                     (if (or too-long? (> (inc (alength (.getBytes line "UTF-8"))) max-bytes))
                       too-long
                       line))
          too-long? (recur true)
          ;; a char is at least one byte, so past max-bytes chars is too long
          (>= (.length sb) max-bytes) (do (.setLength sb 0) (recur true))
          :else (do (.append sb (char c)) (recur false)))))))
