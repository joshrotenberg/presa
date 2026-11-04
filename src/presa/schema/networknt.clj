(ns presa.schema.networknt
  "Full JSON Schema validation (2020-12 by default, and the drafts a schema
  names in $schema) for presa.server's :validator option, backed by
  com.networknt/json-schema-validator. JVM only: Babashka does not load it,
  and presa does not depend on it; add the dependency (presa's :schema-full
  alias has it) to use this namespace.

  Remote $ref documents are not fetched unless :fetch-remote? is true: a
  server validating client input should not make requests a schema names."
  (:require [clojure.data.json :as json])
  (:import (com.networknt.schema InputFormat SchemaRegistry SpecificationVersion)
           (com.networknt.schema.resource SchemaLoader$Builder)
           (java.util.function Consumer)))

(defn- registry [{:keys [fetch-remote?]}]
  (SchemaRegistry/withDefaultDialect
   SpecificationVersion/DRAFT_2020_12
   (reify Consumer
     (accept [_ builder]
       (.schemaLoader ^com.networknt.schema.SchemaRegistry$Builder builder
                      (reify Consumer
                        (accept [_ b] (.fetchRemoteResources ^SchemaLoader$Builder b (boolean fetch-remote?)))))))))

(defn- path [^com.networknt.schema.Error e]
  (let [p (.getInstanceLocation e)]
    (vec (for [i (range (.getNameCount p))]
           (let [el (.getElement p i)] (if (number? el) (long el) (str el)))))))

(defn validator
  "A validator function, (fn [value schema]) -> nil or the first failure
  {:path :keyword :message}. Compiled schemas are cached by value.
  opts: :fetch-remote? (default false)."
  ([] (validator {}))
  ([opts]
   (let [reg (registry opts)
         cache (atom {})
         compiled (fn [schema]
                    (or (get @cache schema)
                        (let [s (.getSchema ^SchemaRegistry reg ^String (json/write-str schema) InputFormat/JSON)]
                          (swap! cache assoc schema s)
                          s)))]
     (fn [value schema]
       (cond
         (true? schema) nil
         (false? schema) {:path [] :keyword "false" :message "no value is allowed"}
         :else
         (let [unloadable {:path [] :keyword "schema" :message "the schema could not be loaded"}]
           ;; a schema that cannot be loaded (an unreachable $ref, say, which
           ;; networknt resolves while validating) cannot be satisfied
           (if-let [s (try (compiled schema) (catch Exception _ nil))]
             (let [errors (try (.validate ^com.networknt.schema.Schema s ^String (json/write-str value) InputFormat/JSON)
                               (catch Exception _ ::unloadable))]
               (if (= ::unloadable errors)
                 unloadable
                 (when-let [^com.networknt.schema.Error e (first errors)]
                   {:path (path e)
                    :keyword (.getKeyword e)
                    :message (.getMessage e)})))
             unloadable)))))))
