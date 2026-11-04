(ns presa.subscriptions-test
  (:require [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.jsonrpc :as rpc]
            [presa.server :as server]
            [presa.transport.http :as http]))

(defn- make-server [& [versions]]
  (server/server
   {:name "t" :version "1" :list-changed true :subscribe true
    :versions (or versions ["2026-07-28"])
    :tools [{:name "change_tools" :handler (fn [_ ctx] (server/list-changed! ctx "tools") "changed")}
            {:name "change_prompts" :handler (fn [_ ctx] (server/list-changed! ctx "prompts") "changed")}
            {:name "touch" :handler (fn [{:strs [uri]} ctx] (server/resource-updated! ctx uri) "touched")}]
    :resources [{:uri "file:///docs" :name "docs" :handler (fn [_] "docs")}]
    :prompts [{:name "p" :handler (fn [_ _] "p")}]}))

(def sub-key "io.modelcontextprotocol/subscriptionId")

(defn- wait-for [pred]
  (loop [n 0]
    (cond (pred) true
          (> n 100) false
          :else (do (Thread/sleep 20) (recur (inc n))))))

(defn- exercise [transport srv]
  (let [c (client/connect transport)]
    (try
      (is (= {"listChanged" true} (get-in (client/info c) [:server-capabilities "tools"])))
      (is (= {"listChanged" true "subscribe" true} (get-in (client/info c) [:server-capabilities "resources"])))
      (let [seen (atom [])
            sub (client/listen c {"toolsListChanged" true "resourceSubscriptions" ["file:///docs"]}
                               #(swap! seen conj %))]
        (testing "the acknowledgement comes first and names what the server will send"
          (is (= {"notifications" {"toolsListChanged" true "resourceSubscriptions" ["file:///docs"]}}
                 (dissoc (deref (:acknowledged sub) 2000 nil) "_meta")))
          (is (= "notifications/subscriptions/acknowledged" (get (first @seen) "method"))))
        (client/call-tool c "change_prompts")
        (client/call-tool c "change_tools")
        (client/call-tool c "touch" {"uri" "file:///docs/a.md"})
        (client/call-tool c "touch" {"uri" "file:///other"})
        (is (wait-for #(= 3 (count @seen))))
        (testing "only what was asked for, each tagged with the stream"
          (is (= ["notifications/subscriptions/acknowledged" "notifications/tools/list_changed"
                  "notifications/resources/updated"]
                 (map #(get % "method") @seen)))
          (is (apply = (map #(get-in % ["params" "_meta" sub-key]) @seen)))
          (is (= "file:///docs/a.md" (get-in (nth @seen 2) ["params" "uri"]))))
        (testing "stopping ends the stream on both sides"
          ((:stop! sub))
          (is (= :stopped (deref (:done sub) 2000 nil)))
          (is (wait-for #(empty? @(:listeners srv))))))
      (testing "the server ends a stream with a final result"
        (let [sub (client/listen c {"promptsListChanged" true} (fn [_]))]
          (deref (:acknowledged sub) 2000 nil)
          (server/end-subscriptions! srv)
          (let [result (deref (:done sub) 2000 nil)]
            (is (= "complete" (get result "resultType")))
            (is (some? (get-in result ["_meta" sub-key]))))))
      (finally (client/close c)))))

(deftest in-process
  (let [srv (make-server)]
    (exercise (client/in-process srv) srv)))

(deftest over-http
  (let [srv (make-server)
        {:keys [url stop!]} (http/serve! srv {:keepalive-ms 100})]
    (try
      (exercise (http/url url) srv)
      (finally (stop!)))))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}})

(deftest requests
  (testing "a server without list-changed or subscribe has no subscriptions/listen"
    (let [srv (server/server {:name "t" :version "1" :tools [{:name "x" :handler (fn [_ _] "x")}]})]
      (is (= -32601 (get-in (second (server/handle srv {} (rpc/request 1 "subscriptions/listen"
                                                                         {"notifications" {} "_meta" meta-2026})))
                            ["error" "code"])))))
  (testing "malformed requests"
    (let [srv (make-server)
          err (fn [notifications]
                (get-in (second (server/handle srv {} (rpc/request 1 "subscriptions/listen"
                                                                   (cond-> {"_meta" meta-2026}
                                                                     notifications (assoc "notifications" notifications)))))
                        ["error" "code"]))]
      (is (= -32602 (err nil)))
      (is (= -32602 (err {"toolsListChanged" "yes"})))
      (is (= -32602 (err {"resourceSubscriptions" ["relative"]}))))))

(deftest initialize-era-notifications
  (let [srv (make-server ["2026-07-28" "2025-11-25"])
        sent (atom [])
        notify #(swap! sent conj %)
        [session] (server/handle srv {} (rpc/request 0 "initialize" {"protocolVersion" "2025-11-25" "capabilities" {}
                                                                     "clientInfo" {"name" "c" "version" "0"}})
                                 {:notify notify})
        call (fn [message] (second (server/handle srv session message {:notify notify})))]
    (testing "list changes reach the session without a subscription"
      (server/list-changed! srv "tools")
      (is (= [{"jsonrpc" "2.0" "method" "notifications/tools/list_changed"}] @sent)))
    (testing "resources/subscribe and unsubscribe"
      (reset! sent [])
      (is (= {} (get (call (rpc/request 1 "resources/subscribe" {"uri" "file:///docs"})) "result")))
      (server/resource-updated! srv "file:///docs")
      (is (= [{"jsonrpc" "2.0" "method" "notifications/resources/updated" "params" {"uri" "file:///docs"}}] @sent))
      (is (= {} (get (call (rpc/request 2 "resources/unsubscribe" {"uri" "file:///docs"})) "result")))
      (reset! sent [])
      (server/resource-updated! srv "file:///docs")
      (is (= [] @sent)))
    (testing "closing the session forgets it"
      (server/close-session! srv session)
      (reset! sent [])
      (server/list-changed! srv "tools")
      (is (= [] @sent)))))

(deftest initialize-era-http-sessions-hear-changes-on-their-stream
  (let [srv (make-server ["2026-07-28" "2025-11-25"])
        {:keys [url stop!]} (http/serve! srv)
        heard (atom [])
        c (client/connect (http/url url) {:versions ["2025-11-25"]
                                          :on-notification #(swap! heard conj (get % "method"))})]
    (try
      (is (= "2025-11-25" (:version (client/info c))))
      ;; the client opens the session stream just after
      ;; notifications/initialized: announce until it is there to hear
      (is (wait-for #(do (server/list-changed! srv "tools")
                         (some #{"notifications/tools/list_changed"} @heard))))
      (testing "resources/subscribe updates arrive the same way"
        (client/request c "resources/subscribe" {"uri" "file:///docs"})
        (server/resource-updated! srv "file:///docs")
        (is (wait-for #(some #{"notifications/resources/updated"} @heard))))
      (finally (client/close c) (stop!)))))
