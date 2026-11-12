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

(deftest stdio-line-limit-counts-bytes
  (let [srv (server/server {:name "t" :version "1" :tools [{:name "x" :handler (fn [_ _] "x")}]})
        ok (str/trim-newline (rpc/write-line (rpc/request 1 "tools/list" {"_meta" meta-2026})))
        wide (str/trim-newline (rpc/write-line (rpc/request 2 "tools/call" {"name" "x" "arguments" {"pad" (apply str (repeat 100 "\u00e9"))}
                                                                             "_meta" meta-2026})))
        chars (count wide)
        bytes (alength (.getBytes ^String wide "UTF-8"))
        limit (+ chars 20)
        out (java.io.StringWriter.)]
    (is (< (inc chars) limit) "under the limit by characters")
    (is (> (inc bytes) limit) "over the limit by UTF-8 bytes")
    (is (< (inc (count ok)) limit))
    (stdio/serve! srv {:in (java.io.StringReader. (str wide "\n" ok "\n")) :out out :log (fn [& _]) :max-line-bytes limit})
    (let [[refused listed] (map rpc/parse-line (str/split-lines (str out)))]
      (is (= -32600 (get-in refused ["error" "code"])))
      (is (= (str "Message exceeds the " limit "-byte line limit") (get-in refused ["error" "message"])))
      (is (= 1 (get listed "id")) "the next line is still read and answered"))))

(defn- post-stream
  "POST an InputStream of bytes with no Content-Length (a chunked body)."
  [url ^bytes body-bytes]
  (let [resp (.send (HttpClient/newHttpClient)
                    (-> (HttpRequest/newBuilder (URI. url))
                        (.header "content-type" "application/json")
                        (.header "accept" "application/json, text/event-stream")
                        (.header "mcp-protocol-version" "2026-07-28")
                        (.header "mcp-method" "tools/list")
                        (.POST (HttpRequest$BodyPublishers/ofInputStream
                                (reify java.util.function.Supplier
                                  (get [_] (java.io.ByteArrayInputStream. body-bytes)))))
                        (.build))
                    (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)}))

(deftest http-body-edge-cases
  (let [srv (server/server {:name "t" :version "1" :tools [{:name "x" :handler (fn [_ _] "x")}]})
        {:keys [url stop!]} (http/serve! srv {:max-body-bytes 400})]
    (try
      (let [small (json/write-str (rpc/request 1 "tools/list" {"_meta" meta-2026}))
            big (json/write-str (rpc/request 1 "tools/list" {"_meta" (assoc meta-2026 "pad" (apply str (repeat 500 "a")))}))
            utf8 #(.getBytes ^String % "UTF-8")
            bom (byte-array [0xEF 0xBB 0xBF])]
        (testing "a chunked body past :max-body-bytes is 413"
          (is (= 200 (:status (post-stream url (utf8 small)))) "a small chunked body is fine")
          (let [r (post-stream url (utf8 big))]
            (is (= 413 (:status r)))
            (is (= -32600 (get-in (json/read-str (:body r)) ["error" "code"])))))
        (testing "a leading UTF-8 byte order mark is accepted"
          (let [r (post-stream url (byte-array (concat bom (utf8 small))))]
            (is (= 200 (:status r)))
            (is (= "x" (get-in (json/read-str (:body r)) ["result" "tools" 0 "name"]))))))
      (finally (stop!)))))

(deftest per-request-caps-are-configurable
  (let [counting {:name "count"
                  :handler (fn [_ ctx]
                             (str (count (filter true? (map #(server/progress! ctx %) (range 1 1101))))
                                  " "
                                  (count (filter true? (map (fn [_] (server/log! ctx "info" "x")) (range 1100))))))}
        run (fn [opts]
              (let [srv (server/server (merge {:name "t" :version "1" :logging true :tools [counting]} opts))]
                (get-in (second (server/handle srv {} (rpc/request 1 "tools/call"
                                                                   {"name" "count"
                                                                    "_meta" {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                                                                             "io.modelcontextprotocol/clientCapabilities" {}
                                                                             "io.modelcontextprotocol/logLevel" "info"
                                                                             "progressToken" "p"}})))
                        ["result" "content" 0 "text"])))]
    (is (= "1000 1000" (run {})) "the defaults")
    (is (= "2 3" (run {:max-progress-updates 2 :max-log-messages 3})))
    (is (= "1100 1100" (run {:max-progress-updates nil :max-log-messages nil})) "nil is no cap")))
