(ns presa.transport.http-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.input :as input]
            [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.http :as http]
            [org.httpkit.server :as hk])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def srv
  (server/server
   {:name "t" :version "1" :versions protocol/versions :logging true
    :tools [{:name "echo" :handler (fn [{:strs [text]} _] text)}
            {:name "slow"
             :handler (fn [_ ctx]
                        (server/progress! ctx 1 {:total 2})
                        (server/log! ctx "info" "halfway")
                        (server/progress! ctx 2 {:total 2})
                        "slow done")}
            {:name "ask"
             :handler (fn [_ ctx]
                        (if-let [r (server/input-response ctx "n")]
                          (str "hi " (get-in r ["content" "name"]))
                          (server/input-required {"n" (input/elicit "name?" {"type" "object"})} {"k" 1})))}
            {:name "héllo wörld" :handler (fn [_ _] "unicode name")}
            {:name "region"
             :input-schema {"type" "object" "properties" {"region" {"type" "string" "x-mcp-header" "Region"}}}
             :handler (fn [{:strs [region]} _] (str "region " region))}]
    :resources [{:uri "x:/a" :name "a" :handler (fn [_] "A")}]}))

(defmacro with-server [[binding opts] & body]
  `(let [~binding (http/serve! srv ~opts)]
     (try ~@body (finally ((:stop! ~binding))))))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {"elicitation" {}}})

(defn- post
  "POST body (a map, or a raw string) with headers; {:status :headers :body}."
  ([url body] (post url body {}))
  ([url body headers]
   (let [method (when (map? body) (get body "method"))
         defaults (cond-> {"content-type" "application/json"
                           "accept" "application/json, text/event-stream"}
                    (and (map? body) (get-in body ["params" "_meta"]))
                    (assoc "mcp-protocol-version" (get-in body ["params" "_meta" "io.modelcontextprotocol/protocolVersion"]))
                    method (assoc "mcp-method" method)
                    (#{"tools/call" "prompts/get"} method) (assoc "mcp-name" (http/encode-header-value (get-in body ["params" "name"])))
                    (= "resources/read" method) (assoc "mcp-name" (get-in body ["params" "uri"])))
         builder (HttpRequest/newBuilder (URI. url))]
     (doseq [[k v] (merge defaults headers) :when v] (.header builder k v))
     (.POST builder (HttpRequest$BodyPublishers/ofString (if (string? body) body (json/write-str body))))
     (let [resp (.send (HttpClient/newHttpClient) (.build builder) (HttpResponse$BodyHandlers/ofString))]
       {:status (.statusCode resp)
        :content-type (.orElse (.firstValue (.headers resp) "content-type") nil)
        :session-id (.orElse (.firstValue (.headers resp) "mcp-session-id") nil)
        :body (.body resp)}))))

(defn- json-body [r] (json/read-str (:body r)))

(defn- request [id method params]
  {"jsonrpc" "2.0" "id" id "method" method "params" (update params "_meta" #(merge meta-2026 %))})

(deftest stateless-requests-over-http
  (with-server [s {}]
    (let [url (:url s)]
      (testing "a plain JSON response"
        (let [r (post url (request 1 "tools/call" {"name" "echo" "arguments" {"text" "hi"}}))]
          (is (= 200 (:status r)))
          (is (str/starts-with? (:content-type r) "application/json"))
          (is (= "hi" (get-in (json-body r) ["result" "content" 0 "text"])))
          (is (nil? (:session-id r)))))
      (testing "progress and log messages turn the response into an event stream"
        (let [r (post url (request 2 "tools/call" {"name" "slow" "_meta" (assoc meta-2026 "progressToken" "p"
                                                                                "io.modelcontextprotocol/logLevel" "info")}))
              events (->> (str/split-lines (:body r))
                          (filter #(str/starts-with? % "data: "))
                          (map #(json/read-str (subs % 6))))]
          (is (str/starts-with? (:content-type r) "text/event-stream"))
          (is (= ["notifications/progress" "notifications/message" "notifications/progress" nil]
                 (map #(get % "method") events)))
          (is (= "slow done" (get-in (last events) ["result" "content" 0 "text"])))))
      (testing "statuses for errors"
        (let [r (post url (request 3 "ping" {}))]
          (is (= 404 (:status r)))
          (is (= {"jsonrpc" "2.0" "id" 3 "error" {"code" -32601 "message" "Method not found: ping"}} (json-body r))))
        (is (= 400 (:status (post url {"jsonrpc" "2.0" "id" 4 "method" "tools/list" "params" {}}
                                  {"mcp-protocol-version" "2026-07-28"}))))
        (is (= 400 (:status (post url (request 5 "resources/read" {"uri" "x:/missing"})))))
        (let [r (post url (assoc-in (request 6 "tools/list" {}) ["params" "_meta" "io.modelcontextprotocol/protocolVersion"] "v999.0.0"))]
          (is (= 400 (:status r)))
          (is (= -32022 (get-in (json-body r) ["error" "code"])))
          (is (= "v999.0.0" (get-in (json-body r) ["error" "data" "requested"])))))
      (testing "header checks"
        (let [body (assoc-in (request 7 "tools/list" {}) ["params" "_meta" "io.modelcontextprotocol/protocolVersion"] "v999.0.0")
              r (post url body {"mcp-protocol-version" "2026-07-28"})]
          (is (= 400 (:status r)))
          (is (= {"code" -32020 "message" "Protocol header does not match request metadata"
                  "data" {"header" "2026-07-28" "body" "v999.0.0"}}
                 (get (json-body r) "error"))))
        (doseq [[label headers] [["no version header" {"mcp-protocol-version" nil}]
                                 ["no method header" {"mcp-method" nil}]
                                 ["a method header in other case" {"mcp-method" "TOOLS/CALL"}]
                                 ["no name header" {"mcp-name" nil}]
                                 ["another name" {"mcp-name" "other"}]]]
          (let [r (post url (request 8 "tools/call" {"name" "echo" "arguments" {"text" "x"}}) headers)]
            (is (= 400 (:status r)) label)
            (is (= -32020 (get-in (json-body r) ["error" "code"])) label)))
        (testing "a padded or base64 name is accepted"
          (is (= 200 (:status (post url (request 9 "tools/call" {"name" "echo" "arguments" {"text" "x"}})
                                    {"mcp-name" "  echo  "}))))
          (is (= "unicode name"
                 (get-in (json-body (post url (request 10 "tools/call" {"name" "héllo wörld"})))
                         ["result" "content" 0 "text"]))))))))

(deftest admission
  (with-server [s {}]
    (let [url (:url s)
          body (request 1 "tools/list" {})
          send-raw (fn [method]
                     (.statusCode (.send (HttpClient/newHttpClient)
                                         (-> (HttpRequest/newBuilder (URI. url)) (.method method (HttpRequest$BodyPublishers/noBody)) (.build))
                                         (HttpResponse$BodyHandlers/ofString))))]
      (is (= 405 (send-raw "GET")))
      (is (= 404 (:status (post (str/replace url "/mcp" "/other") body))))
      (is (= 415 (:status (post url body {"content-type" "text/plain"}))))
      (is (= 406 (:status (post url body {"accept" "application/json"}))))
      (let [r (post url "{not json")]
        (is (= 400 (:status r)))
        (is (= -32700 (get-in (json-body r) ["error" "code"]))))
      (is (= 202 (:status (post url {"jsonrpc" "2.0" "method" "notifications/cancelled" "params" {"requestId" 1}}))))
      (is (= 202 (:status (post url {"jsonrpc" "2.0" "id" 1 "result" {}}))))
      (testing "DNS rebinding: foreign origins are refused, loopback ones and none are not"
        (is (= 403 (:status (post url body {"origin" "http://evil.example.com"}))))
        (is (= 200 (:status (post url body {"origin" (str "http://localhost:" (:port s))}))))
        (is (= 200 (:status (post url body {"origin" (str "http://127.0.0.1:" (:port s))}))))))
    (testing "allowed origins and hosts"
      (with-server [s {:allowed-origins #{"https://app.example.com"} :allowed-hosts #(str/starts-with? % "127.0.0.1:")}]
        (is (= 200 (:status (post (:url s) (request 1 "tools/list" {}) {"origin" "https://app.example.com"})))))
      (with-server [s {:allowed-hosts #{"good.example.com"}}]
        (is (= 403 (:status (post (:url s) (request 1 "tools/list" {})))) "the Host does not match")))))

(deftest initialize-era-sessions-over-http
  (with-server [s {}]
    (let [url (:url s)
          init {"jsonrpc" "2.0" "id" 0 "method" "initialize"
                "params" {"protocolVersion" "2025-06-18" "capabilities" {} "clientInfo" {"name" "c" "version" "0"}}}
          r (post url init)
          sid (:session-id r)]
      (is (= 200 (:status r)))
      (is (string? sid))
      (is (= "2025-06-18" (get-in (json-body r) ["result" "protocolVersion"])))
      (let [with-session {"mcp-session-id" sid "mcp-protocol-version" "2025-06-18"}]
        (is (= 202 (:status (post url {"jsonrpc" "2.0" "method" "notifications/initialized"} with-session))))
        (let [r (post url {"jsonrpc" "2.0" "id" 1 "method" "tools/list"} with-session)]
          (is (= 200 (:status r)))
          (is (= 5 (count (get-in (json-body r) ["result" "tools"]))))
          (is (not (contains? (get (json-body r) "result") "resultType"))))
        (testing "errors are 200 for these clients"
          (is (= 200 (:status (post url {"jsonrpc" "2.0" "id" 2 "method" "nope"} with-session)))))
        (is (= 400 (:status (post url {"jsonrpc" "2.0" "id" 3 "method" "tools/list"}
                                  (assoc with-session "mcp-protocol-version" "2025-11-25")))))
        (is (= 404 (:status (post url {"jsonrpc" "2.0" "id" 4 "method" "tools/list"} {"mcp-session-id" "unknown"}))))))))

(defn- initialize!
  "Open an initialize-era session; the headers for requests on it."
  [url]
  (let [r (post url {"jsonrpc" "2.0" "id" 0 "method" "initialize"
                     "params" {"protocolVersion" "2025-06-18" "capabilities" {} "clientInfo" {"name" "c" "version" "0"}}})]
    (when (= 200 (:status r))
      {"mcp-session-id" (:session-id r) "mcp-protocol-version" "2025-06-18"})))

(defn- list-status [url session]
  (:status (post url {"jsonrpc" "2.0" "id" 1 "method" "tools/list"} session)))

(deftest idle-sessions-are-closed
  (testing "an idle session is closed when the next initialize arrives"
    (with-server [s {:session-idle-ms 100}]
      (let [url (:url s)
            a (initialize! url)]
        (is (= 200 (list-status url a)))
        (Thread/sleep 400)
        (let [b (initialize! url)]
          (is (= 404 (list-status url a)) "the idle session is gone")
          (is (= 200 (list-status url b)))))))
  (testing "a session with a request in flight is kept"
    (let [release (promise)
          started (promise)
          s (http/serve! (server/server {:name "t" :version "1" :versions protocol/versions
                                         :tools [{:name "wait" :handler (fn [_ _] (deliver started true) @release "done")}]})
                         {:session-idle-ms 100})]
      (try
        (let [url (:url s)
              a (initialize! url)
              call (future (post url {"jsonrpc" "2.0" "id" 2 "method" "tools/call" "params" {"name" "wait"}}
                                 (assoc a "mcp-name" "wait")))]
          (is (true? (deref started 5000 false)))
          (Thread/sleep 200)
          (initialize! url)
          (deliver release true)
          (is (= 200 (:status (deref call 5000 nil))))
          (is (= 200 (list-status url a)) "the busy session survived the sweep"))
        (finally ((:stop! s)))))))

(deftest sessions-are-capped
  (with-server [s {:max-sessions 2}]
    (let [url (:url s)
          a (initialize! url)]
      (is (some? (initialize! url)))
      (let [r (post url {"jsonrpc" "2.0" "id" 0 "method" "initialize"
                         "params" {"protocolVersion" "2025-06-18" "capabilities" {} "clientInfo" {"name" "c" "version" "0"}}})]
        (is (= 503 (:status r)))
        (is (= "Too many sessions" (get-in (json-body r) ["error" "message"]))))
      (testing "closing a session makes room"
        (let [builder (doto (HttpRequest/newBuilder (URI. url))
                        (.header "mcp-session-id" (get a "mcp-session-id"))
                        (.DELETE))]
          (is (= 204 (.statusCode (.send (HttpClient/newHttpClient) (.build builder) (HttpResponse$BodyHandlers/ofString))))))
        (is (some? (initialize! url)))))))

(deftest clients-over-http
  (with-server [s {}]
    (doseq [versions [[protocol/latest-version] ["2025-11-25"]]]
      (testing (str "over " versions)
        (let [logs (atom [])
              c (client/connect (http/url (:url s))
                                {:versions versions :log-level "info" :on-log #(swap! logs conj %)
                                 :handlers {"elicitation/create" (fn [_] {"action" "accept" "content" {"name" "Ada"}})}})]
          (try
            (is (= (first versions) (:version (client/info c))))
            (is (= "hi" (client/text (client/call-tool c "echo" {"text" "hi"}))))
            (is (= "hi Ada" (client/text (client/call-tool c "ask"))))
            (is (= "unicode name" (client/text (client/call-tool c "héllo wörld"))))
            (is (= [{"uri" "x:/a" "text" "A"}] (client/read-resource c "x:/a")))
            (let [progress (atom [])]
              (is (= "slow done" (client/text (client/call-tool c "slow" {} {:on-progress #(swap! progress conj %)}))))
              (is (= [1 2] (map #(get % "progress") @progress))))
            (when (= [protocol/latest-version] versions)
              (is (= [{"level" "info" "data" "halfway"}] @logs))
              (testing "x-mcp-header arguments travel as Mcp-Param headers"
                (is (= "region eu west" (client/text (client/call-tool c "region" {"region" "eu west"}))))))
            (finally (client/close c))))))))

(defn- future-threads
  "Live threads of the pool futures run on (clojure-agent-send-off-pool on
  the JVM, CLI-agent-send-off-pool in Babashka)."
  []
  (count (filter #(str/includes? (.getName ^Thread %) "agent-send-off-pool")
                 (keys (Thread/getAllStackTraces)))))

(deftest requests-release-their-threads
  (with-server [s {}]
    (let [c (client/connect (http/url (:url s)))]
      (try
        (client/call-tool c "echo" {"text" "warm"})
        (let [before (future-threads)]
          (dotimes [i 50] (client/call-tool c "echo" {"text" (str i)}))
          (is (< (- (future-threads) before) 10) "50 requests leave no thread behind"))
        (finally (client/close c))))))

(deftest a-client-that-goes-away-cancels-its-request
  (let [started (promise)
        interrupted (promise)
        s (http/serve! (server/server {:name "t" :version "1"
                                       :tools [{:name "wait"
                                                :handler (fn [_ _]
                                                           (deliver started true)
                                                           (try (Thread/sleep 10000)
                                                                (catch InterruptedException _ (deliver interrupted true)))
                                                           "late")}]})
                       {})]
    (try
      (let [uri (URI. (:url s))
            body (json/write-str (request 1 "tools/call" {"name" "wait"}))
            sock (java.net.Socket. (.getHost uri) (.getPort uri))]
        (doto (.getOutputStream sock)
          (.write (.getBytes (str "POST " (.getPath uri) " HTTP/1.1\r\n"
                                  "Host: " (.getHost uri) ":" (.getPort uri) "\r\n"
                                  "Content-Type: application/json\r\n"
                                  "Accept: application/json, text/event-stream\r\n"
                                  "Mcp-Protocol-Version: 2026-07-28\r\n"
                                  "Mcp-Method: tools/call\r\nMcp-Name: wait\r\n"
                                  "Content-Length: " (count (.getBytes body "UTF-8")) "\r\n\r\n"
                                  body)
                             "UTF-8"))
          (.flush))
        (is (true? (deref started 5000 false)) "the handler ran")
        (.close sock)
        (is (true? (deref interrupted 5000 false)) "closing the connection interrupted the handler"))
      (finally ((:stop! s))))))

(deftest a-client-renews-a-session-the-server-forgot
  (let [s1 (http/serve! srv {})
        port (:port s1)
        c (client/connect (http/url (:url s1)) {:versions ["2025-11-25"]})
        s2 (atom nil)]
    (try
      (is (= "before" (client/text (client/call-tool c "echo" {"text" "before"}))))
      ;; a restarted server knows no sessions, so the next request gets 404
      ((:stop! s1))
      (reset! s2 (http/serve! srv {:port port}))
      (is (= "after" (client/text (client/call-tool c "echo" {"text" "after"}))) "renewed and retried")
      (is (= "again" (client/text (client/call-tool c "echo" {"text" "again"}))) "the new session holds")
      (finally
        (client/close c)
        (some-> @s2 :stop! (apply []))))))

(deftest every-request-is-answered
  (let [s (http/serve! (server/server {:name "t" :version "1"
                                       :tools [{:name "asserts" :handler (fn [_ _] (assert false "nope"))}
                                               {:name "unwritable" :handler (fn [_ _] {"content" [] "structuredContent" {"at" (Object.)}})}]})
                       {})]
    (try
      (let [r (post (:url s) (request 1 "tools/call" {"name" "asserts"}))]
        (is (= 200 (:status r)))
        (is (true? (get-in (json-body r) ["result" "isError"]))))
      (let [r (post (:url s) (request 2 "tools/call" {"name" "unwritable"}))]
        (is (= 500 (:status r)))
        (is (= 2 (get (json-body r) "id")))
        (is (= -32603 (get-in (json-body r) ["error" "code"]))))
      (finally ((:stop! s))))))

(deftest a-cancelled-request-ends-its-exchange
  ;; tools/call for "stall" is never answered: either no response starts,
  ;; or JSON headers and part of a body go out and the rest never comes.
  ;; A cancelled request closes its connection, which the server sees.
  (doseq [[label stall] [["no response" (fn [_])]
                         ["a stalled JSON body" (fn [ch] (hk/send! ch {:status 200 :headers {"content-type" "application/json"}
                                                                       :body "{\"jsonrpc\":"} false))]]]
    (testing label
      (let [inner (http/handler srv)
            closes (atom 0)
            server (hk/run-server
                    (fn [req]
                      (let [body (slurp (:body req))]
                        (if (str/includes? body "\"stall\"")
                          (hk/as-channel req {:on-open stall :on-close (fn [_ _] (swap! closes inc))})
                          (inner (assoc req :body (java.io.ByteArrayInputStream. (.getBytes body "UTF-8")))))))
                    {:port 0 :ip "127.0.0.1" :legacy-return-value? false})
            c (client/connect (http/url (str "http://127.0.0.1:" (hk/server-port server) "/mcp")))]
        (try
          (dotimes [_ 10]
            (is (thrown? Exception (client/call-tool c "stall" {} {:timeout-ms 300}))))
          (is (loop [n 0] (cond (= 10 @closes) true
                                (> n 50) false
                                :else (do (Thread/sleep 50) (recur (inc n)))))
              (str "each timed-out request closed its connection (" @closes " of 10)"))
          (finally (client/close c) @(hk/server-stop! server {:timeout 100})))))))

(deftest header-values
  (is (= "plain value" (http/encode-header-value "plain value")))
  (is (= "true" (http/encode-header-value true)))
  (is (= "42" (http/encode-header-value 42)))
  (is (= "=?base64?IHBhZGRlZA==?=" (http/encode-header-value " padded")))
  (is (= "héllo" (http/decode-header-value (http/encode-header-value "héllo"))))
  (is (nil? (http/decode-header-value "=?base64?//79?=")))
  (testing "x-mcp-header annotations"
    (is (= [["Region" "region" "string"]]
           (http/param-headers {"properties" {"region" {"type" "string" "x-mcp-header" "Region"} "q" {"type" "string"}}})))
    (doseq [bad [{"properties" {"a" {"type" "number" "x-mcp-header" "A"}}}
                 {"properties" {"a" {"type" "string" "x-mcp-header" "Has Space"}}}
                 {"properties" {"a" {"type" "string" "x-mcp-header" ""}}}
                 {"properties" {"a" {"type" "string" "x-mcp-header" "X"} "b" {"type" "string" "x-mcp-header" "x"}}}]]
      (is (= :presa.transport.http/invalid (http/param-headers bad)) (pr-str bad)))))

(deftest a-request-after-initialized-waits-for-the-session-stream
  ;; A slow notifications/initialized: before the fix, a request sent right
  ;; after it was posted while it was still in flight, ahead of the session
  ;; GET stream, which then opened at an arbitrary later time.
  (let [order (atom [])
        server (hk/run-server
                (fn [req]
                  (case (:request-method req)
                    :delete {:status 200}
                    :get
                    (do (swap! order conj "GET")
                        (hk/as-channel req {:on-open (fn [ch] (hk/send! ch {:status 200 :headers {"content-type" "text/event-stream"}} false))}))
                    (let [{:strs [id method]} (json/read-str (slurp (:body req)))
                          reply (fn [result] {:status 200 :headers {"content-type" "application/json" "mcp-session-id" "s1"}
                                              :body (json/write-str {"jsonrpc" "2.0" "id" id "result" result})})]
                      (swap! order conj method)
                      (case method
                        "initialize" (reply {"protocolVersion" "2025-11-25" "capabilities" {"tools" {}}
                                             "serverInfo" {"name" "slow" "version" "1"}})
                        "notifications/initialized" (do (Thread/sleep 300) {:status 202})
                        (reply {"tools" []})))))
                {:port 0 :legacy-return-value? false})
        url (str "http://127.0.0.1:" (hk/server-port server) "/mcp")]
    (try
      (let [c (client/connect (http/url url) {:versions ["2025-11-25"]})]
        (try
          (client/list-tools c)
          (is (= ["initialize" "notifications/initialized" "GET" "tools/list"] @order))
          (finally (client/close c))))
      (finally (hk/server-stop! server)))))

(deftest origin-checks
  (let [body (request 1 "tools/list" {})
        status (fn [s origin] (:status (post (:url s) body {"origin" origin})))]
    (testing ":allowed-origins as a predicate"
      (with-server [s {:allowed-origins #(= % "https://app.example.com")}]
        (is (= 200 (status s "https://app.example.com")))
        (is (= 403 (status s "https://other.example.com")))))
    (testing "Origin: null is refused, with and without an allow list"
      (with-server [s {}]
        (is (= 403 (status s "null"))))
      (with-server [s {:allowed-origins #{"https://app.example.com"}}]
        (is (= 403 (status s "null")))))
    (testing "an origin with userinfo is not loopback"
      (with-server [s {}]
        (is (= 403 (status s "http://localhost@evil.example")))
        (is (= 403 (status s "http://127.0.0.1:80@evil.example")))))
    (testing "loopback origins are accepted without configuration"
      (with-server [s {}]
        (is (= 200 (status s "http://localhost")))
        (is (= 200 (status s (str "http://127.0.0.1:" (:port s)))))
        (is (= 200 (status s (str "http://[::1]:" (:port s)))))))))

(deftest own-tools-listing
  ;; Calling a tool it has not seen listed, the transport lists tools first
  ;; to learn their x-mcp-header annotations, with a request id of its own.
  (let [events (atom [])
        s (http/serve! (server/server {:name "t" :version "1" :versions protocol/versions
                                       :on-event #(swap! events conj %)
                                       :tools [{:name "region"
                                                :input-schema {"type" "object"
                                                               "properties" {"region" {"type" "string"
                                                                                       "x-mcp-header" "Region"}}}
                                                :handler (fn [{:strs [region]} _] (str "region " region))}]}))]
    (try
      (doseq [versions [[protocol/latest-version] ["2025-11-25"]]]
        (testing (str "over " versions)
          (reset! events [])
          (let [c (client/connect (http/url (:url s)) {:versions versions})]
            (try
              (is (= "region eu" (client/text (client/call-tool c "region" {"region" "eu"}))))
              (let [listing (filter #(= "tools/list" (:method %)) @events)]
                (is (seq (filter #(= :request/start (:event %)) listing)))
                (is (empty? (filter #(= :request/rejected (:event %)) listing)))
                (is (every? #(re-matches #"presa-tools-\d+-\d+" (str (:request-id %))) listing)))
              (finally (client/close c))))))
      (finally ((:stop! s))))))

(defn- raw-status
  "The status line's code for a POST with the given Host header, sent over a
  plain socket (java.net.http will not set Host)."
  [url host]
  (let [uri (URI. url)
        body (json/write-str (request 1 "tools/list" {}))]
    (with-open [sock (java.net.Socket. (.getHost uri) (.getPort uri))]
      (doto (.getOutputStream sock)
        (.write (.getBytes (str "POST " (.getPath uri) " HTTP/1.1\r\nHost: " host "\r\n"
                                "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                                "Mcp-Protocol-Version: 2026-07-28\r\nMcp-Method: tools/list\r\n"
                                "Connection: close\r\nContent-Length: " (count (.getBytes body "UTF-8")) "\r\n\r\n" body)
                           "UTF-8"))
        (.flush))
      (parse-long (second (str/split (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream sock)))) #" "))))))

(deftest a-loopback-server-checks-host
  (with-server [s {}]
    (is (= 403 (raw-status (:url s) "evil.example.com")) "a rebinding host is refused")
    (is (= 200 (raw-status (:url s) (str "localhost:" (:port s)))))
    (is (= 200 (raw-status (:url s) (str "127.0.0.1:" (:port s))))))
  (with-server [s {:allowed-hosts #{"evil.example.com"}}]
    (is (= 200 (raw-status (:url s) "evil.example.com")) ":allowed-hosts replaces the default")))

(deftest a-deleted-session-fails-requests-waiting-on-the-client
  (let [asked (promise)
        failed (promise)
        s (http/serve! (server/server {:name "t" :version "1" :versions protocol/versions
                                       :tools [{:name "ask"
                                                :handler (fn [_ ctx]
                                                           (deliver asked true)
                                                           (try (server/client-request! ctx "elicitation/create"
                                                                                        {"mode" "form" "message" "who?"
                                                                                         "requestedSchema" {"type" "object"}})
                                                                (catch Exception e (deliver failed (:kind (ex-data e)))))
                                                           "done")}]})
                       {})]
    (try
      (let [init (post (:url s) {"jsonrpc" "2.0" "id" 0 "method" "initialize"
                                 "params" {"protocolVersion" "2025-06-18" "capabilities" {"elicitation" {}}
                                           "clientInfo" {"name" "c" "version" "0"}}})
            sid (:session-id init)
            headers {"mcp-session-id" sid "mcp-protocol-version" "2025-06-18"}]
        (post (:url s) {"jsonrpc" "2.0" "method" "notifications/initialized"} headers)
        ;; the call waits on the client's answer, which never comes
        (future (post (:url s) {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" "ask"}}
                      (assoc headers "mcp-name" "ask")))
        (is (true? (deref asked 5000 false)))
        (Thread/sleep 200)
        (.send (HttpClient/newHttpClient)
               (.build (doto (HttpRequest/newBuilder (URI. (:url s))) (.header "mcp-session-id" sid) (.DELETE)))
               (HttpResponse$BodyHandlers/discarding))
        (is (= :closed (deref failed 2000 nil)) "the waiting request fails at once"))
      (finally ((:stop! s))))))
