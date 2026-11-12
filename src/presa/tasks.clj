(ns presa.tasks
  "The task store of the Tasks extension (io.modelcontextprotocol/tasks,
  SEP-2663) and the wire shapes of a task; see docs/tasks-extension.md.
  presa.server creates and runs tasks; this namespace keeps their state.

  A task is a map: :id, :scope (the authenticated subject, or nil), :status
  (\"working\", \"input_required\", \"completed\", \"failed\", \"cancelled\"),
  :status-message, :created-at and :updated-at (epoch ms), :ttl-ms,
  :poll-interval-ms, :input-requests (key to bare request), :result or
  :error once terminal, and :waiters (key to the promise a parked worker
  waits on). A terminal task never changes again."
  (:require [clojure.string :as str])
  (:import (java.security SecureRandom)
           (java.time Instant)
           (java.util Base64)))

(def extension
  "The Tasks extension's id in capabilities."
  "io.modelcontextprotocol/tasks")

(def terminal
  "The statuses a task never leaves."
  #{"completed" "failed" "cancelled"})

(defn declared?
  "Whether a request's client capabilities declare the Tasks extension."
  [client-capabilities]
  (map? (get-in client-capabilities ["extensions" extension])))

(def required-capabilities
  "The -32021 data a request needs to declare Tasks."
  {"requiredCapabilities" {"extensions" {extension {}}}})

(defn store
  "A task store from the server's :tasks options:
  :ttl-ms (default 3600000), :poll-interval-ms (default 250), :max-tasks
  (default 1000 tasks not yet terminal)."
  [{:keys [ttl-ms poll-interval-ms max-tasks]
    :or {ttl-ms 3600000 poll-interval-ms 250 max-tasks 1000}}]
  {:tasks (atom {}) :ttl-ms ttl-ms :poll-interval-ms poll-interval-ms :max-tasks max-tasks
   ;; (fn [task]) called after each change of a task, for status notifications
   :on-change (atom nil)})

(defn- changed! [st before after]
  (when-let [f (and after (not= (dissoc before :updated-at :work) (dissoc after :updated-at :work))
                    @(:on-change st))]
    (try (f after) (catch Exception _ nil))))

(defn- new-id []
  (let [b (byte-array 24)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn- now-ms [] (System/currentTimeMillis))

(defn- expired? [{:keys [created-at ttl-ms]} now]
  (and ttl-ms (> now (+ created-at ttl-ms))))

(defn- reap!
  "Forget the tasks past their TTL, cancelling the work of those not yet
  terminal: once forgotten, nothing can update or cancel them."
  [st]
  (let [now (now-ms)
        [before after] (swap-vals! (:tasks st) (fn [m] (into {} (remove (fn [[_ t]] (expired? t now))) m)))]
    (doseq [[id t] before
            :when (and (not (contains? after id)) (not (terminal (:status t))))]
      (some-> (:work t) future-cancel))))

(defn create!
  "Store a new working task for scope and return it, or nil when the store
  holds max-tasks tasks that are not terminal."
  [st scope]
  (reap! st)
  (let [now (now-ms)
        task {:id (new-id) :scope scope :status "working" :status-message "Task accepted"
              :created-at now :updated-at now :ttl-ms (:ttl-ms st)
              :poll-interval-ms (:poll-interval-ms st) :input-requests {} :waiters {}}
        added (atom false)]
    (swap! (:tasks st)
           (fn [m]
             (if (>= (count (remove (comp terminal :status) (vals m))) (:max-tasks st))
               (do (reset! added false) m)
               (do (reset! added true) (assoc m (:id task) task)))))
    (when @added task)))

(defn lookup
  "The task with id visible to scope, or nil when there is none, it has
  expired, or it belongs to another scope."
  [st id scope]
  (when-let [t (get @(:tasks st) id)]
    (when (and (= scope (:scope t)) (not (expired? t (now-ms))))
      t)))

(defn update-vals!
  "Apply f to the task with id unless it is terminal; returns [before
  after], the task before f and after."
  [st id f]
  (let [[old new] (swap-vals! (:tasks st)
                              (fn [m]
                                (if-let [t (get m id)]
                                  (if (terminal (:status t))
                                    m
                                    (assoc m id (assoc (f t) :updated-at (now-ms))))
                                  m)))
        before (get old id)
        after (get new id)]
    (changed! st before after)
    [before after]))

(defn update!
  "Apply f to the task with id unless it is terminal; returns the task after."
  [st id f]
  (second (update-vals! st id f)))

(defn set-work!
  "Remember the future running the task with id."
  [st id work]
  (swap! (:tasks st) (fn [m] (if (contains? m id) (assoc-in m [id :work] work) m))))

(defn- iso [ms] (str (Instant/ofEpochMilli ms)))

(defn- base [t]
  (cond-> {"taskId" (:id t)
           "status" (:status t)
           "createdAt" (iso (:created-at t))
           "lastUpdatedAt" (iso (:updated-at t))
           "ttlMs" (:ttl-ms t)}
    (:status-message t) (assoc "statusMessage" (:status-message t))
    (:poll-interval-ms t) (assoc "pollIntervalMs" (:poll-interval-ms t))))

(defn creation
  "The CreateTaskResult of a new task, without resultType and _meta."
  [t]
  (base t))

(defn detailed
  "The DetailedTask of tasks/get: the base fields and the payload of the
  task's status."
  [t]
  (cond-> (base t)
    (= "input_required" (:status t)) (assoc "inputRequests" (:input-requests t))
    (= "completed" (:status t)) (assoc "result" (:result t))
    (= "failed" (:status t)) (assoc "error" (:error t))))

(defn notification
  "The params of notifications/tasks for a task: the DetailedTask fields."
  [t]
  (detailed t))

(defn check-task-id
  "A taskId param, or nil with the -32602 message to send."
  [task-id]
  (cond
    (not (and (string? task-id) (not (str/blank? task-id)))) [nil "A non-empty taskId is required"]
    (> (alength (.getBytes ^String task-id "UTF-8")) 256) [nil "taskId is longer than 256 bytes"]
    :else [task-id nil]))
