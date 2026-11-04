(ns presa.auth-grants-test
  "The JWT-bearer grant (RFC 7523, workload identity) and enterprise-managed
  authorization (an RFC 8693 exchange for an ID-JAG, then a JWT-bearer
  grant), against a stub identity provider and authorization server in
  front of a presa resource server."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as hk]
            [presa.auth :as auth]
            [presa.auth.client :as oauth]
            [presa.auth.jwt :as jwt]
            [presa.client :as client]
            [presa.server :as server]
            [presa.transport.http :as http])
  (:import (java.net URLDecoder)))

(defn- form [^String body]
  (into {} (for [pair (str/split (or body "") #"&") :when (seq pair)
                 :let [[k v] (str/split pair #"=" 2)]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")])))

(def now (quot (System/currentTimeMillis) 1000))

(defn- json-response [status m] {:status status :headers {"content-type" "application/json"} :body (json/write-str m)})

(defn- servers
  "An identity provider that exchanges the ID token \"id-token-1\" for an
  ID-JAG, an authorization server that grants a token for a JWT-bearer
  assertion that is \"workload-jwt\" or an ID-JAG, and a presa server that
  requires its tokens."
  []
  (let [kp (.generateKeyPair (doto (java.security.KeyPairGenerator/getInstance "RSA") (.initialize 2048)))
        as-port (promise) idp-port (promise)
        issuer #(str "http://127.0.0.1:" @as-port)
        seen (atom [])
        idp (hk/run-server
             (fn [{:keys [uri body]}]
               (let [f (form (some-> body slurp))]
                 (swap! seen conj [:idp uri f])
                 (if (and (= "/token" uri) (= "id-token-1" (get f "subject_token"))
                          (= "urn:ietf:params:oauth:token-type:id-jag" (get f "requested_token_type")))
                   (json-response 200 {"access_token" (str "jag-for-" (get f "audience"))
                                       "issued_token_type" "urn:ietf:params:oauth:token-type:id-jag"
                                       "token_type" "N_A"})
                   (json-response 400 {"error" "invalid_grant"}))))
             {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        as (hk/run-server
            (fn [{:keys [uri body headers]}]
              (case uri
                "/.well-known/oauth-authorization-server"
                (json-response 200 {"issuer" (issuer) "token_endpoint" (str (issuer) "/token")
                                    "grant_types_supported" ["urn:ietf:params:oauth:grant-type:jwt-bearer"]
                                    "token_endpoint_auth_methods_supported" ["client_secret_basic" "none"]})
                "/token"
                (let [f (form (some-> body slurp))]
                  (swap! seen conj [:as uri f (get headers "authorization")])
                  (if (and (= "urn:ietf:params:oauth:grant-type:jwt-bearer" (get f "grant_type"))
                           (#{"workload-jwt" (str "jag-for-" (issuer))} (get f "assertion")))
                    (json-response 200 {"access_token" (jwt/sign {"sub" (get f "assertion") "aud" (get f "resource")
                                                                  "scope" "mcp:read" "exp" (+ now 600)}
                                                                 {:alg "RS256" :kid "k" :key (.getPrivate kp)})
                                        "token_type" "Bearer" "expires_in" 600})
                    (json-response 400 {"error" "invalid_grant"})))
                {:status 404 :body ""}))
            {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        _ (deliver as-port (hk/server-port as))
        _ (deliver idp-port (hk/server-port idp))
        port (let [probe (http/serve! (server/server {:name "x" :version "1"}))] ((:stop! probe)) (:port probe))
        resource (str "http://127.0.0.1:" port "/mcp")
        rs (http/serve! (server/server {:name "p" :version "1"
                                        :tools [{:name "whoami" :description "Who"
                                                 :handler (fn [_ ctx] (get-in ctx [:auth :subject]))}]})
                        {:port port
                         :auth (auth/resource-server {:resource resource
                                                      :verify (jwt/verifier {:audience resource :keys {"k" (.getPublic kp)}})
                                                      :authorization-servers [(issuer)]
                                                      :required-scopes ["mcp:read"]})})]
    {:resource resource :issuer (issuer) :idp-token (str "http://127.0.0.1:" @idp-port "/token") :seen seen
     :stop! (fn [] ((:stop! rs)) @(hk/server-stop! as {:timeout 100}) @(hk/server-stop! idp {:timeout 100}))}))

(defn- token-requests [seen] (filter #(= :as (first %)) @seen))

(deftest jwt-bearer-grant
  (let [{:keys [resource seen stop!]} (servers)]
    (try
      (let [c (client/connect (http/url resource {:token-provider (oauth/provider {:resource resource :grant :jwt-bearer
                                                                                   :client-id "workload"
                                                                                   :assertion "workload-jwt"})}))]
        (try
          (is (= "workload-jwt" (client/text (client/call-tool c "whoami"))))
          (let [[[_ _ f basic]] (token-requests seen)]
            (is (= "urn:ietf:params:oauth:grant-type:jwt-bearer" (get f "grant_type")))
            (is (= resource (get f "resource")))
            (is (= "workload" (get f "client_id")))
            (is (nil? basic) "no secret: the none method"))
          (finally (client/close c))))
      (testing "a refused assertion fails once, with no retry and no other grant"
        (reset! seen [])
        (let [e (try (client/connect (http/url resource {:token-provider (oauth/provider {:resource resource :grant :jwt-bearer
                                                                                          :client-id "workload"
                                                                                          :assertion "forged"})}))
                     (catch Exception e e))]
          (is (instance? Exception e))
          (is (= 1 (count (token-requests seen))))
          (is (every? #(= "urn:ietf:params:oauth:grant-type:jwt-bearer" (get-in % [2 "grant_type"])) (token-requests seen)))))
      (finally (stop!)))))

(deftest enterprise-managed-authorization
  (let [{:keys [resource issuer idp-token seen stop!]} (servers)
        provider (oauth/provider {:resource resource :grant :jwt-bearer
                                  :client-id "mcp-client" :client-secret "s"
                                  :assertion (oauth/id-jag {:token-endpoint idp-token :id-token "id-token-1"
                                                            :client-id "idp-client"})})
        c (client/connect (http/url resource {:token-provider provider}))]
    (try
      (is (= (str "jag-for-" issuer) (client/text (client/call-tool c "whoami"))))
      (testing "the exchange at the identity provider"
        (let [[[_ _ f]] (filter #(= :idp (first %)) @seen)]
          (is (= "urn:ietf:params:oauth:grant-type:token-exchange" (get f "grant_type")))
          (is (= "urn:ietf:params:oauth:token-type:id_token" (get f "subject_token_type")))
          (is (= issuer (get f "audience")))
          (is (= resource (get f "resource")))
          (is (= "idp-client" (get f "client_id")))))
      (testing "the JWT-bearer grant authenticates the client with Basic"
        (let [[[_ _ _ basic]] (token-requests seen)]
          (is (str/starts-with? (str basic) "Basic "))))
      (finally (client/close c) (stop!)))))
