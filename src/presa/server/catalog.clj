(ns ^:no-doc presa.server.catalog
  "Internal to presa.server; not part of the API. Definitions: checking them,
  the catalog snapshot a request reads, capabilities."
  (:require [clojure.string :as str]
            [presa.protocol :as protocol]
            [presa.tasks :as tasks]
            [presa.uri-template :as uri-template]))

;; Definitions

(defn check-name [kind m k]
  (when-not (and (map? m) (string? (get m k)) (not (str/blank? (get m k))))
    (throw (ex-info (str "A " kind " needs a non-blank string " k) {:kind :invalid-input kind m}))))

(defn check-handler [kind m label]
  (when-not (ifn? (:handler m))
    (throw (ex-info (str "The " kind " " label " needs a :handler function")
                    {:kind :invalid-input kind label}))))

(defn check-complete [kind m label]
  (when-let [c (:complete m)]
    (when-not (and (map? c) (every? string? (keys c)) (every? ifn? (vals c)))
      (throw (ex-info (str "The " kind " " label " :complete must map argument names to functions")
                      {:kind :invalid-input kind label})))))

(defn index
  "Definitions by key, with their order, checking that keys are distinct."
  [kind k defs]
  (let [ks (map k defs)]
    (when-not (apply distinct? :presa.server/none ks)
      (throw (ex-info (str "The " kind " " (name k) " values must be distinct")
                      {:kind :invalid-input :values (vec ks)})))
    [(into {} (map (juxt k identity)) defs) (vec ks)]))

(defn- json-name [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    k))

(defn json-schema
  "A schema written with keyword keys or values as JSON Schema with string
  keys: :type :object becomes \"type\" \"object\", :a/b becomes \"a/b\".
  Strings, numbers and booleans are left alone."
  [v]
  (cond
    (map? v) (into {} (map (fn [[k x]] [(json-name k) (json-schema x)])) v)
    (sequential? v) (mapv json-schema v)
    (keyword? v) (json-name v)
    :else v))

(defn tool-def [tool]
  (check-name "tool" tool :name)
  (check-handler "tool" tool (:name tool))
  (when-let [p (:task tool)]
    (when-not (or (#{:sync :optional :required} p) (fn? p))
      (throw (ex-info (str "The tool " (:name tool) " :task must be :sync, :optional, :required or a function")
                      {:kind :invalid-input :tool (:name tool)}))))
  (cond-> tool
    (contains? tool :input-schema) (update :input-schema json-schema)
    (contains? tool :output-schema) (update :output-schema json-schema)))

(defn resource-def [resource]
  (check-name "resource" resource :uri)
  (check-name "resource" resource :name)
  (check-handler "resource" resource (:uri resource))
  resource)

(defn template-def [template]
  (check-name "resource template" template :uri-template)
  (check-name "resource template" template :name)
  (check-handler "resource template" template (:uri-template template))
  (check-complete "resource template" template (:uri-template template))
  (assoc template :presa.server/match (uri-template/matcher (:uri-template template))))

(defn prompt-def [prompt]
  (check-name "prompt" prompt :name)
  (check-handler "prompt" prompt (:name prompt))
  (check-complete "prompt" prompt (:name prompt))
  (doseq [arg (:arguments prompt)]
    (check-name "prompt argument" arg :name))
  prompt)

(defn snapshot
  "srv with the current definitions merged in, read once."
  [srv]
  (if-let [c (:catalog srv)] (merge srv @c) srv))

(defn stateless-versions [srv] (filterv protocol/stateless? (:versions srv)))

(defn legacy-versions [srv] (filterv (complement protocol/stateless?) (:versions srv)))

(defn completions? [srv]
  (boolean (some :complete (concat (:templates srv) (vals (:prompts srv))))))

(defn capabilities
  "What srv advertises. A kind with no definitions is left out, except on a
  :list-changed server, which can gain them later and must say so."
  [srv]
  (cond-> {}
    (or (seq (:tools srv)) (:list-changed srv))
    (assoc "tools" (if (:list-changed srv) {"listChanged" true} {}))
    (or (seq (:resources srv)) (seq (:templates srv)) (:list-changed srv))
    (assoc "resources" (cond-> {}
                         (:list-changed srv) (assoc "listChanged" true)
                         (:subscribe srv) (assoc "subscribe" true)))
    (or (seq (:prompts srv)) (:list-changed srv))
    (assoc "prompts" (if (:list-changed srv) {"listChanged" true} {}))
    (completions? srv) (assoc "completions" {})
    (:logging srv) (assoc "logging" {})
    (:task-store srv) (assoc "extensions" {tasks/extension {}})))

(defn put-ordered [c m-key order-key k v]
  (cond-> (assoc-in c [m-key k] v)
    (not (contains? (get c m-key) k)) (update order-key conj k)))

(defn drop-ordered [c m-key order-key k]
  (-> c
      (update m-key dissoc k)
      (update order-key (fn [ks] (filterv #(not= k %) ks)))))
