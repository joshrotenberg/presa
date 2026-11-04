(ns ^:no-doc presa.server.tasks
  "Internal to presa.server; not part of the API. The Tasks extension: a tool
  call's task policy, a task's work, and tasks/* methods."
  (:require [presa.protocol :as protocol]
            [presa.tasks :as tasks]
            [presa.server.core :refer [check-output! emit! error error-code input-required? tool-result]]))

;; Tasks

(defn task-policy
  "How a tools/call runs: :sync, :optional or :required. Tasks exist only on
  2026-07-28, with the extension on."
  [srv tool params context]
  (if (and (:task-store srv) (protocol/stateless? (:version context)))
    (let [p (:task tool :sync)]
      (if (fn? p) (p params context) p))
    :sync))

(def max-task-input-rounds 16)

(defn run-task
  "The work of task id: call the handler, park on input requests until
  tasks/update answers them, and record the outcome. A tool error ends
  completed with isError; an exception ends failed."
  [srv st id tool arguments ctx]
  (binding [*out* *err*]
    (try
      (let [v (loop [ctx ctx round 1]
                (let [v ((:handler tool) arguments ctx)]
                  (if-not (input-required? v)
                    v
                    (let [{:keys [requests state]} (:presa.server/input-required v)
                          waiters (into {} (map (fn [k] [k (promise)])) (keys requests))]
                      (when (> round max-task-input-rounds)
                        (throw (error :internal-error "The task asked for input too many times")))
                      (when (empty? requests)
                        (throw (error :internal-error "Invalid input-required result")))
                      (tasks/update! st id #(-> %
                                                (assoc :status "input_required")
                                                (update :input-requests merge requests)
                                                (update :waiters merge waiters)))
                      (let [answers (into {} (map (fn [[k p]] [k @p])) waiters)]
                        (recur (assoc ctx
                                      :input-responses (merge (:input-responses ctx) answers)
                                      :state state)
                               (inc round)))))))
            result (tool-result v)]
        (check-output! srv tool result)
        (tasks/update! st id #(-> %
                                  (assoc :status "completed"
                                         :result (-> result
                                                     (assoc "resultType" "complete")
                                                     (assoc "_meta" {(:server-info protocol/meta-keys) (:info srv)})))
                                  (dissoc :status-message)
                                  (assoc :input-requests {} :waiters {}))))
      (catch InterruptedException _ nil)
      (catch Throwable e
        (let [{:keys [code message]} (:presa/error (ex-data e))
              code (error-code code)
              message (if code message "Tool raised an exception")]
          (emit! srv {:event :tool/error :tool (:name tool) :task-id id :exception e})
          (tasks/update! st id #(assoc % :status "failed" :status-message message
                                       :error {"code" (or code -32603) "message" message}
                                       :input-requests {} :waiters {})))))))

(defn start-task!
  "Create a task for a tools/call and start its work; the marker `shape`
  turns into a CreateTaskResult."
  [srv tool arguments context]
  (let [st (:task-store srv)
        scope (get-in context [:auth :subject])
        task (or (tasks/create! st scope)
                 (throw (error :internal-error "Task capacity exhausted" {"retryable" true "limit" "max_tasks"})))
        id (:id task)
        ;; the work outlives the request: keep what describes the caller,
        ;; drop what belongs to the request (its stream, cancellation, _meta)
        ctx (-> (select-keys context [:version :client-info :client-capabilities :auth :server
                                      :input-responses :state])
                (assoc :meta {}
                       :notify (fn [_])
                       :task-id id
                       :cancelled? (fn [] (= "cancelled" (:status (get @(:tasks st) id))))))
        work (future (run-task srv st id tool arguments ctx))]
    (tasks/set-work! st id work)
    {:presa.server/task-created task}))

(defn require-tasks-declared! [context]
  (when-not (tasks/declared? (:client-capabilities context))
    (throw (error :missing-client-capability "Missing required client capability" tasks/required-capabilities))))

(defn task-of
  "The task a tasks/* request names, checked in snodo's order: the
  declaration, the taskId, then the lookup."
  [srv params context]
  (require-tasks-declared! context)
  (let [[id message] (tasks/check-task-id (get params "taskId"))]
    (when message (throw (error :invalid-params message)))
    (or (tasks/lookup (:task-store srv) id (get-in context [:auth :subject]))
        (throw (error :invalid-params "Unknown or inaccessible taskId")))))

(defn tasks-get [srv params context]
  (tasks/detailed (task-of srv params context)))

(defn tasks-update [srv params context]
  (require-tasks-declared! context)
  (let [[_ message] (tasks/check-task-id (get params "taskId"))
        responses (get params "inputResponses")]
    (when message (throw (error :invalid-params message)))
    (when-not (map? responses)
      (throw (error :invalid-params "tasks/update requires an inputResponses object")))
    (when-not (and (every? #(and (string? %) (seq %)) (keys responses)) (every? map? (vals responses)))
      (throw (error :invalid-params "inputResponses is invalid")))
    (let [t (task-of srv params context)
          [before _] (tasks/update-vals!
                      (:task-store srv) (:id t)
                      (fn [t]
                        (let [matched (filter #(contains? (:input-requests t) %) (keys responses))]
                          (if (empty? matched)
                            t
                            (let [left (apply dissoc (:input-requests t) matched)]
                              (assoc t
                                     :input-requests left
                                     :waiters (apply dissoc (:waiters t) matched)
                                     :status (if (empty? left) "working" "input_required")))))))]
      ;; wake the worker for each answered key; unknown keys are ignored
      (doseq [[k v] responses]
        (when-let [p (and (contains? (:input-requests before) k) (get-in before [:waiters k]))]
          (deliver p v)))
      {})))

(defn tasks-cancel [srv params context]
  (let [t (task-of srv params context)
        [before after] (tasks/update-vals! (:task-store srv) (:id t)
                                           #(assoc % :status "cancelled" :status-message "Cancellation requested"
                                                   :input-requests {} :waiters {}))]
    (when (and (= "cancelled" (:status after)) (not= "cancelled" (:status before)))
      (when-let [work (:work before)] (future-cancel work)))
    {}))
