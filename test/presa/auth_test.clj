(ns presa.auth-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [presa.auth :as auth]
            [presa.auth.jwt :as jwt]
            [presa.client :as client]
            [presa.input :as input]
            [presa.server :as server]
            [presa.transport.http :as http])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(defn- rsa-pair [] (.generateKeyPair (doto (java.security.KeyPairGenerator/getInstance "RSA") (.initialize 2048))))
(defn- ec-pair [] (.generateKeyPair (doto (java.security.KeyPairGenerator/getInstance "EC")
                                      (.initialize (java.security.spec.ECGenParameterSpec. "secp256r1")))))

(def now (quot (System/currentTimeMillis) 1000))

(deftest jwt-verification
  (let [rsa (rsa-pair)
        ec (ec-pair)
        aud "https://mcp.example.com/mcp"
        v (jwt/verifier {:audience aud :issuer "https://auth.example.com"
                         :keys {"r" (assoc (jwt/public-key->jwk (.getPublic rsa)) "kid" "r")
                                "e" (.getPublic ec)}})
        claims {"sub" "ada" "aud" aud "iss" "https://auth.example.com" "scope" "mcp:read mcp:write"
                "client_id" "c1" "exp" (+ now 600)}
        rs (fn [c] (jwt/sign c {:alg "RS256" :kid "r" :key (.getPrivate rsa)}))]
    (is (= {:subject "ada" :client-id "c1" :scopes ["mcp:read" "mcp:write"]}
           (select-keys (v (rs claims)) [:subject :client-id :scopes])))
    (is (= "ada" (:subject (v (jwt/sign claims {:alg "ES256" :kid "e" :key (.getPrivate ec)})))))
    (is (= ["a"] (:scopes (v (rs (-> claims (dissoc "scope") (assoc "scp" ["a"])))))))
    (testing "refusals"
      (is (= :expired (:error (v (rs (assoc claims "exp" (- now 10)))))))
      (is (= :invalid-token (:error (v (rs (assoc claims "nbf" (+ now 600)))))))
      (is (= :wrong-audience (:error (v (rs (assoc claims "aud" "https://other.example.com"))))))
      (is (= :wrong-issuer (:error (v (rs (assoc claims "iss" "https://evil.example.com"))))))
      (is (= :invalid-token (:error (v (jwt/sign claims {:alg "RS256" :kid "r" :key (.getPrivate (rsa-pair))})))))
      (is (= :invalid-token (:error (v (jwt/sign claims {:alg "RS256" :kid "unknown" :key (.getPrivate rsa)})))))
      (is (= :invalid-token (:error (v (str (rs claims) "x")))))
      (is (= :invalid-token (:error (v "not.a.jwt"))))
      (is (= :invalid-token (:error (v (jwt/sign claims {:alg "HS256" :key (.getBytes "guess")}))))
          "no HS256 secret configured"))
    (testing "JWK round trip"
      (is (= (seq (.getEncoded (jwt/jwk->public-key (jwt/public-key->jwk (.getPublic rsa)))))
             (seq (.getEncoded (.getPublic rsa)))))
      (is (= (seq (.getEncoded (.getPublic ec)))
             (seq (.getEncoded (jwt/jwk->public-key (jwt/public-key->jwk (.getPublic ec))))))))))

(deftest jwks-documents
  (let [rsa (rsa-pair)
        served (atom [])
        jwks-srv (org.httpkit.server/run-server
                  (fn [_] (swap! served conj 1)
                    {:status 200 :headers {"content-type" "application/json"}
                     :body (json/write-str {"keys" [(assoc (jwt/public-key->jwk (.getPublic rsa)) "kid" "k1" "alg" "RS256")]})})
                  {:port 0 :ip "127.0.0.1" :legacy-return-value? false})
        url (str "http://127.0.0.1:" (org.httpkit.server/server-port jwks-srv) "/jwks")
        v (jwt/verifier {:audience "x" :jwks-url url :refresh-ms 60000})
        tok (fn [kid] (jwt/sign {"sub" "s" "aud" "x"} {:alg "RS256" :kid kid :key (.getPrivate rsa)}))]
    (try
      (is (= "s" (:subject (v (tok "k1")))))
      (is (= "s" (:subject (v (tok "k1")))))
      (is (= 1 (count @served)) "the document is cached")
      (is (= :invalid-token (:error (v (tok "k2")))))
      (is (= 1 (count @served)) "an unknown kid refetches at most once per refresh interval")
      (finally @(org.httpkit.server/server-stop! jwks-srv)))))

(deftest resource-server-config
  (is (= "https://mcp.example.com/mcp" (auth/canonical-resource "HTTPS://MCP.Example.com/mcp")))
  (is (= "https://mcp.example.com" (auth/canonical-resource "https://mcp.example.com/")))
  (is (= "/.well-known/oauth-protected-resource/mcp" (auth/metadata-path "https://mcp.example.com/mcp")))
  (is (= "/.well-known/oauth-protected-resource" (auth/metadata-path "https://mcp.example.com")))
  (doseq [bad ["mcp.example.com" "ftp://x/y" "https://x/y#frag"]]
    (is (= :invalid-input (:kind (ex-data (try (auth/canonical-resource bad) (catch Exception e e))))) bad)))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {"elicitation" {}}})

(defn- http-send [url method headers body]
  (let [b (HttpRequest/newBuilder (URI. url))]
    (doseq [[k v] headers] (.header b k v))
    (.method b method (if body (HttpRequest$BodyPublishers/ofString body) (HttpRequest$BodyPublishers/noBody)))
    (let [r (.send (HttpClient/newHttpClient) (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode r)
       :www (.orElse (.firstValue (.headers r) "www-authenticate") nil)
       :body (.body r)})))

(deftest http-resource-server
  (let [rsa (rsa-pair)
        srv (server/server
             {:name "t" :version "1"
              :required-scopes {[:tool "admin"] ["mcp:admin"]}
              :tools [{:name "whoami" :handler (fn [_ ctx] (get-in ctx [:auth :subject]))}
                      {:name "admin" :handler (fn [_ _] "admin ran")}
                      {:name "ask" :handler (fn [_ ctx]
                                              (if (server/input-response ctx "n") "answered"
                                                  (server/input-required {"n" (input/elicit "n?" {"type" "object"})} {"k" 1})))}]})
        running (http/serve! srv {:auth nil})
        port (:port running)
        _ ((:stop! running))
        resource (str "http://127.0.0.1:" port "/mcp")
        config (auth/resource-server {:resource resource
                                      :verify (jwt/verifier {:audience resource :keys {"k" (.getPublic rsa)}})
                                      :authorization-servers ["https://auth.example.com"]
                                      :scopes-supported ["mcp:read" "mcp:admin"]
                                      :required-scopes ["mcp:read"]})
        {:keys [url stop!]} (http/serve! srv {:port port :auth config})
        token (fn [sub scopes] (jwt/sign {"sub" sub "aud" resource "scope" scopes "exp" (+ now 600)}
                                         {:alg "RS256" :kid "k" :key (.getPrivate rsa)}))
        post (fn [headers] (http-send url "POST" (merge {"content-type" "application/json"
                                                         "accept" "application/json, text/event-stream"
                                                         "mcp-protocol-version" "2026-07-28" "mcp-method" "tools/list"}
                                                        headers)
                                      (json/write-str {"jsonrpc" "2.0" "id" 1 "method" "tools/list" "params" {"_meta" meta-2026}})))]
    (try
      (testing "the metadata document needs no token"
        (let [r (http-send (str "http://127.0.0.1:" port "/.well-known/oauth-protected-resource/mcp") "GET" {} nil)]
          (is (= 200 (:status r)))
          (is (= {"resource" resource "authorization_servers" ["https://auth.example.com"]
                  "bearer_methods_supported" ["header"] "scopes_supported" ["mcp:read" "mcp:admin"]}
                 (json/read-str (:body r))))))
      (testing "challenges"
        (let [r (post {})]
          (is (= 401 (:status r)))
          (is (= (str "Bearer scope=\"mcp:read\", resource_metadata=\"http://127.0.0.1:" port
                      "/.well-known/oauth-protected-resource/mcp\"")
                 (:www r))))
        (let [r (post {"authorization" "Bearer not-a-token"})]
          (is (= 401 (:status r)))
          (is (str/includes? (:www r) "error=\"invalid_token\"")))
        (let [r (post {"authorization" (str "Bearer " (token "ada" "other"))})]
          (is (= 403 (:status r)))
          (is (str/includes? (:www r) "error=\"insufficient_scope\"")))
        (is (= 400 (:status (post {"authorization" "Bearer a b"})))))
      (testing "a valid token reaches the handler as (:auth context)"
        (let [c (client/connect (http/url url {:headers {"authorization" (str "Bearer " (token "ada" "mcp:read"))}})
                                {:handlers {"elicitation/create" (fn [_] {"action" "accept" "content" {}})}})]
          (try
            (is (= "ada" (client/text (client/call-tool c "whoami"))))
            (testing "a tool needing a scope the caller lacks is hidden and refused"
              (is (= ["whoami" "ask"] (map #(get % "name") (client/list-tools c))))
              (let [e (try (client/call-tool c "admin") (catch Exception e e))]
                (is (= -32003 (:code (ex-data e))))
                (is (= {"error" "insufficient_scope" "scope" "mcp:admin"} (:data (ex-data e))))))
            (is (= "answered" (client/text (client/call-tool c "ask"))) "multi round-trip input works with a principal")
            (finally (client/close c)))))
      (testing "with the scope, the tool is listed and runs"
        (let [c (client/connect (http/url url {:headers {"authorization" (str "Bearer " (token "root" "mcp:read mcp:admin"))}}))]
          (try
            (is (= "admin ran" (client/text (client/call-tool c "admin"))))
            (finally (client/close c)))))
      (finally (stop!)))))

(deftest request-state-is-bound-to-the-principal
  (let [srv (server/server {:name "t" :version "1" :state-secret (apply str (repeat 32 "s"))
                            :tools [{:name "ask" :handler (fn [_ ctx]
                                                            (if (:state ctx) "resumed"
                                                                (server/input-required {"n" (input/elicit "n?" {"type" "object"})} {"k" 1})))}]})
        call (fn [subject params]
               (second (server/handle srv {} {"jsonrpc" "2.0" "id" 1 "method" "tools/call"
                                              "params" (merge {"name" "ask" "_meta" meta-2026} params)}
                                      {:auth {:subject subject :scopes []}})))
        state (get-in (call "ada" {}) ["result" "requestState"])]
    (is (= "resumed" (get-in (call "ada" {"requestState" state "inputResponses" {"n" {"action" "accept" "content" {}}}})
                             ["result" "content" 0 "text"])))
    (is (= -32602 (get-in (call "mallory" {"requestState" state "inputResponses" {"n" {"action" "accept" "content" {}}}})
                          ["error" "code"])))))

(deftest posted-cancellation-is-scoped-to-the-subject
  (let [rsa (rsa-pair)
        started (atom 0)
        interrupted (atom #{})
        srv (server/server {:name "t" :version "1"
                            :tools [{:name "block"
                                     :handler (fn [{:strs [who]} _]
                                                (swap! started inc)
                                                (try (Thread/sleep 3000) "finished"
                                                     (catch InterruptedException _
                                                       (swap! interrupted conj who) "interrupted")))}]})
        probe (http/serve! srv)
        port (:port probe)
        _ ((:stop! probe))
        resource (str "http://127.0.0.1:" port "/mcp")
        config (auth/resource-server {:resource resource
                                      :verify (jwt/verifier {:audience resource :keys {"k" (.getPublic rsa)}})
                                      :authorization-servers ["https://auth.example.com"]})
        {:keys [url stop!]} (http/serve! srv {:port port :auth config :cancellation :subject})
        bearer (fn [sub] (str "Bearer " (jwt/sign {"sub" sub "aud" resource "exp" (+ now 600)}
                                                  {:alg "RS256" :kid "k" :key (.getPrivate rsa)})))
        headers (fn [sub method] {"content-type" "application/json" "accept" "application/json, text/event-stream"
                                  "mcp-protocol-version" "2026-07-28" "mcp-method" method
                                  "authorization" (bearer sub)})
        call (fn [sub who]
               (future (http-send url "POST" (assoc (headers sub "tools/call") "mcp-name" "block")
                                  (json/write-str {"jsonrpc" "2.0" "id" 7 "method" "tools/call"
                                                   "params" {"name" "block" "arguments" {"who" who} "_meta" meta-2026}}))))
        cancel (fn [sub]
                 (http-send url "POST" (headers sub "notifications/cancelled")
                            (json/write-str {"jsonrpc" "2.0" "method" "notifications/cancelled" "params" {"requestId" 7}})))]
    (try
      (let [ada (call "ada" "ada")
            bob (call "bob" "bob")]
        (loop [n 0] (when (and (< @started 2) (< n 200)) (Thread/sleep 10) (recur (inc n))))
        (is (= 202 (:status (cancel "ada"))))
        (is (= 204 (:status (deref ada 3000 nil))) "ada's request 7 was cancelled and answered with no body")
        (is (= 200 (:status (deref bob 5000 nil))) "bob's request 7, same id, ran to the end")
        (is (= #{"ada"} @interrupted)))
      (finally (stop!)))))
