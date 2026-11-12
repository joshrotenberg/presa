(ns ^:no-doc presa.server.subscriptions
  "Internal to presa.server; not part of the API. subscriptions/listen."
  (:require [presa.protocol :as protocol]
            [presa.tasks :as tasks]
            [presa.server.core :refer [error notify!]]
            [presa.server.catalog :refer [capabilities]]
            [presa.server.access :refer [may-read?]]
            [presa.server.tasks :refer [require-tasks-declared!]]))

(def max-session-subscriptions
  "The URIs one initialize-era session may subscribe to."
  1000)

(def flags
  {"toolsListChanged" "tools" "promptsListChanged" "prompts" "resourcesListChanged" "resources"})

(defn subscriptions-listen
  "Hold a subscriptions/listen request open: acknowledge what the server
  will send, deliver matching notifications, and answer when the server
  ends the stream (a cancelled stream gets no answer)."
  [srv params context]
  (let [requested (get params "notifications")
        uris (get requested "resourceSubscriptions")
        id (:request-id context)
        sub-key (:subscription-id protocol/meta-keys)]
    (when-not (map? requested)
      (throw (error :invalid-params "subscriptions/listen requires a notifications object")))
    (doseq [k (keys flags)]
      (when (and (contains? requested k) (not (boolean? (get requested k))))
        (throw (error :invalid-params (str k " must be a boolean")))))
    (when (and (contains? requested "resourceSubscriptions")
               (not (and (sequential? uris) (<= (count uris) 1000)
                         (every? #(and (string? %) (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" %)) uris))))
      (throw (error :invalid-params "resourceSubscriptions must be a list of absolute URIs")))
    (when (and (contains? requested "taskIds") (:task-store srv))
      (let [ids (get requested "taskIds")]
        (when-not (and (sequential? ids) (<= (count ids) 100)
                       (every? #(and (string? %) (seq %) (<= (alength (.getBytes ^String % "UTF-8")) 256)) ids))
          (throw (error :invalid-params "taskIds must be a list of at most 100 task ids")))
        (require-tasks-declared! context)))
    (let [caps (capabilities srv)
          kinds (set (for [[flag kind] flags
                           :when (and (true? (get requested flag))
                                      (get-in caps [kind "listChanged"]))]
                       kind))
          ;; only URIs this caller may read; others are dropped
          uris (when (and (:subscribe srv) (seq uris))
                 (not-empty (filterv #(may-read? srv context %) uris)))
          scope (get-in context [:auth :subject])
          ;; only tasks this caller may see; unknown ones are dropped
          task-ids (when-let [st (:task-store srv)]
                     (not-empty (vec (distinct (filter #(tasks/lookup st % scope) (get requested "taskIds"))))))
          ack (cond-> (into {} (for [[flag kind] flags :when (kinds kind)] [flag true]))
                uris (assoc "resourceSubscriptions" uris)
                task-ids (assoc "taskIds" task-ids))
          tag {sub-key id}
          listener-key (Object.)
          acked (promise)]
      ;; Register before acknowledging, so a stream acknowledged is a stream
      ;; end-subscriptions! can end; events wait for the acknowledgement,
      ;; which stays the first message on the stream. The capacity check and
      ;; the registration are one step.
      (swap! (:listeners srv)
             (fn [m] (if (< (count (filter :stop (vals m))) (:max-subscriptions srv))
                       ;; a placeholder that counts toward the capacity
                       (assoc m listener-key {:stop (:stopped context)})
                       m)))
      (when-not (contains? @(:listeners srv) listener-key)
        (throw (error :internal-error "Server subscription capacity exhausted" {"capacity" "subscriptions"})))
      (swap! (:listeners srv) assoc listener-key
             {:kinds kinds
              :uris uris
              :task-ids (set task-ids)
              :scope scope
              :auth (:auth context)
              :send (fn [method params]
                      (deref acked 5000 nil)
                      (notify! context method (assoc params "_meta" tag)))
              :stop (:stopped context)})
      (try
        (notify! context "notifications/subscriptions/acknowledged" {"notifications" ack "_meta" tag})
        (finally (deliver acked true)))
      (try
        @(:stopped context)
        {"_meta" tag}
        (finally
          (swap! (:listeners srv) dissoc listener-key))))))
