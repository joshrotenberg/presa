(ns ^:no-doc presa.server.shape
  "Internal to presa.server; not part of the API. Results as each revision
  sends them, pagination cursors, and definitions on the wire."
  (:require [clojure.string :as str]
            [presa.input :as input]
            [presa.protocol :as protocol]
            [presa.request-state :as request-state]
            [presa.tasks :as tasks]
            [presa.server.core :refer [error input-required? now-ms task-created?]])
  (:import (java.util Base64)))

;; Shaping results by revision

(def cached-methods
  #{"server/discover" "tools/list" "resources/list" "resources/templates/list"
    "resources/read" "prompts/list"})

(def input-methods
  "The methods that may answer input_required."
  #{"tools/call" "resources/read" "prompts/get"})

(defn input-required-result
  "An input_required result for a 2026-07-28 request, after checking that
  the client declared what the requests need."
  [srv context method params {:keys [requests state]}]
  (when-not (input-methods method)
    (throw (error :internal-error "Input-required result is not permitted for this operation")))
  (when (and (empty? requests) (nil? state))
    (throw (error :internal-error "Invalid input-required result")))
  (when-let [missing (input/missing-capabilities requests (:client-capabilities context))]
    (throw (error :missing-client-capability "Missing required client capability"
                  {"requiredCapabilities" missing})))
  (cond-> {"resultType" "input_required"}
    (seq requests) (assoc "inputRequests" requests)
    (some? state) (assoc "requestState"
                         (request-state/sign (:state-secret srv) method params state
                                             (:state-ttl-ms srv) (now-ms)
                                             (get-in context [:auth :subject])))))

(defn shape
  "A result as the revision of context sends it."
  [srv context method params result]
  (cond
    (not (protocol/stateless? (:version context)))
    result

    (input-required? result)
    (-> (input-required-result srv context method params (:presa.server/input-required result))
        (assoc "_meta" {(:server-info protocol/meta-keys) (:info srv)}))

    (task-created? result)
    (-> (tasks/creation (:presa.server/task-created result))
        (assoc "resultType" "task" "_meta" {(:server-info protocol/meta-keys) (:info srv)}))

    :else
    (cond-> (-> result
                (assoc "resultType" "complete")
                (update "_meta" (fnil assoc {}) (:server-info protocol/meta-keys) (:info srv)))
      (cached-methods method) (assoc "ttlMs" (if (or (seq (:input-responses context)) (some? (:state context)))
                                               ;; a result that took input is not one to reuse
                                               0
                                               (get-in srv [:cache :ttl-ms]))
                                     "cacheScope" (get-in srv [:cache :scope])))))

;; Pagination

(def list-kinds
  {"tools/list" "tools" "resources/list" "resources"
   "resources/templates/list" "resources" "prompts/list" "prompts"})

(defn encode-cursor [method offset version]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.getBytes (str "presa1|" method "|" offset "|" version) "UTF-8")))

(defn decode-cursor
  "[offset catalog-version] from a cursor for method."
  [method cursor]
  (let [invalid #(throw (error :invalid-params "Invalid pagination cursor"))]
    (when-not (string? cursor)
      (throw (error :invalid-params "Cursor must be a string")))
    (when-not (<= (count cursor) 512) (invalid))
    (let [decoded (try (String. (.decode (Base64/getUrlDecoder) ^String cursor) "UTF-8")
                       (catch Exception _ (invalid)))
          [prefix m offset version] (str/split decoded #"\|" 4)
          offset (when (and offset (re-matches #"\d{1,9}" offset)) (parse-long offset))
          version (when (and version (re-matches #"\d{1,18}" version)) (parse-long version))]
      (if (and (= "presa1" prefix) (= method m) offset version)
        [offset version]
        (invalid)))))

(defn page
  "[items next-cursor] for one page of items."
  [srv method params items]
  (let [current (get-in srv [:catalog-version (list-kinds method)] 0)
        [offset version] (if (contains? params "cursor")
                           (decode-cursor method (get params "cursor"))
                           [0 current])
        size (or (:page-size srv) (count items))
        items (vec items)]
    ;; a cursor from before the list changed would skip or repeat items
    (when (or (not= version current) (> offset (count items)))
      (throw (error :invalid-params "Pagination cursor has expired")))
    (let [end (min (count items) (+ offset size))]
      [(subvec items offset end)
       (when (< end (count items)) (encode-cursor method end current))])))

(defn paged [srv method params k items]
  (let [[items cursor] (page srv method params items)]
    (cond-> {k items}
      cursor (assoc "nextCursor" cursor))))

;; Wire definitions

(defn wire [m fields]
  (reduce (fn [acc [k wire-key]]
            (if-some [v (get m k)] (assoc acc wire-key v) acc))
          {}
          fields))

(defn wire-tool [tool]
  (merge {"inputSchema" {"type" "object"}}
         (wire tool [[:name "name"] [:title "title"] [:description "description"]
                     [:input-schema "inputSchema"] [:output-schema "outputSchema"]
                     [:annotations "annotations"] [:meta "_meta"]])))

(defn wire-resource [resource]
  (wire resource [[:uri "uri"] [:name "name"] [:title "title"] [:description "description"]
                  [:mime-type "mimeType"] [:size "size"] [:annotations "annotations"]
                  [:meta "_meta"]]))

(defn wire-template [template]
  (wire template [[:uri-template "uriTemplate"] [:name "name"] [:title "title"]
                  [:description "description"] [:mime-type "mimeType"]
                  [:annotations "annotations"] [:meta "_meta"]]))

(defn wire-prompt [prompt]
  (cond-> (wire prompt [[:name "name"] [:title "title"] [:description "description"]
                        [:meta "_meta"]])
    (seq (:arguments prompt))
    (assoc "arguments" (mapv #(wire % [[:name "name"] [:title "title"]
                                       [:description "description"] [:required "required"]])
                             (:arguments prompt)))))
