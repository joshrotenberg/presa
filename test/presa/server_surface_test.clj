(ns presa.server-surface-test
  "Resources, prompts, completion, pagination and caching hints."
  (:require [clojure.test :refer [deftest is testing]]
            [presa.content :as content]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.server :as server]))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}})

(def people {"ada" {"name" "Ada"} "alan" {"name" "Alan"}})

(def srv
  (server/server
   {:name "t" :version "1"
    :versions protocol/versions
    :resources [{:uri "file:///readme.txt" :name "readme" :mime-type "text/plain"
                 :handler (fn [_] "read me")}
                {:uri "file:///logo.png" :name "logo" :mime-type "image/png"
                 :handler (fn [_] (byte-array [1 2 3]))}
                {:uri "file:///two" :name "two"
                 :handler (fn [_] [(content/resource-contents "file:///two#a" "a")
                                   (content/resource-contents "file:///two#b" "b")])}]
    :resource-templates [{:uri-template "people://{name}/profile" :name "profile"
                          :mime-type "application/json"
                          :handler (fn [{:strs [name]} _]
                                     (when-let [p (get people name)]
                                       (pr-str p)))
                          :complete {"name" (fn [value _ _]
                                              (filter #(.startsWith ^String % value) (sort (keys people))))}}]
    :prompts [{:name "greet" :description "Greet someone"
               :arguments [{:name "name" :required true} {:name "style"}]
               :handler (fn [{:strs [name style]} _]
                          (str "Greet " name (when style (str " in a " style " way"))))
               :complete {"style" (fn [_ _ _] {"values" ["formal" "casual"] "total" 2 "hasMore" false})}}
              {:name "chat"
               :handler (fn [_ _]
                          ["hello"
                           {"role" "assistant" "content" (content/image (byte-array [9]) "image/png")}
                           {"role" "user" "content" (content/embedded-resource "file:///readme.txt" "read me" {:mime-type "text/plain"})}])}]}))

(defn- call
  ([method] (call method {}))
  ([method params]
   (second (server/handle srv {} (rpc/request 1 method (assoc params "_meta" meta-2026))))))

(defn- result [method & [params]] (get (call method params) "result"))
(defn- error-code [method & [params]] (get-in (call method params) ["error" "code"]))

(deftest capabilities
  (is (= {"resources" {} "prompts" {} "completions" {}}
         (get (result "server/discover") "capabilities"))))

(deftest resources
  (testing "resources/list"
    (is (= [{"uri" "file:///readme.txt" "name" "readme" "mimeType" "text/plain"}
            {"uri" "file:///logo.png" "name" "logo" "mimeType" "image/png"}
            {"uri" "file:///two" "name" "two"}]
           (get (result "resources/list") "resources"))))
  (testing "resources/templates/list"
    (is (= [{"uriTemplate" "people://{name}/profile" "name" "profile" "mimeType" "application/json"}]
           (get (result "resources/templates/list") "resourceTemplates"))))
  (testing "resources/read"
    (is (= [{"uri" "file:///readme.txt" "mimeType" "text/plain" "text" "read me"}]
           (get (result "resources/read" {"uri" "file:///readme.txt"}) "contents")))
    (is (= [{"uri" "file:///logo.png" "mimeType" "image/png" "blob" "AQID"}]
           (get (result "resources/read" {"uri" "file:///logo.png"}) "contents")))
    (is (= ["a" "b"] (map #(get % "text") (get (result "resources/read" {"uri" "file:///two"}) "contents"))))
    (is (= [{"uri" "people://ada/profile" "mimeType" "application/json" "text" "{\"name\" \"Ada\"}"}]
           (get (result "resources/read" {"uri" "people://ada/profile"}) "contents"))))
  (testing "a resource that does not exist"
    (doseq [uri ["file:///nope" "people://nobody/profile"]]
      (is (= {"code" -32602 "message" "Resource not found" "data" {"uri" uri}}
             (get (call "resources/read" {"uri" uri}) "error")))))
  (testing "a read without a uri, or with a relative one"
    (is (= -32602 (error-code "resources/read")))
    (is (= -32602 (error-code "resources/read" {"uri" "readme.txt"})))))

(deftest prompts
  (testing "prompts/list"
    (is (= [{"name" "greet" "description" "Greet someone"
             "arguments" [{"name" "name" "required" true} {"name" "style"}]}
            {"name" "chat"}]
           (get (result "prompts/list") "prompts"))))
  (testing "prompts/get"
    (is (= {"description" "Greet someone"
            "messages" [{"role" "user" "content" {"type" "text" "text" "Greet Ada in a formal way"}}]}
           (select-keys (result "prompts/get" {"name" "greet" "arguments" {"name" "Ada" "style" "formal"}})
                        ["description" "messages"])))
    (let [messages (get (result "prompts/get" {"name" "chat"}) "messages")]
      (is (= ["user" "assistant" "user"] (map #(get % "role") messages)))
      (is (= {"type" "image" "data" "CQ==" "mimeType" "image/png"} (get (second messages) "content")))
      (is (= {"type" "resource" "resource" {"uri" "file:///readme.txt" "mimeType" "text/plain" "text" "read me"}}
             (get (nth messages 2) "content")))))
  (testing "errors"
    (is (= {"code" -32602 "message" "Missing required prompt arguments" "data" {"missing" ["name"]}}
           (get (call "prompts/get" {"name" "greet"}) "error")))
    (is (= -32602 (error-code "prompts/get" {"name" "greet" "arguments" {"name" 1}})))
    (is (= -32602 (error-code "prompts/get" {"name" "nope"})))))

(deftest completion
  (is (= {"completion" {"values" ["ada" "alan"]}}
         (select-keys (result "completion/complete" {"ref" {"type" "ref/resource" "uri" "people://{name}/profile"}
                                                     "argument" {"name" "name" "value" "a"}})
                      ["completion"])))
  (is (= {"values" ["formal" "casual"] "total" 2 "hasMore" false}
         (get (result "completion/complete" {"ref" {"type" "ref/prompt" "name" "greet"}
                                             "argument" {"name" "style" "value" ""}})
              "completion")))
  (testing "an argument without a completer completes to nothing"
    (is (= {"values" []}
           (get (result "completion/complete" {"ref" {"type" "ref/prompt" "name" "greet"}
                                               "argument" {"name" "name" "value" "x"}})
                "completion"))))
  (testing "more than 100 values"
    (let [big (server/server {:name "t" :version "1"
                              :prompts [{:name "p" :handler (fn [_ _] "x")
                                         :complete {"a" (fn [_ _ _] (map str (range 150)))}}]})
          completion (get-in (second (server/handle big {} (rpc/request 1 "completion/complete"
                                                                        {"ref" {"type" "ref/prompt" "name" "p"}
                                                                         "argument" {"name" "a" "value" ""}
                                                                         "_meta" meta-2026})))
                             ["result" "completion"])]
      (is (= 100 (count (get completion "values"))))
      (is (= 150 (get completion "total")))
      (is (true? (get completion "hasMore")))))
  (testing "errors"
    (is (= -32602 (error-code "completion/complete" {"ref" {"type" "ref/prompt" "name" "nope"}
                                                     "argument" {"name" "a" "value" ""}})))
    (is (= -32602 (error-code "completion/complete" {"ref" {"type" "ref/other"}
                                                     "argument" {"name" "a" "value" ""}})))
    (is (= -32602 (error-code "completion/complete" {})))))

(deftest capability-gating
  (testing "a method for a capability the server lacks is not found"
    (is (= -32601 (error-code "tools/list")))
    (is (= -32601 (error-code "tools/call" {"name" "x"})))))

(deftest pagination
  (let [tools (for [i (range 5)] {:name (str "t" i) :handler (fn [_ _] "x")})
        paged (server/server {:name "t" :version "1" :page-size 2 :tools tools})
        list-page (fn [cursor]
                    (second (server/handle paged {} (rpc/request 1 "tools/list"
                                                                 (cond-> {"_meta" meta-2026}
                                                                   cursor (assoc "cursor" cursor))))))
        pages (loop [cursor nil acc []]
                (let [r (get (list-page cursor) "result")
                      acc (conj acc (map #(get % "name") (get r "tools")))]
                  (if-let [next-cursor (get r "nextCursor")]
                    (recur next-cursor acc)
                    acc)))]
    (is (= [["t0" "t1"] ["t2" "t3"] ["t4"]] pages))
    (testing "an invalid cursor"
      (doseq [cursor ["garbage" "" "cHJlc2ExfHByb21wdHMvbGlzdHwy"]]
        (is (= {"code" -32602 "message" "Invalid pagination cursor"} (get (list-page cursor) "error")) (pr-str cursor)))
      (is (= {"code" -32602 "message" "Cursor must be a string"} (get (list-page 5) "error"))))))

(deftest caching-hints
  (testing "2026-07-28 list, read and discover results carry ttlMs and cacheScope"
    (doseq [[method params] [["server/discover"] ["resources/list"] ["resources/templates/list"]
                             ["prompts/list"] ["resources/read" {"uri" "file:///readme.txt"}]]]
      (is (= {"ttlMs" 0 "cacheScope" "private"} (select-keys (result method params) ["ttlMs" "cacheScope"]))
          method))
    (is (not (contains? (result "prompts/get" {"name" "chat"}) "ttlMs"))))
  (testing "configured hints"
    (let [cached (server/server {:name "t" :version "1" :cache {:ttl-ms 60000 :scope "public"}
                                 :tools [{:name "x" :handler (fn [_ _] "x")}]})]
      (is (= {"ttlMs" 60000 "cacheScope" "public"}
             (select-keys (get (second (server/handle cached {} (rpc/request 1 "tools/list" {"_meta" meta-2026}))) "result")
                          ["ttlMs" "cacheScope"])))))
  (testing "initialize-era results carry none"
    (let [[session] (server/handle srv {} (rpc/request 0 "initialize" {"protocolVersion" "2025-11-25" "capabilities" {}
                                                                       "clientInfo" {"name" "c" "version" "0"}}))
          r (get (second (server/handle srv session (rpc/request 1 "resources/list"))) "result")]
      (is (= 3 (count (get r "resources"))))
      (is (not-any? #(contains? r %) ["ttlMs" "cacheScope" "resultType"])))))

(deftest handlers-can-throw-protocol-errors
  (let [s (server/server {:name "t" :version "1"
                          :tools [{:name "strict"
                                   :handler (fn [_ _] (throw (server/error :invalid-params "bad input" {"field" "x"})))}]})]
    (is (= {"code" -32602 "message" "bad input" "data" {"field" "x"}}
           (get (second (server/handle s {} (rpc/request 1 "tools/call" {"name" "strict" "_meta" meta-2026}))) "error")))))

(deftest definitions-are-checked
  (doseq [opts [{:resources [{:uri "a" :handler identity}]}
                {:resources [{:uri "a" :name "a"}]}
                {:resources [{:uri "a" :name "a" :handler identity} {:uri "a" :name "b" :handler identity}]}
                {:resource-templates [{:uri-template "x://{a" :name "a" :handler identity}]}
                {:prompts [{:name "p" :handler identity :complete {"a" "not a fn"}}]}
                {:prompts [{:name "p" :handler identity :arguments [{:description "no name"}]}]}
                {:page-size 0}
                {:cache {:scope "shared"}}]]
    (is (= :invalid-input (:kind (ex-data (try (server/server (merge {:name "t" :version "1"} opts))
                                               (catch Exception e e)))))
        (pr-str opts))))

(deftest logging
  (let [sent (atom [])
        logger (server/server {:name "t" :version "1" :logging true :versions protocol/versions
                               :tools [{:name "work"
                                        :handler (fn [_ ctx]
                                                   (server/log! ctx "debug" "detail")
                                                   (server/log! ctx "warning" {"rows" 3} "importer")
                                                   "done")}]})
        run (fn [s session message]
              (reset! sent [])
              (let [[session' response] (server/handle s session message {:notify #(swap! sent conj %)})]
                [session' response @sent]))
        call (fn [meta] (rpc/request 1 "tools/call" {"name" "work" "_meta" meta}))]
    (testing "the capability is advertised"
      (is (= {"tools" {} "logging" {}}
             (get-in (second (run logger {} (rpc/request 1 "server/discover" {"_meta" meta-2026}))) ["result" "capabilities"]))))
    (testing "2026-07-28: no level, no messages"
      (is (= [] (nth (run logger {} (call meta-2026)) 2))))
    (testing "2026-07-28: messages at or above the request's level"
      (is (= [{"jsonrpc" "2.0" "method" "notifications/message"
               "params" {"level" "warning" "data" {"rows" 3} "logger" "importer"}}]
             (nth (run logger {} (call (assoc meta-2026 "io.modelcontextprotocol/logLevel" "info"))) 2)))
      (is (= 2 (count (nth (run logger {} (call (assoc meta-2026 "io.modelcontextprotocol/logLevel" "debug"))) 2)))))
    (testing "initialize-era: the server's level until logging/setLevel"
      (let [[session] (run logger {} (rpc/request 0 "initialize" {"protocolVersion" "2025-06-18" "capabilities" {}
                                                                  "clientInfo" {"name" "c" "version" "0"}}))
            plain (rpc/request 1 "tools/call" {"name" "work"})]
        (is (= ["warning"] (map #(get-in % ["params" "level"]) (nth (run logger session plain) 2))))
        (let [[session' response] (run logger session (rpc/request 2 "logging/setLevel" {"level" "debug"}))]
          (is (= {} (get response "result")))
          (is (= ["debug" "warning"] (map #(get-in % ["params" "level"]) (nth (run logger session' plain) 2)))))
        (is (= -32602 (get-in (second (run logger session (rpc/request 2 "logging/setLevel" {"level" "loud"})))
                              ["error" "code"])))))
    (testing "without :logging, log! sends nothing and setLevel is not found"
      (let [quiet (server/server {:name "t" :version "1" :versions protocol/versions
                                  :tools [{:name "work" :handler (fn [_ ctx] (str (server/log! ctx "error" "x")))}]})
            [_ response msgs] (run quiet {} (call (assoc meta-2026 "io.modelcontextprotocol/logLevel" "debug")))]
        (is (= [] msgs))
        (is (= "false" (get-in response ["result" "content" 0 "text"])))
        (let [[session] (run quiet {} (rpc/request 0 "initialize" {"protocolVersion" "2025-06-18" "capabilities" {}
                                                                   "clientInfo" {"name" "c" "version" "0"}}))]
          (is (= -32601 (get-in (second (run quiet session (rpc/request 2 "logging/setLevel" {"level" "debug"})))
                                ["error" "code"]))))))))

(deftest instrumentation
  (let [events (atom [])
        srv (server/server {:name "t" :version "1"
                            :on-event #(swap! events conj (dissoc % :duration-ms :exception))
                            :tools [{:name "ok" :handler (fn [_ _] "ok")}
                                    {:name "boom" :handler (fn [_ _] (throw (ex-info "kaboom" {})))}]})
        run (fn [m] (server/handle srv {} m))]
    (run (rpc/request 1 "tools/call" {"name" "ok" "_meta" meta-2026}))
    (run (rpc/request 2 "tools/call" {"name" "boom" "_meta" meta-2026}))
    (run (rpc/request 3 "tools/call" {"name" "nope" "_meta" meta-2026}))
    (run (rpc/request 4 "tools/list" {}))
    (is (= [{:event :request/start :method "tools/call" :request-id 1 :version "2026-07-28"}
            {:event :request/stop :method "tools/call" :request-id 1 :outcome :ok}
            {:event :request/start :method "tools/call" :request-id 2 :version "2026-07-28"}
            {:event :tool/error :tool "boom" :request-id 2}
            {:event :request/stop :method "tools/call" :request-id 2 :outcome :ok}
            {:event :request/start :method "tools/call" :request-id 3 :version "2026-07-28"}
            {:event :request/stop :method "tools/call" :request-id 3 :outcome :error :error-code -32602}
            {:event :request/rejected :method "tools/list" :request-id 4 :error-code -32602}]
           @events))
    (testing "a failing on-event function never fails the request"
      (let [srv (server/server {:name "t" :version "1" :on-event (fn [_] (throw (ex-info "x" {})))
                                :tools [{:name "ok" :handler (fn [_ _] "ok")}]})]
        (is (= "ok" (get-in (second (server/handle srv {} (rpc/request 1 "tools/call" {"name" "ok" "_meta" meta-2026})))
                            ["result" "content" 0 "text"])))))))
