(ns presa.proxy-test
  (:require [clojure.string]
            [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.input :as input]
            [presa.protocol :as protocol]
            [presa.proxy :as proxy]
            [presa.server :as server]
            [presa.transport.http :as http]))

(defn- backend-a []
  (server/server
   {:name "a" :version "1" :list-changed true
    :tools [{:name "echo" :description "Echo" :handler (fn [{:strs [text]} _] text)}
            {:name "steps" :description "Reports progress"
             :handler (fn [_ ctx] (server/progress! ctx 1 {:total 2}) (server/progress! ctx 2 {:total 2}) "stepped")}
            {:name "ask" :description "Asks for a name"
             :handler (fn [_ ctx]
                        (if-let [r (server/input-response ctx "who")]
                          (str "hi " (get-in r ["content" "name"]))
                          (server/input-required {"who" (input/elicit "who?" {"type" "object"})} {"backend" "state"})))}]
    :resources [{:uri "a:/readme" :name "readme" :handler (fn [_] "A readme")}]
    :resource-templates [{:uri-template "a:/items/{id}" :name "item" :handler (fn [{:strs [id]} _] (str "item " id))}]
    :prompts [{:name "intro" :arguments [{:name "who" :required true}]
               :handler (fn [{:strs [who]} _] (str "introduce " who))
               :complete {"who" (fn [v _ _] (filter #(.startsWith ^String % v) ["ada" "alan"]))}}]}))

(defn- backend-b []
  (server/server {:name "b" :version "1"
                  :tools [{:name "echo" :description "Echo, loudly"
                           :handler (fn [{:strs [text]} _] (clojure.string/upper-case text))}]}))

(defn- connect [srv] (client/connect (client/in-process srv)))

(deftest proxying
  (let [a (backend-a)
        {:keys [server close!]} (proxy/proxy-server [{:prefix "a" :client (connect a)}
                                                     {:prefix "b" :client (connect (backend-b))}]
                                                    {:name "front" :version "1" :versions protocol/versions})
        handlers {"elicitation/create" (fn [_] {"action" "accept" "content" {"name" "Ada"}})}
        c (client/connect (client/in-process server) {:handlers handlers})]
    (try
      (is (= ["a_echo" "a_steps" "a_ask" "b_echo"] (map #(get % "name") (client/list-tools c))))
      (is (= "hi" (client/text (client/call-tool c "a_echo" {"text" "hi"}))))
      (is (= "HI" (client/text (client/call-tool c "b_echo" {"text" "hi"}))))
      (testing "progress is forwarded"
        (let [seen (atom [])]
          (is (= "stepped" (client/text (client/call-tool c "a_steps" {} {:on-progress #(swap! seen conj (get % "progress"))}))))
          (is (= [1 2] @seen))))
      (testing "input requests pass through to the caller and back"
        (is (= "hi Ada" (client/text (client/call-tool c "a_ask")))))
      (testing "resources, templates, prompts and completion"
        (is (= "A readme" (get-in (client/read-resource c "a:/readme") [0 "text"])))
        (is (= "item 7" (get-in (client/read-resource c "a:/items/7") [0 "text"])))
        (is (= "introduce ada" (get-in (client/get-prompt c "a_intro" {"who" "ada"}) ["messages" 0 "content" "text"])))
        (is (= ["ada" "alan"] (get (client/complete c {"type" "ref/prompt" "name" "a_intro"} "who" "a") "values"))))
      (testing "an initialize-era caller gets input as requests"
        (let [old (client/connect (client/in-process server) {:versions ["2025-11-25"] :handlers handlers})]
          (try (is (= "hi Ada" (client/text (client/call-tool old "a_ask"))))
               (finally (client/close old)))))
      (testing "a backend's list change resyncs the proxy"
        (server/add-tool! a {:name "late" :description "Late" :handler (fn [_ _] "late")})
        (is (loop [n 0]
              (cond (some #{"a_late"} (map #(get % "name") (client/list-tools c))) true
                    (> n 100) false
                    :else (do (Thread/sleep 20) (recur (inc n))))))
        (is (= "late" (client/text (client/call-tool c "a_late")))))
      (finally (client/close c) (close!)))))

(deftest collisions-are-refused
  (is (= :invalid-input
         (:kind (ex-data (try (proxy/proxy-server [{:prefix "x" :client (connect (backend-b))}
                                                   {:prefix "x" :client (connect (backend-b))}]
                                                  {:name "p" :version "1"})
                              (catch Exception e e)))))))

;; Logs and initialize-era input

(defn- chatty-backend
  "A backend that logs and, on initialize-era sessions, asks its client for
  a name with elicitation/create."
  ([] (chatty-backend {}))
  ([opts]
  (server/server
   (merge opts
   {:name "old" :version "1" :versions protocol/versions :logging true
    :tools [{:name "noisy" :description "Logs at two levels"
             :handler (fn [_ ctx]
                        (server/log! ctx "debug" "a detail")
                        (server/log! ctx "warning" "a warning" "noisy")
                        "logged")}
            {:name "greet" :description "Asks who to greet"
             :handler (fn [_ ctx]
                        (let [r (server/client-request! ctx "elicitation/create"
                                                        {"mode" "form" "message" "who?"
                                                         "requestedSchema" {"type" "object"
                                                                            "properties" {"name" {"type" "string"}}}})]
                          (str "hello " (get-in r ["content" "name"]))))}
            {:name "greet-twice" :description "Asks twice"
             :handler (fn [_ ctx]
                        (let [ask #(get-in (server/client-request! ctx "elicitation/create"
                                                                   {"mode" "form" "message" %
                                                                    "requestedSchema" {"type" "object"
                                                                                       "properties" {"name" {"type" "string"}}}})
                                           ["content" "name"])]
                          (str (ask "first?") " and " (ask "second?"))))}]}))))

(def ^:private elicit-caps {"elicitation" {"form" {}}})

(defn- names-from [names]
  (let [left (atom names)]
    {"elicitation/create" (fn [_] (let [n (first @left)] (swap! left rest)
                                    {"action" "accept" "content" {"name" n}}))}))

(defn- check-forwarding [backend-client]
  (let [{:keys [server close!]} (proxy/proxy-server [{:prefix "old" :client backend-client}]
                                                    {:name "front" :version "1" :versions protocol/versions})]
    (try
      (testing "logs reach the caller at its level"
        (let [logs (atom [])
              c (client/connect (client/in-process server) {:log-level "info" :on-log #(swap! logs conj %)})]
          (try (is (= "logged" (client/text (client/call-tool c "old_noisy"))))
               (is (= [{"level" "warning" "data" "a warning" "logger" "noisy"}] @logs))
               (finally (client/close c)))))
      (testing "a 2026-07-28 caller answers an initialize-era backend's request"
        (let [c (client/connect (client/in-process server) {:handlers (names-from ["Ada"])})]
          (try (is (= "hello Ada" (client/text (client/call-tool c "old_greet"))))
               (finally (client/close c)))))
      (testing "an initialize-era caller answers it too"
        (let [c (client/connect (client/in-process server) {:versions ["2025-11-25"] :handlers (names-from ["Ada"])})]
          (try (is (= "hello Ada" (client/text (client/call-tool c "old_greet"))))
               (finally (client/close c)))))
      (testing "a backend that asks twice is answered twice"
        (let [c (client/connect (client/in-process server) {:handlers (names-from ["Ada" "Alan"])})]
          (try (is (= "Ada and Alan" (client/text (client/call-tool c "old_greet-twice"))))
               (finally (client/close c)))))
      (finally (close!)))))

(deftest forwarding-from-an-in-process-backend
  (check-forwarding (client/connect (client/in-process (chatty-backend))
                                    {:versions ["2025-11-25"] :capabilities elicit-caps :log-level "debug"})))

(deftest forwarding-from-an-http-backend
  (let [{:keys [url stop!]} (http/serve! (chatty-backend) {})]
    (try
      (check-forwarding (client/connect (http/url url)
                                        {:versions ["2025-11-25"] :capabilities elicit-caps}))
      (finally (stop!)))))

(deftest a-stale-parked-call-is-refused
  (let [stopped (atom (promise))
        on-event (fn [{:keys [event method outcome]}]
                   (when (and (= :request/stop event) (= "tools/call" method))
                     (deliver @stopped outcome)))
        {:keys [server close!]} (proxy/proxy-server [{:prefix "old" :client (client/connect (client/in-process (chatty-backend {:on-event on-event}))
                                                                                            {:versions ["2025-11-25"] :capabilities elicit-caps})}]
                                                    {:name "front" :version "1"})
        c (client/connect (client/in-process server) {:capabilities elicit-caps})]
    (try
      (let [first-try (client/request c "tools/call" {"name" "old_greet" "arguments" {}} {:input :return})]
        (is (= "input_required" (get first-try "resultType")))
        (is (= "elicitation/create" (get-in first-try ["inputRequests" "backend" "method"])))
        (let [answer {"inputResponses" {"backend" {"action" "accept" "content" {"name" "Ada"}}}
                      "requestState" (get first-try "requestState")}
              params (merge {"name" "old_greet" "arguments" {}} answer)]
          (is (= "hello Ada" (client/text (client/request c "tools/call" params))))
          (testing "the same state cannot resume the call again"
            (is (= -32602 (:code (ex-data (try (client/request c "tools/call" params) (catch Exception e e)))))))))
      (testing "a caller that cannot answer gets -32021 and the backend is not left waiting"
        (reset! stopped (promise))
        (let [plain (client/connect (client/in-process server))]
          (try (is (= -32021 (:code (ex-data (try (client/call-tool plain "old_greet") (catch Exception e e))))))
               (is (not= :still-waiting (deref @stopped 5000 :still-waiting)) "the backend's call ended at once")
               (finally (client/close plain)))))
      (finally (client/close c) (close!)))))

;; Cancellation

(defn- slow-backend
  "A backend whose wait tool runs until cancelled, then delivers seen."
  [seen versions]
  (server/server
   {:name "slow" :version "1" :versions versions
    :tools [{:name "wait" :description "Waits until cancelled"
             :handler (fn [_ ctx]
                        (loop [n 0]
                          (cond
                            ((:cancelled? ctx)) (do (deliver seen :cancelled) "cancelled")
                            (> n 500) (do (deliver seen :ran-out) "done")
                            :else (do (try (Thread/sleep 10)
                                           ;; an interrupted handler is a cancelled one too
                                           (catch InterruptedException _ (deliver seen :cancelled)))
                                      (recur (inc n))))))}]}))

(deftest a-cancelled-call-is-cancelled-at-the-backend
  (doseq [versions [protocol/versions ["2025-11-25"]]]
    (testing (str "a backend speaking " (first versions))
      (let [seen (promise)
            backend (client/connect (client/in-process (slow-backend seen protocol/versions)) {:versions versions})
            {:keys [server close!]} (proxy/proxy-server [{:prefix "s" :client backend}] {:name "front" :version "1"})
            c (client/connect (client/in-process server))]
        (try
          (is (= :timeout (:kind (ex-data (try (client/call-tool c "s_wait" {} {:timeout-ms 200})
                                               (catch Exception e e))))))
          (is (= :cancelled (deref seen 3000 :still-running)))
          (finally (client/close c) (close!)))))))

(deftest a-parked-call-is-freed-when-its-caller-gives-up
  (let [stopped (promise)
        on-event (fn [{:keys [event method]}]
                   (when (and (= :request/stop event) (= "tools/call" method)) (deliver stopped true)))
        backend (client/connect (client/in-process (chatty-backend {:on-event on-event}))
                                {:versions ["2025-11-25"] :capabilities elicit-caps :timeout-ms 30000})
        {:keys [server close!]} (proxy/proxy-server [{:prefix "old" :client backend}]
                                                    {:name "front" :version "1" :versions protocol/versions})
        never (promise)
        ;; an initialize-era caller that never answers, then gives up
        c (client/connect (client/in-process server)
                          {:versions ["2025-11-25"]
                           :handlers {"elicitation/create" (fn [_] @never)}})]
    (try
      (is (= :timeout (:kind (ex-data (try (client/call-tool c "old_greet" {} {:timeout-ms 300})
                                           (catch Exception e e))))))
      (is (true? (deref stopped 3000 false)) "the backend's call ended, long before its 30 s timeout")
      (finally (deliver never {"action" "cancel"}) (client/close c) (close!)))))

(deftest resyncs-keep-names-unique-and-follow-bursts
  (let [a (server/server {:name "a" :version "1" :list-changed true :tools [{:name "x" :handler (fn [_ _] "from a")}]})
        b (server/server {:name "b" :version "1" :list-changed true :tools [{:name "y" :handler (fn [_ _] "from b")}]})
        logs (atom [])
        {:keys [server close!]} (proxy/proxy-server [{:prefix "p" :client (connect a)} {:prefix "p" :client (connect b)}]
                                                    {:name "front" :version "1" :log (fn [& parts] (swap! logs conj (apply str parts)))})
        c (client/connect (client/in-process server))
        eventually (fn [f] (loop [n 0] (cond (f) true (> n 100) false :else (do (Thread/sleep 20) (recur (inc n))))))]
    (try
      ;; the proxy follows b once its subscription is acknowledged: a change
      ;; that shows up proves it, so the next one is not missed
      (server/add-tool! b {:name "z" :handler (fn [_ _] "z")})
      (is (eventually #(some (fn [t] (= "p_z" (get t "name"))) (client/list-tools c))))
      (testing "a name another backend has is skipped and logged"
        (server/add-tool! b {:name "x" :handler (fn [_ _] "b's x")})
        (is (eventually #(some (fn [l] (clojure.string/includes? l "p_x")) @logs)) (str @logs))
        (is (= "from a" (client/text (client/call-tool c "p_x")))))
      (testing "a burst of changes all arrive"
        (dotimes [i 20] (server/add-tool! a {:name (str "t" i) :handler (fn [_ _] "t")}))
        (is (eventually #(= 20 (count (filter (fn [t] (re-matches #"p_t\d+" (get t "name"))) (client/list-tools c)))))))
      (finally (client/close c) (close!)))))
