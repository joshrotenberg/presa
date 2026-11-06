(ns ^:no-doc presa.transport.http.headers
  "Internal to presa.transport.http; not part of the API. MCP header values
  and the headers a tool's x-mcp-header annotations name."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.util Base64)))

(defn write-json [v] (json/write-str v :escape-slash false :escape-unicode false))

;; Header values

(defn encode-header-value
  "A value as an MCP header carries it: printable ASCII without surrounding
  whitespace as is, anything else as =?base64?...?=. Booleans and integers
  in their JSON form."
  [v]
  (let [s (if (string? v) v (write-json v))]
    (if (and (re-matches #"[\x20-\x7E]*" s) (= s (str/trim s)))
      s
      (str "=?base64?" (.encodeToString (Base64/getEncoder) (.getBytes ^String s "UTF-8")) "?="))))

(defn decode-header-value
  "The value of a header, decoding =?base64?...?=; nil when it is malformed."
  [s]
  (when (string? s)
    (let [s (str/trim s)]
      (if-let [[_ b64] (re-matches #"=\?base64\?(.*)\?=" s)]
        ;; strict base64: padded, and only the base64 alphabet
        (when (and (zero? (mod (count b64) 4)) (re-matches #"[A-Za-z0-9+/]*={0,2}" b64))
        (try
          (let [decoder (-> java.nio.charset.StandardCharsets/UTF_8
                            (.newDecoder)
                            (.onMalformedInput java.nio.charset.CodingErrorAction/REPORT))]
            (str (.decode decoder (java.nio.ByteBuffer/wrap (.decode (Base64/getDecoder) ^String b64)))))
          (catch Exception _ nil)))
        s))))

(def name-param
  {"tools/call" "name" "prompts/get" "name" "resources/read" "uri"
   "tasks/get" "taskId" "tasks/update" "taskId" "tasks/cancel" "taskId"})

;; Client

(def ^:private header-token #"^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$")

(defn param-headers
  "The Mcp-Param-* headers a tool's input schema declares with x-mcp-header,
  as [[header-name property type] ...], or :presa.transport.http/invalid when an annotation
  breaks the rules (a client must not call such a tool)."
  [input-schema]
  (let [props (get input-schema "properties")
        found (for [[prop schema] (when (map? props) props)
                    :when (and (map? schema) (contains? schema "x-mcp-header"))]
                [(get schema "x-mcp-header") prop (get schema "type")])
        names (map (comp str/lower-case str first) found)]
    (if (or (some (fn [[h _ t]]
                    (or (not (string? h)) (not (re-matches header-token h))
                        (not (#{"string" "integer" "boolean"} t))))
                  found)
            (not= (count names) (count (set names))))
      :presa.transport.http/invalid
      (vec found))))
