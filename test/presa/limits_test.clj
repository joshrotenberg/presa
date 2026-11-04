(ns presa.limits-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.jsonrpc :as rpc]
            [presa.server :as server]
            [presa.transport.http :as http]
            [presa.transport.stdio :as stdio])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}})

(deftest stdio-line-limit
  (let [srv (server/server {:name "t" :version "1" :tools [{:name "x" :handler (fn [_ _] "x")}]})
        ok (str/trim-newline (rpc/write-line (rpc/request 1 "tools/list" {"_meta" meta-2026})))
        huge (str/trim-newline (rpc/write-line (rpc/request 2 "tools/call" {"name" "x" "arguments" {"pad" (apply str (repeat 500 "a"))}
                                                                             "_meta" meta-2026})))
        out (java.io.StringWriter.)]
    (stdio/serve! srv {:in (java.io.StringReader. (str huge "\n" ok "\n")) :out out :log (fn [& _]) :max-line-bytes 300})
    (let [[refused listed] (map rpc/parse-line (str/split-lines (str out)))]
      (is (= {"jsonrpc" "2.0" "id" nil "error" {"code" -32600 "message" "Message exceeds the 300-byte line limit"}} refused))
      (is (= 1 (get listed "id")) "the next line is read normally"))))

(defn- post [url body]
  (let [resp (.send (HttpClient/newHttpClient)
                    (-> (HttpRequest/newBuilder (URI. url))
                        (.header "content-type" "application/json")
                        (.header "accept" "application/json, text/event-stream")
                        (.header "mcp-protocol-version" "2026-07-28")
                        (.header "mcp-method" "tools/list")
                        (.POST (HttpRequest$BodyPublishers/ofString body))
                        (.build))
                    (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)}))

(deftest http-body-limit
  (let [srv (server/server {:name "t" :version "1" :tools [{:name "x" :handler (fn [_ _] "x")}]})
        {:keys [url stop!]} (http/serve! srv {:max-body-bytes 400})]
    (try
      (let [small (json/write-str (rpc/request 1 "tools/list" {"_meta" meta-2026}))
            big (json/write-str (rpc/request 1 "tools/list" {"_meta" (assoc meta-2026 "pad" (apply str (repeat 500 "a")))}))]
        (is (= 200 (:status (post url small))))
        (let [r (post url big)]
          (is (= 413 (:status r)))
          (is (= -32600 (get-in (json/read-str (:body r)) ["error" "code"])))))
      (finally (stop!)))))

(deftest subscription-cap
  (let [srv (server/server {:name "t" :version "1" :list-changed true :max-subscriptions 1
                            :tools [{:name "x" :handler (fn [_ _] "x")}]})
        {:keys [url stop!]} (http/serve! srv)
        c (client/connect (http/url url))]
    (try
      (let [first-sub (client/listen c {"toolsListChanged" true} (fn [_]))]
        (is (some? (deref (:acknowledged first-sub) 2000 nil)))
        (let [second-sub (client/listen c {"toolsListChanged" true} (fn [_]))
              done (deref (:done second-sub) 2000 nil)]
          (is (= -32603 (:code (ex-data done))))
          (is (= {"capacity" "subscriptions"} (:data (ex-data done)))))
        ((:stop! first-sub)))
      (testing "HTTP sends the refusal as 503"
        (let [srv (server/server {:name "t" :version "1" :list-changed true :max-subscriptions 0
                                  :tools [{:name "x" :handler (fn [_ _] "x")}]})
              {:keys [url stop!]} (http/serve! srv)]
          (try
            (let [resp (.send (HttpClient/newHttpClient)
                              (-> (HttpRequest/newBuilder (URI. url))
                                  (.header "content-type" "application/json")
                                  (.header "accept" "application/json, text/event-stream")
                                  (.header "mcp-protocol-version" "2026-07-28")
                                  (.header "mcp-method" "subscriptions/listen")
                                  (.POST (HttpRequest$BodyPublishers/ofString
                                          (json/write-str (rpc/request 1 "subscriptions/listen"
                                                                       {"notifications" {"toolsListChanged" true}
                                                                        "_meta" meta-2026}))))
                                  (.build))
                              (HttpResponse$BodyHandlers/ofString))]
              (is (= 503 (.statusCode resp))))
            (finally (stop!)))))
      (finally (client/close c) (stop!)))))

(deftest per-request-notification-limits
  (let [results (atom {})
        srv (server/server
             {:name "t" :version "1" :logging true
              :tools [{:name "noisy"
                       :handler (fn [_ ctx]
                                  (swap! results assoc
                                         :increasing [(server/progress! ctx 1) (server/progress! ctx 1) (server/progress! ctx 0.5)
                                                      (server/progress! ctx 2)]
                                         :long-message (server/progress! ctx 3 {:message (apply str (repeat 5000 "m"))})
                                         :many (count (filter true? (for [i (range 4 (+ 4 server/max-progress-updates))]
                                                                      (server/progress! ctx i))))
                                         :big-log (server/log! ctx "info" (apply str (repeat 20000 "d")))
                                         :logs (count (filter true? (repeatedly (+ 5 server/max-log-messages)
                                                                                #(server/log! ctx "info" "x")))))
                                  "done")}]})
        sent (atom 0)]
    (server/handle srv {} (rpc/request 1 "tools/call" {"name" "noisy"
                                                       "_meta" (assoc meta-2026 "progressToken" "p"
                                                                      "io.modelcontextprotocol/logLevel" "debug")})
                   {:notify (fn [_] (swap! sent inc))})
    (is (= [true false false true] (:increasing @results)) "progress must increase")
    (is (false? (:long-message @results)))
    (is (= (- server/max-progress-updates 2) (:many @results)) "two updates were already sent")
    (is (false? (:big-log @results)))
    (is (= server/max-log-messages (:logs @results)))
    (is (= (+ server/max-progress-updates server/max-log-messages) @sent))))
