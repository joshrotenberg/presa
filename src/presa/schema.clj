(ns presa.schema
  "Validation for the subset of JSON Schema tools use, with no dependencies:
  type (one or a list), enum, const, required, properties,
  additionalProperties, items, the string, number, array and object size
  constraints, pattern, and true and false schemas. Other keywords,
  including $ref and the combinators, are ignored, so a schema that needs
  them still loads; validate with a full JSON Schema library when that
  matters.

  Values are what clojure.data.json reads: maps with string keys, vectors,
  strings, numbers, booleans and nil."
  (:require [clojure.string :as str]))

(defn- json-type? [t v]
  (case t
    "null" (nil? v)
    "boolean" (boolean? v)
    "string" (string? v)
    "number" (number? v)
    "integer" (or (integer? v)
                  (and (number? v) (not (ratio? v)) (== (double v) (Math/floor (double v)))
                       (not (Double/isInfinite (double v)))))
    "array" (sequential? v)
    "object" (map? v)
    false))

(defn- type-name [v]
  (cond (nil? v) "null" (boolean? v) "boolean" (string? v) "string"
        (integer? v) "integer" (number? v) "number" (sequential? v) "array"
        (map? v) "object" :else "unknown"))

(defn- fail [path keyword message]
  {:path path :keyword keyword :message message})

(defn- json= [a b]
  (cond
    (and (number? a) (number? b)) (== a b)
    (and (sequential? a) (sequential? b)) (and (= (count a) (count b)) (every? true? (map json= a b)))
    (and (map? a) (map? b)) (and (= (set (keys a)) (set (keys b)))
                                 (every? #(json= (get a %) (get b %)) (keys a)))
    :else (= a b)))

(defn- code-points [^String s] (.codePointCount s 0 (count s)))

(declare validate*)

(defn- check-type [v schema path]
  (when-let [t (get schema "type")]
    (let [types (if (sequential? t) t [t])]
      (when-not (some #(json-type? % v) types)
        (fail path "type" (str "expected " (str/join " or " types) ", got " (type-name v)))))))

(defn- check-enum [v schema path]
  (or (when (contains? schema "enum")
        (when-not (some #(json= v %) (get schema "enum"))
          (fail path "enum" "value is not one of the allowed values")))
      (when (contains? schema "const")
        (when-not (json= v (get schema "const"))
          (fail path "const" "value is not the allowed constant")))))

(defn- check-string [v schema path]
  (when (string? v)
    (let [{:strs [minLength maxLength pattern]} schema
          n (code-points v)]
      (cond
        (and (number? minLength) (< n minLength)) (fail path "minLength" (str "shorter than " minLength " characters"))
        (and (number? maxLength) (> n maxLength)) (fail path "maxLength" (str "longer than " maxLength " characters"))
        (and (string? pattern) (not (re-find (re-pattern pattern) v))) (fail path "pattern" (str "does not match " pattern))))))

(defn- check-number [v schema path]
  (when (number? v)
    (let [{:strs [minimum maximum exclusiveMinimum exclusiveMaximum multipleOf]} schema]
      (cond
        (and (number? minimum) (< v minimum)) (fail path "minimum" (str "less than " minimum))
        (and (number? maximum) (> v maximum)) (fail path "maximum" (str "greater than " maximum))
        (and (number? exclusiveMinimum) (<= v exclusiveMinimum)) (fail path "exclusiveMinimum" (str "not greater than " exclusiveMinimum))
        (and (number? exclusiveMaximum) (>= v exclusiveMaximum)) (fail path "exclusiveMaximum" (str "not less than " exclusiveMaximum))
        (and (number? multipleOf) (pos? multipleOf)
             (let [q (/ (double v) (double multipleOf))] (> (Math/abs (- q (Math/rint q))) 1e-9)))
        (fail path "multipleOf" (str "not a multiple of " multipleOf))))))

(defn- check-array [v schema path]
  (when (sequential? v)
    (let [{:strs [minItems maxItems uniqueItems items]} schema
          n (count v)]
      (cond
        (and (number? minItems) (< n minItems)) (fail path "minItems" (str "fewer than " minItems " items"))
        (and (number? maxItems) (> n maxItems)) (fail path "maxItems" (str "more than " maxItems " items"))
        (and (true? uniqueItems)
             (some (fn [[i a]] (some #(json= a %) (drop (inc i) v))) (map-indexed vector v)))
        (fail path "uniqueItems" "items are not unique")
        (or (map? items) (boolean? items))
        (some (fn [[i item]] (validate* item items (conj path i))) (map-indexed vector v))))))

(defn- check-object [v schema path]
  (when (map? v)
    (let [{:strs [required properties additionalProperties minProperties maxProperties]} schema
          properties (if (map? properties) properties {})
          n (count v)]
      (or
       (when (sequential? required)
         (some #(when-not (contains? v %) (fail path "required" (str "missing required property " %))) required))
       (cond
         (and (number? minProperties) (< n minProperties)) (fail path "minProperties" (str "fewer than " minProperties " properties"))
         (and (number? maxProperties) (> n maxProperties)) (fail path "maxProperties" (str "more than " maxProperties " properties")))
       (some (fn [[k sub]] (when (contains? v k) (validate* (get v k) sub (conj path k))))
             (sort-by key properties))
       (when (or (false? additionalProperties) (map? additionalProperties))
         (some (fn [k]
                 (when-not (contains? properties k)
                   (if (false? additionalProperties)
                     (fail (conj path k) "additionalProperties" "property is not allowed")
                     (validate* (get v k) additionalProperties (conj path k)))))
               (sort (keys v))))))))

(defn- validate* [v schema path]
  (cond
    (true? schema) nil
    (false? schema) (fail path "false" "no value is allowed")
    (not (map? schema)) nil
    :else (or (check-type v schema path)
              (check-enum v schema path)
              (check-string v schema path)
              (check-number v schema path)
              (check-array v schema path)
              (check-object v schema path))))

(defn validate
  "nil when v satisfies schema, else the first failure found:
  {:path [..] :keyword k :message m}. path is a vector of property names and
  array indexes; message never quotes the value, which may be a secret."
  [v schema]
  (validate* v schema []))

(defn describe
  "A failure from `validate` as one line of text."
  [{:keys [path message]}]
  (if (seq path)
    (str "at /" (str/join "/" path) ": " message)
    message))
