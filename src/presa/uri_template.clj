(ns presa.uri-template
  "The parts of RFC 6570 URI templates that MCP resource templates use:
  simple expansion {var}, which matches one path segment, and reserved
  expansion {+var}, which matches the rest of a URI. Several variables in one
  expression, and the other operators, are not supported."
  (:require [clojure.string :as str])
  (:import (java.net URLDecoder URLEncoder)))

(def ^:private expression #"\{(\+?)([A-Za-z0-9_.]+)\}")

(defn parse
  "A template string as a vector of parts: strings for literal text and
  {:name n :reserved? b} for expressions. Throws ex-info with :kind
  :invalid-input for an expression presa does not support."
  [template]
  (when-not (string? template)
    (throw (ex-info "A URI template is a string" {:kind :invalid-input :template template})))
  (let [m (re-matcher expression template)]
    (loop [parts [] start 0]
      (if (.find m)
        (recur (cond-> parts
                 (< start (.start m)) (conj (subs template start (.start m)))
                 true (conj {:name (.group m 2) :reserved? (= "+" (.group m 1))}))
               (.end m))
        (let [parts (cond-> parts (< start (count template)) (conj (subs template start)))]
          (when (some #(and (string? %) (re-find #"[{}]" %)) parts)
            (throw (ex-info (str "Unsupported URI template expression in " template)
                            {:kind :invalid-input :template template})))
          parts)))))

(defn variables
  "The variable names of a template, in order."
  [template]
  (vec (keep :name (parse template))))

(defn- decode [s] (URLDecoder/decode (str/replace s "+" "%2B") "UTF-8"))

(defn matcher
  "A function of a URI that returns the template's variables as a map with
  string keys when the URI matches, else nil."
  [template]
  (let [parts (parse template)
        names (keep :name parts)
        pattern (re-pattern
                 (apply str (map (fn [part]
                                   (cond
                                     (string? part) (java.util.regex.Pattern/quote part)
                                     (:reserved? part) "(.+)"
                                     :else "([^/?#]+)"))
                                 parts)))]
    (fn [uri]
      (when-let [groups (and (string? uri) (re-matches pattern uri))]
        (zipmap names (map decode (if (string? groups) [] (rest groups))))))))

(defn expand
  "The URI a template names for values, a map with string keys. Simple
  expressions percent-encode their value; reserved ones keep / ? # and the
  like. A missing value expands to nothing."
  [template values]
  (apply str (map (fn [part]
                    (if (string? part)
                      part
                      (let [v (str (get values (:name part) ""))
                            encoded (str/replace (URLEncoder/encode v "UTF-8") "+" "%20")]
                        (if (:reserved? part)
                          (-> encoded
                              (str/replace "%2F" "/") (str/replace "%3A" ":")
                              (str/replace "%3F" "?") (str/replace "%23" "#")
                              (str/replace "%26" "&") (str/replace "%3D" "="))
                          encoded))))
                  (parse template))))
