(ns presa.scopes-test
  "The :required-scopes policy for every component kind, on every request
  that reaches a component: lists, use, completion and subscriptions; and
  an initialize-era HTTP session bound to the subject that opened it."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [presa.auth :as auth]
            [presa.auth.jwt :as jwt]
            [presa.jsonrpc :as rpc]
            [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.http :as http])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def meta-2026 {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                "io.modelcontextprotocol/clientCapabilities" {}})

(defn- policy-server []
  (server/server
   {:name "t" :version "1" :subscribe true :versions protocol/versions
    :required-scopes {[:prompt "secret"] ["admin"]
                      [:resource "file:///vault/key"] ["admin"]
                      [:resource-template "file:///hidden/{id}"] ["admin"]}
    :resources [{:uri "file:///vault/key" :name "key" :handler (fn [_] "k")}
                {:uri "file:///open" :name "open" :handler (fn [_] "o")}]
    :resource-templates [{:uri-template "file:///hidden/{id}" :name "hidden"
                          :handler (fn [{:strs [id]} _] id)
                          :complete {"id" (fn [_ _ _] ["leak"])}}]
    :prompts [{:name "secret" :arguments [{:name "a"}] :handler (fn [_ _] "s")
               :complete {"a" (fn [_ _ _] ["leak"])}}
              {:name "public" :arguments [{:name "a"}] :handler (fn [_ _] "p")
               :complete {"a" (fn [_ _ _] ["ok"])}}]}))

(def reader {:subject "u" :scopes []})
(def admin {:subject "root" :scopes ["admin"]})

(defn- call [srv who method params]
  (second (server/handle srv {} (rpc/request 1 method (assoc params "_meta" meta-2026)) {:auth who})))

(defn- code [response] (get-in response ["error" "code"]))

(deftest every-kind-is-filtered-and-refused
  (let [srv (policy-server)]
    (testing "lists leave out what the caller may not use"
      (is (= ["public"] (map #(get % "name") (get-in (call srv reader "prompts/list" {}) ["result" "prompts"]))))
      (is (= ["file:///open"] (map #(get % "uri") (get-in (call srv reader "resources/list" {}) ["result" "resources"]))))
      (is (= [] (get-in (call srv reader "resources/templates/list" {}) ["result" "resourceTemplates"])))
      (is (= 2 (count (get-in (call srv admin "prompts/list" {}) ["result" "prompts"])))))
    (testing "use is -32003 with the scopes it needs"
      (is (= -32003 (code (call srv reader "prompts/get" {"name" "secret"}))))
      (is (= {"error" "insufficient_scope" "scope" "admin"}
             (get-in (call srv reader "resources/read" {"uri" "file:///vault/key"}) ["error" "data"])))
      (is (= -32003 (code (call srv reader "resources/read" {"uri" "file:///hidden/7"}))) "a template, through read")
      (is (= "7" (get-in (call srv admin "resources/read" {"uri" "file:///hidden/7"}) ["result" "contents" 0 "text"]))))
    (testing "completion needs the scopes of what it completes"
      (is (= -32003 (code (call srv reader "completion/complete" {"ref" {"type" "ref/prompt" "name" "secret"}
                                                                  "argument" {"name" "a" "value" ""}}))))
      (is (= -32003 (code (call srv reader "completion/complete" {"ref" {"type" "ref/resource" "uri" "file:///hidden/{id}"}
                                                                  "argument" {"name" "id" "value" ""}}))))
      (is (= ["ok"] (get-in (call srv reader "completion/complete" {"ref" {"type" "ref/prompt" "name" "public"}
                                                                    "argument" {"name" "a" "value" ""}})
                            ["result" "completion" "values"])))
      (is (= ["leak"] (get-in (call srv admin "completion/complete" {"ref" {"type" "ref/prompt" "name" "secret"}
                                                                     "argument" {"name" "a" "value" ""}})
                              ["result" "completion" "values"]))))))

(deftest default-scopes
  (let [srv (server/server {:name "t" :version "1" :default-scopes ["member"]
                            :required-scopes {[:tool "open"] []}
                            :tools [{:name "open" :handler (fn [_ _] "o")}
                                    {:name "members" :handler (fn [_ _] "m")}]})]
    (is (= ["open"] (map #(get % "name") (get-in (call srv reader "tools/list" {}) ["result" "tools"]))))
    (is (= -32003 (code (call srv reader "tools/call" {"name" "members" "arguments" {}}))))
    (is (= "m" (get-in (call srv {:subject "m" :scopes ["member"]} "tools/call" {"name" "members" "arguments" {}})
                       ["result" "content" 0 "text"])))))

(defn- listen
  "Open subscriptions/listen for uris as who; returns {:sent atom :stop! f}."
  [srv who uris]
  (let [sent (atom [])
        p (server/prepare srv {} (rpc/request 9 "subscriptions/listen"
                                              {"notifications" {"resourceSubscriptions" uris} "_meta" meta-2026})
                          {:auth who :notify #(swap! sent conj %)})
        work (future ((:respond p)))]
    (loop [n 0] (when (and (empty? @sent) (< n 200)) (Thread/sleep 10) (recur (inc n))))
    {:sent sent :stop! (fn [] ((:cancel! p)) (future-cancel work))}))

(defn- methods-of [sent] (mapv #(get % "method") @sent))

(deftest subscriptions-follow-the-policy
  (let [srv (policy-server)]
    (testing "listen acknowledges only what the caller may read, and sends only that"
      (let [{:keys [sent stop!]} (listen srv reader ["file:///vault/key" "file:///open" "file:///hidden/1"])]
        (try
          (is (= {"resourceSubscriptions" ["file:///open"]} (get-in (first @sent) ["params" "notifications"])))
          (server/resource-updated! srv "file:///vault/key")
          (server/resource-updated! srv "file:///hidden/1")
          (server/resource-updated! srv "file:///open")
          (Thread/sleep 100)
          (is (= ["notifications/subscriptions/acknowledged" "notifications/resources/updated"] (methods-of sent)))
          (is (= "file:///open" (get-in (last @sent) ["params" "uri"])))
          (finally (stop!)))))
    (testing "a prefix subscription does not carry updates of a protected resource under it"
      (let [{:keys [sent stop!]} (listen srv reader ["file:///"])]
        (try
          (server/resource-updated! srv "file:///vault/key")
          (server/resource-updated! srv "file:///open")
          (Thread/sleep 100)
          (is (= ["file:///open"] (keep #(get-in % ["params" "uri"]) @sent)))
          (finally (stop!)))))
    (testing "an admin hears all of it"
      (let [{:keys [sent stop!]} (listen srv admin ["file:///"])]
        (try
          (server/resource-updated! srv "file:///vault/key")
          (Thread/sleep 100)
          (is (= ["file:///vault/key"] (keep #(get-in % ["params" "uri"]) @sent)))
          (finally (stop!)))))
    (testing "initialize-era resources/subscribe is refused with -32003"
      (let [init (server/handle srv {} (rpc/request 1 "initialize" {"protocolVersion" "2025-11-25" "capabilities" {}
                                                                    "clientInfo" {"name" "c" "version" "1"}})
                                {:auth reader})
            session (first init)
            subscribe (fn [uri] (second (server/handle srv session (rpc/request 2 "resources/subscribe" {"uri" uri})
                                                       {:auth reader})))]
        (is (= -32003 (code (subscribe "file:///vault/key"))))
        (is (= -32003 (code (subscribe "file:///hidden/3"))))
        (is (= {} (get (subscribe "file:///open") "result")))))))

;; An initialize-era session over HTTP belongs to the subject that opened it

(def now (quot (System/currentTimeMillis) 1000))

(defn- http-send [url method headers body]
  (let [b (HttpRequest/newBuilder (URI. url))]
    (doseq [[k v] headers] (.header b k v))
    (.method b method (if body (HttpRequest$BodyPublishers/ofString body) (HttpRequest$BodyPublishers/noBody)))
    (let [r (.send (HttpClient/newHttpClient) (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode r)
       :session (.orElse (.firstValue (.headers r) "mcp-session-id") nil)})))

(deftest http-sessions-belong-to-their-subject
  (let [secret "a shared secret for the test only, 32 bytes or more"
        srv (server/server {:name "t" :version "1" :versions protocol/versions
                            :tools [{:name "whoami" :handler (fn [_ ctx] (get-in ctx [:auth :subject]))}]})
        port (let [probe (http/serve! srv)] ((:stop! probe)) (:port probe))
        resource (str "http://127.0.0.1:" port "/mcp")
        {:keys [url stop!]} (http/serve! srv {:port port
                                              :auth (auth/resource-server
                                                     {:resource resource
                                                      :verify (jwt/verifier {:audience resource :secret secret})
                                                      :authorization-servers ["https://auth.example.com"]})})
        bearer (fn [sub] (str "Bearer " (jwt/sign {"sub" sub "aud" resource "exp" (+ now 600)}
                                                  {:alg "HS256" :key (.getBytes secret "UTF-8")})))
        headers (fn [sub & [session]]
                  (cond-> {"content-type" "application/json" "accept" "application/json, text/event-stream"
                           "authorization" (bearer sub)}
                    session (assoc "mcp-session-id" session "mcp-protocol-version" "2025-11-25")))
        rpc (fn [id method params] (json/write-str {"jsonrpc" "2.0" "id" id "method" method "params" params}))]
    (try
      (let [opened (http-send url "POST" (headers "ada")
                              (rpc 1 "initialize" {"protocolVersion" "2025-11-25" "capabilities" {}
                                                   "clientInfo" {"name" "c" "version" "1"}}))
            sid (:session opened)]
        (is (= 200 (:status opened)))
        (is (string? sid))
        (testing "its subject may use it"
          (is (= 200 (:status (http-send url "POST" (headers "ada" sid) (rpc 2 "tools/list" {}))))))
        (testing "another subject gets 404 for POST, GET and DELETE"
          (is (= 404 (:status (http-send url "POST" (headers "eve" sid) (rpc 3 "tools/list" {})))))
          (is (= 404 (:status (http-send url "GET" (assoc (headers "eve" sid) "accept" "text/event-stream") nil))))
          (is (= 404 (:status (http-send url "DELETE" (headers "eve" sid) nil)))))
        (testing "and the session survives that"
          (is (= 200 (:status (http-send url "POST" (headers "ada" sid) (rpc 4 "tools/list" {})))))))
      (finally (stop!)))))
