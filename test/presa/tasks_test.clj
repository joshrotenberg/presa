(ns presa.tasks-test
  (:require [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.input :as input]
            [presa.jsonrpc :as rpc]
            [presa.server :as server]
            [presa.transport.http :as http]))

(def ext "io.modelcontextprotocol/tasks")

(defn- meta-with [caps]
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" caps})

(def declared (meta-with {"elicitation" {} "extensions" {ext {}}}))
(def undeclared (meta-with {}))

(defn- make-server [& [opts]]
  (server/server
   (merge
    {:name "t" :version "1"
     :tasks {:ttl-ms 60000 :poll-interval-ms 20}
     :tools [{:name "sync" :handler (fn [_ _] "sync ran")}
             {:name "slow" :task :optional
              :handler (fn [{:strs [ms]} _] (when ms (Thread/sleep (long ms))) "slow done")}
             {:name "must" :task :required :handler (fn [_ _] "must done")}
             {:name "tool-error" :task :optional :handler (fn [_ _] (server/tool-error "bad input"))}
             {:name "raises" :task :optional :handler (fn [_ _] (throw (ex-info "secret detail" {})))}
             {:name "two" :task :optional
              :handler (fn [_ ctx]
                         (let [a (server/input-response ctx "a") b (server/input-response ctx "b")]
                           (if (and a b)
                             (str "got " (get-in a ["content" "v"]) " " (get-in b ["content" "v"]))
                             (server/input-required
                              (cond-> {}
                                (not a) (assoc "a" (input/elicit "a?" {"type" "object"}))
                                (not b) (assoc "b" (input/elicit "b?" {"type" "object"})))))))}]}
    opts)))

(defn- call [srv method params meta & [opts]]
  (second (server/handle srv {} (rpc/request 1 method (assoc params "_meta" meta)) opts)))

(defn- result [srv method params meta & [opts]] (get (call srv method params meta opts) "result"))

(defn- wait-status [srv id status & [opts]]
  (loop [n 0]
    (let [t (result srv "tasks/get" {"taskId" id} declared opts)]
      (if (or (= status (get t "status")) (> n 200)) t (do (Thread/sleep 10) (recur (inc n)))))))

(deftest negotiation
  (let [srv (make-server)]
    (is (= {ext {}} (get-in (result srv "server/discover" {} undeclared) ["capabilities" "extensions"])))
    (testing "an undeclared client runs optional tools synchronously"
      (let [r (result srv "tools/call" {"name" "slow"} undeclared)]
        (is (= "complete" (get r "resultType")))
        (is (= "slow done" (get-in r ["content" 0 "text"])))))
    (testing "and is refused required tools and the task methods"
      (doseq [[method params] [["tools/call" {"name" "must"}]
                               ["tasks/get" {"taskId" "x"}]
                               ["tasks/update" {"taskId" "x" "inputResponses" {}}]
                               ["tasks/cancel" {"taskId" "x"}]]]
        (is (= {"code" -32021 "message" "Missing required client capability"
                "data" {"requiredCapabilities" {"extensions" {ext {}}}}}
               (get (call srv method params undeclared) "error"))
            method)))
    (testing "a sync tool is never a task, and a legacy task param is ignored"
      (let [r (result srv "tools/call" {"name" "sync" "task" {"ttl" 60000}} declared)]
        (is (= "complete" (get r "resultType")))
        (is (not (contains? r "taskId")))))
    (testing "without the extension the methods do not exist"
      (let [plain (server/server {:name "t" :version "1" :tools [{:name "slow" :task :optional :handler (fn [_ _] "x")}]})]
        (is (= -32601 (get-in (call plain "tasks/get" {"taskId" "x"} declared) ["error" "code"])))
        (is (= "x" (get-in (result plain "tools/call" {"name" "slow"} declared) ["content" 0 "text"])))))
    (is (= -32601 (get-in (call srv "tasks/list" {} declared) ["error" "code"])))))

(deftest lifecycle
  (let [srv (make-server)
        created (result srv "tools/call" {"name" "slow" "arguments" {"ms" 50}} declared)
        id (get created "taskId")]
    (testing "the CreateTaskResult"
      (is (= "task" (get created "resultType")))
      (is (= "working" (get created "status")))
      (is (= 60000 (get created "ttlMs")))
      (is (= 20 (get created "pollIntervalMs")))
      (is (re-matches #"\d{4}-\d{2}-\d{2}T.*" (get created "createdAt")))
      (is (not-any? #(contains? created %) ["result" "error" "inputRequests" "requestState" "task"])))
    (testing "tasks/get until completed"
      (let [t (wait-status srv id "completed")]
        (is (= "complete" (get t "resultType")))
        (is (= "slow done" (get-in t ["result" "content" 0 "text"])))
        (is (= "complete" (get-in t ["result" "resultType"])))
        (is (not (contains? t "error")))))
    (testing "cancel on a terminal task is acknowledged and changes nothing"
      (is (= {"resultType" "complete"} (dissoc (result srv "tasks/cancel" {"taskId" id} declared) "_meta")))
      (is (= "completed" (get (result srv "tasks/get" {"taskId" id} declared) "status"))))
    (testing "bad and unknown ids"
      (is (= {"code" -32602 "message" "Unknown or inaccessible taskId"}
             (get (call srv "tasks/get" {"taskId" "nope"} declared) "error")))
      (is (= -32602 (get-in (call srv "tasks/get" {} declared) ["error" "code"])))
      (is (= -32602 (get-in (call srv "tasks/get" {"taskId" (apply str (repeat 300 "x"))} declared) ["error" "code"]))))))

(deftest outcomes
  (let [srv (make-server)]
    (testing "a tool error completes with isError"
      (let [id (get (result srv "tools/call" {"name" "tool-error"} declared) "taskId")
            t (wait-status srv id "completed")]
        (is (= "completed" (get t "status")))
        (is (true? (get-in t ["result" "isError"])))))
    (testing "an exception fails the task without leaking its message"
      (let [id (get (result srv "tools/call" {"name" "raises"} declared) "taskId")
            t (wait-status srv id "failed")]
        (is (= "failed" (get t "status")))
        (is (= {"code" -32603 "message" "Tool raised an exception"} (get t "error")))
        (is (not (contains? t "result")))))))

(deftest cancel-a-running-task
  (let [srv (make-server)
        id (get (result srv "tools/call" {"name" "slow" "arguments" {"ms" 10000}} declared) "taskId")]
    (is (= {"resultType" "complete"} (dissoc (result srv "tasks/cancel" {"taskId" id} declared) "_meta")))
    (let [t (result srv "tasks/get" {"taskId" id} declared)]
      (is (= "cancelled" (get t "status")))
      (is (not-any? #(contains? t %) ["result" "error"])))))

(deftest input-inside-a-task
  (let [srv (make-server)
        id (get (result srv "tools/call" {"name" "two"} declared) "taskId")
        t (wait-status srv id "input_required")]
    (is (= #{"a" "b"} (set (keys (get t "inputRequests")))))
    (testing "partial answers, unknown keys ignored"
      (is (= "complete" (get (result srv "tasks/update" {"taskId" id "inputResponses" {"a" {"action" "accept" "content" {"v" 1}}
                                                                                       "zzz" {"ignored" true}}}
                                     declared)
                             "resultType")))
      (let [t (result srv "tasks/get" {"taskId" id} declared)]
        (is (= "input_required" (get t "status")))
        (is (= ["b"] (keys (get t "inputRequests"))))))
    (result srv "tasks/update" {"taskId" id "inputResponses" {"b" {"action" "accept" "content" {"v" 2}}}} declared)
    (is (= "got 1 2" (get-in (wait-status srv id "completed") ["result" "content" 0 "text"])))
    (testing "malformed updates"
      (is (= -32602 (get-in (call srv "tasks/update" {"taskId" id} declared) ["error" "code"])))
      (is (= -32602 (get-in (call srv "tasks/update" {"taskId" id "inputResponses" {"a" 1}} declared) ["error" "code"]))))))

(deftest scoping-ttl-and-capacity
  (testing "a task belongs to the subject that made it"
    (let [srv (make-server)
          id (get (result srv "tools/call" {"name" "slow"} declared {:auth {:subject "ada"}}) "taskId")]
      (is (some? (result srv "tasks/get" {"taskId" id} declared {:auth {:subject "ada"}})))
      (is (= -32602 (get-in (call srv "tasks/get" {"taskId" id} declared {:auth {:subject "mallory"}}) ["error" "code"])))
      (is (= -32602 (get-in (call srv "tasks/get" {"taskId" id} declared) ["error" "code"])))))
  (testing "a task past its TTL is unknown"
    (let [srv (make-server {:tasks {:ttl-ms 50}})
          id (get (result srv "tools/call" {"name" "slow"} declared) "taskId")]
      (Thread/sleep 80)
      (is (= -32602 (get-in (call srv "tasks/get" {"taskId" id} declared) ["error" "code"])))))
  (testing "capacity"
    (let [srv (make-server {:tasks {:max-tasks 1}})]
      (result srv "tools/call" {"name" "slow" "arguments" {"ms" 2000}} declared)
      (is (= {"code" -32603 "message" "Task capacity exhausted" "data" {"retryable" true "limit" "max_tasks"}}
             (get (call srv "tools/call" {"name" "slow"} declared) "error"))))))

(deftest clients-await-tasks
  (let [srv (make-server)
        handlers {"elicitation/create" (fn [{:strs [message]}] {"action" "accept" "content" {"v" message}})}]
    (doseq [[label transport stop!] (let [{:keys [url stop!]} (http/serve! srv)]
                                      [["in process" (client/in-process srv) (fn [])]
                                       ["over HTTP" (http/url url) stop!]])]
      (testing label
        (let [c (client/connect transport {:tasks true :handlers handlers})]
          (try
            (let [created (client/call-tool c "two")]
              (is (client/task? created))
              (let [done (client/await-task c created {:timeout-ms 5000})]
                (is (= "completed" (get done "status")))
                (is (= "got a? b?" (get-in done ["result" "content" 0 "text"])))))
            (let [created (client/call-tool c "slow" {"ms" 5000})]
              (client/cancel-task c (get created "taskId"))
              (is (= "cancelled" (get (client/task c (get created "taskId")) "status"))))
            (finally (client/close c) (stop!))))))))

(deftest status-notifications
  (let [srv (make-server)
        heard (atom [])
        c (client/connect (client/in-process srv)
                          {:tasks true :handlers {"elicitation/create" (fn [{:strs [message]}] {"action" "accept" "content" {"v" message}})}})]
    (try
      (let [created (client/call-tool c "two")
            id (get created "taskId")
            sub (client/listen c {"taskIds" [id "unknown-task"]} #(swap! heard conj %))]
        (testing "the acknowledgement keeps only tasks the caller can see"
          (is (= {"taskIds" [id]} (get (deref (:acknowledged sub) 2000 nil) "notifications"))))
        (client/update-task c id {"a" {"action" "accept" "content" {"v" 1}}})
        (client/update-task c id {"b" {"action" "accept" "content" {"v" 2}}})
        (is (loop [n 0] (cond (some #(= "completed" (get-in % ["params" "status"])) @heard) true
                              (> n 200) false :else (do (Thread/sleep 10) (recur (inc n))))))
        (let [n (first (filter #(= "completed" (get-in % ["params" "status"])) @heard))]
          (is (= "notifications/tasks" (get n "method")))
          (is (= id (get-in n ["params" "taskId"])))
          (is (= "got 1 2" (get-in n ["params" "result" "content" 0 "text"])))
          (is (some? (get-in n ["params" "_meta" "io.modelcontextprotocol/subscriptionId"]))))
        ((:stop! sub)))
      (testing "await-task can follow notifications instead of polling"
        (let [done (client/await-task c (client/call-tool c "two") {:listen? true :timeout-ms 5000})]
          (is (= "got a? b?" (get-in done ["result" "content" 0 "text"])))))
      (finally (client/close c))))
  (testing "taskIds needs the Tasks declaration"
    (let [srv (make-server)]
      (is (= -32021 (get-in (call srv "subscriptions/listen" {"notifications" {"taskIds" ["x"]}} undeclared) ["error" "code"])))
      (is (= -32602 (get-in (call srv "subscriptions/listen" {"notifications" {"taskIds" "x"}} declared) ["error" "code"]))))))

(deftest expired-tasks-release-their-threads
  (let [srv (make-server {:tasks {:ttl-ms 300 :poll-interval-ms 20}})
        work-of (fn [id] (get-in @(:tasks (:task-store srv)) [id :work]))]
    (testing "a task waiting for input that never comes"
      (let [id (get (result srv "tools/call" {"name" "two"} declared) "taskId")
            _ (wait-status srv id "input_required")
            work (work-of id)]
        (is (some? work))
        (is (loop [n 0] (cond (future-done? work) true
                              (> n 100) false
                              :else (do (Thread/sleep 50) (recur (inc n)))))
            "the worker returns once the TTL passes")))
    (testing "a working task past its TTL is cancelled when it is reaped"
      (let [id (get (result srv "tools/call" {"name" "slow" "arguments" {"ms" 30000}} declared) "taskId")
            work (work-of id)]
        (Thread/sleep 400)
        ;; creating a task reaps the expired ones
        (result srv "tools/call" {"name" "slow" "arguments" {"ms" 1}} declared)
        (is (future-cancelled? work))))))

(deftest task-state-comes-back-as-json
  (let [srv (make-server {:tools [{:name "stateful" :task :optional
                                   :handler (fn [_ ctx]
                                              (if (server/input-response ctx "q")
                                                (pr-str [(:state ctx) (keys (:input-responses ctx))])
                                                (server/input-required {"q" (input/elicit "q?" {"type" "object"})}
                                                                       {:step 1})))}]})
        id (get (result srv "tools/call" {"name" "stateful"} declared) "taskId")]
    (wait-status srv id "input_required")
    (result srv "tasks/update" {"taskId" id "inputResponses" {"q" {"action" "accept" "content" {}}}} declared)
    (is (= "[{\"step\" 1} (\"q\")]" (get-in (wait-status srv id "completed") ["result" "content" 0 "text"])))))
