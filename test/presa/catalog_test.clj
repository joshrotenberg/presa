(ns presa.catalog-test
  "Changing a running server's tools, resources and prompts."
  (:require [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.jsonrpc :as rpc]
            [presa.server :as server]
            [presa.transport.http :as http]))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}})

(defn- tool [name] {:name name :handler (fn [_ _] (str name " ran"))})

(defn- names [srv]
  (map #(get % "name")
       (get-in (second (server/handle srv {} (rpc/request 1 "tools/list" {"_meta" meta-2026})))
               ["result" "tools"])))

(deftest tools
  (let [srv (server/server {:name "t" :version "1" :tools [(tool "a") (tool "b")]})]
    (server/add-tool! srv (tool "c"))
    (is (= ["a" "b" "c"] (names srv)))
    (testing "replacing keeps the place"
      (server/add-tool! srv {:name "a" :description "new a" :handler (fn [_ _] "new")})
      (is (= ["a" "b" "c"] (names srv)))
      (is (= "new a" (:description (server/tool srv "a")))))
    (server/remove-tool! srv "b")
    (is (= ["a" "c"] (names srv)))
    (is (nil? (server/tool srv "b")))
    (is (= -32602 (get-in (second (server/handle srv {} (rpc/request 1 "tools/call" {"name" "b" "_meta" meta-2026})))
                          ["error" "code"])))
    (testing "definitions are checked"
      (is (= :invalid-input (:kind (ex-data (try (server/add-tool! srv {:name "x"}) (catch Exception e e)))))))))

(deftest resources-templates-and-prompts
  (let [srv (server/server {:name "t" :version "1"})
        call (fn [method params] (get (second (server/handle srv {} (rpc/request 1 method (assoc params "_meta" meta-2026)))) "result"))]
    (server/add-resource! srv {:uri "x:/a" :name "a" :handler (fn [_] "A")})
    (server/add-resource-template! srv {:uri-template "x:/t/{id}" :name "t" :handler (fn [{:strs [id]} _] id)})
    (server/add-prompt! srv {:name "p" :handler (fn [_ _] "hi")})
    (is (= "A" (get-in (call "resources/read" {"uri" "x:/a"}) ["contents" 0 "text"])))
    (is (= "7" (get-in (call "resources/read" {"uri" "x:/t/7"}) ["contents" 0 "text"])))
    (is (= ["p"] (map #(get % "name") (get (call "prompts/list" {}) "prompts"))))
    (server/remove-resource! srv "x:/a")
    (server/remove-resource-template! srv "x:/t/{id}")
    (server/remove-prompt! srv "p")
    (is (nil? (call "resources/read" {"uri" "x:/a"})))
    (is (nil? (call "prompts/list" {})) "no prompts left: the capability and method are gone")))

(deftest cursors-expire-when-the-list-changes
  (let [srv (server/server {:name "t" :version "1" :page-size 1 :tools [(tool "a") (tool "b")]})
        list-page (fn [cursor] (second (server/handle srv {} (rpc/request 1 "tools/list" (cond-> {"_meta" meta-2026}
                                                                                            cursor (assoc "cursor" cursor))))))
        cursor (get-in (list-page nil) ["result" "nextCursor"])]
    (is (= ["b"] (map #(get % "name") (get-in (list-page cursor) ["result" "tools"]))))
    (server/add-tool! srv (tool "c"))
    (is (= {"code" -32602 "message" "Pagination cursor has expired"} (get (list-page cursor) "error")))))

(deftest a-request-reads-one-snapshot
  (let [started (promise)
        go (promise)
        srv (server/server {:name "t" :version "1"
                            :tools [{:name "slow" :handler (fn [_ ctx]
                                                             (deliver started true)
                                                             @go
                                                             (str (some? (server/tool (:server ctx) "slow"))))}]})
        result (future (second (server/handle srv {} (rpc/request 1 "tools/call" {"name" "slow" "_meta" meta-2026}))))]
    @started
    (server/remove-tool! srv "slow")
    (deliver go true)
    (is (= "false" (get-in @result ["result" "content" 0 "text"]))
        "the call finishes, and the handler sees the current catalog through the server")))

(deftest listening-clients-are-told
  (let [srv (server/server {:name "t" :version "1" :list-changed true :tools [(tool "a")]})
        {:keys [url stop!]} (http/serve! srv)
        c (client/connect (http/url url))
        seen (atom [])]
    (try
      (let [sub (client/listen c {"toolsListChanged" true} #(swap! seen conj (get % "method")))]
        (deref (:acknowledged sub) 2000 nil)
        (server/add-tool! srv (tool "b"))
        (is (loop [n 0] (cond (some #{"notifications/tools/list_changed"} @seen) true
                              (> n 100) false
                              :else (do (Thread/sleep 20) (recur (inc n))))))
        (is (= ["a" "b"] (map #(get % "name") (client/list-tools c))))
        (testing "a new tool is callable over HTTP, Mcp-Param checks included"
          (server/add-tool! srv {:name "region"
                                 :input-schema {"type" "object" "properties" {"region" {"type" "string" "x-mcp-header" "Region"}}}
                                 :handler (fn [{:strs [region]} _] region)})
          (is (= "eu" (client/text (client/call-tool c "region" {"region" "eu"})))))
        ((:stop! sub)))
      (finally (client/close c) (stop!)))))
