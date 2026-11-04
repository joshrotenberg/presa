(ns presa.auth-dpop-test
  "DPoP (RFC 9449) in the OAuth client, against a stub authorization server
  that binds tokens to the proof key and a stub resource server that checks
  proofs, both asking for nonces."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as hk]
            [presa.auth.client :as oauth]
            [presa.auth.dpop :as dpop]
            [presa.auth.jwt :as jwt]
            [presa.client :as client]
            [presa.server :as server]
            [presa.transport.http :as http])
  (:import (java.net URLDecoder)
           (java.util Base64)))

(defn- part [^String jws i]
  (json/read-str (String. (.decode (Base64/getUrlDecoder) ^String (nth (str/split jws #"\.") i)) "UTF-8")))

(defn- verifies? [^String jws]
  (let [[h c s] (str/split jws #"\.")
        k (jwt/jwk->public-key (get (part jws 0) "jwk"))
        v (doto (java.security.Signature/getInstance "SHA256withECDSAinP1363Format")
            (.initVerify k)
            (.update (.getBytes (str h "." c) "US-ASCII")))]
    (.verify v (.decode (Base64/getUrlDecoder) ^String s))))

(deftest proofs
  (let [kp (dpop/key-pair)
        p (dpop/proof kp "POST" "https://as.example.com/token?x=1#f" {:access-token "tok" :nonce "n1"})
        header (part p 0)
        claims (part p 1)]
    (is (= "dpop+jwt" (get header "typ")))
    (is (= "ES256" (get header "alg")))
    (is (= #{"kty" "crv" "x" "y"} (set (keys (get header "jwk")))) "the public key only")
    (is (= "POST" (get claims "htm")))
    (is (= "https://as.example.com/token" (get claims "htu")) "no query or fragment")
    (is (= (dpop/ath "tok") (get claims "ath")))
    (is (= "n1" (get claims "nonce")))
    (is (string? (get claims "jti")))
    (is (verifies? p))
    (is (not= (get claims "jti") (get (part (dpop/proof kp "POST" "https://x/" {}) 1) "jti")) "a fresh jti each time")
    (is (= 43 (count (dpop/thumbprint (dpop/public-jwk kp)))))))

(defn- form [^String body]
  (into {} (for [pair (str/split (or body "") #"&") :when (seq pair)
                 :let [[k v] (str/split pair #"=" 2)]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")])))

(def now (quot (System/currentTimeMillis) 1000))

(defn- stubs
  "An authorization server and a resource server that both require DPoP
  and a nonce. Returns their URLs, what they saw, and a stop function."
  []
  (let [signing (.generateKeyPair (doto (java.security.KeyPairGenerator/getInstance "RSA") (.initialize 2048)))
        as-port (promise) rs-port (promise)
        issuer #(str "http://127.0.0.1:" @as-port)
        resource #(str "http://127.0.0.1:" @rs-port "/mcp")
        seen (atom {:token-requests [] :mcp []})
        as (hk/run-server
            (fn [{:keys [uri query-string body headers]}]
              (case uri
                "/.well-known/oauth-authorization-server"
                {:status 200 :headers {"content-type" "application/json"}
                 :body (json/write-str {"issuer" (issuer)
                                        "authorization_endpoint" (str (issuer) "/authorize")
                                        "token_endpoint" (str (issuer) "/token")
                                        "code_challenge_methods_supported" ["S256"]
                                        "dpop_signing_alg_values_supported" ["ES256"]
                                        "token_endpoint_auth_methods_supported" ["none"]})}
                "/authorize"
                (let [q (form query-string)]
                  {:status 302 :headers {"location" (str (get q "redirect_uri") "?code=c1&state=" (get q "state"))}})
                "/token"
                (let [proof (get headers "dpop")
                      f (form (slurp body))]
                  (swap! seen update :token-requests conj proof)
                  (cond
                    (not (and proof (verifies? proof))) {:status 400 :body (json/write-str {"error" "invalid_dpop_proof"})}
                    (not= "as-nonce" (get (part proof 1) "nonce"))
                    {:status 400 :headers {"dpop-nonce" "as-nonce" "content-type" "application/json"}
                     :body (json/write-str {"error" "use_dpop_nonce"})}
                    :else
                    {:status 200 :headers {"content-type" "application/json"}
                     :body (json/write-str
                            {"access_token" (jwt/sign {"sub" "ada" "aud" (get f "resource") "exp" (+ now 600)
                                                       "cnf" {"jkt" (dpop/thumbprint (get (part proof 0) "jwk"))}}
                                                      {:alg "RS256" :key (.getPrivate signing)})
                             "token_type" "DPoP" "expires_in" 600})}))
                {:status 404 :body ""}))
            {:ip "127.0.0.1" :port 0 :legacy-return-value? false})
        mcp (http/handler (server/server {:name "rs" :version "1"
                                          :tools [{:name "hi" :description "Hi" :handler (fn [_ _] "hi")}]})
                          {})
        challenge (fn [extra] (str "DPoP " extra "resource_metadata=\"http://127.0.0.1:" @rs-port
                                   "/.well-known/oauth-protected-resource/mcp\""))
        rs (hk/run-server
            (fn [{:keys [uri headers request-method] :as req}]
              (if (= "/.well-known/oauth-protected-resource/mcp" uri)
                {:status 200 :headers {"content-type" "application/json"}
                 :body (json/write-str {"resource" (resource) "authorization_servers" [(issuer)]})}
                (let [authz (get headers "authorization")
                      proof (get headers "dpop")]
                  (if-not authz
                    {:status 401 :headers {"www-authenticate" (challenge "")} :body ""}
                    (let [[scheme token] (str/split authz #" " 2)
                          claims (when proof (part proof 1))]
                      (swap! seen update :mcp conj {:scheme scheme :method request-method :claims claims
                                                    :valid? (boolean (and proof (verifies? proof)))
                                                    :bound? (= (get-in (part token 1) ["cnf" "jkt"])
                                                               (dpop/thumbprint (get (part proof 0) "jwk")))})
                      (if (not= "rs-nonce" (get claims "nonce"))
                        {:status 401 :headers {"www-authenticate" (challenge "error=\"use_dpop_nonce\", ")
                                               "dpop-nonce" "rs-nonce"}
                         :body ""}
                        (mcp req)))))))
            {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (deliver as-port (hk/server-port as))
    (deliver rs-port (hk/server-port rs))
    {:resource (resource) :seen seen
     :stop! (fn [] @(hk/server-stop! as {:timeout 100}) @(hk/server-stop! rs {:timeout 100}))}))

(deftest dpop-bound-tokens
  (let [{:keys [resource seen stop!]} (stubs)]
    (try
      (let [provider (oauth/provider {:resource resource :authorizer (oauth/redirect-reader) :client-id "c"})
            c (client/connect (http/url resource {:token-provider provider}))]
        (try
          (is (= "hi" (client/text (client/call-tool c "hi"))))
          (testing "the token request carries a proof, and is retried with the server's nonce"
            (let [[first-try retry] (:token-requests @seen)]
              (is (= 2 (count (:token-requests @seen))))
              (is (nil? (get (part first-try 1) "nonce")))
              (is (= "as-nonce" (get (part retry 1) "nonce")))
              (is (= "POST" (get (part retry 1) "htm")))))
          (testing "each MCP request presents the token with DPoP and a fresh, bound proof"
            (let [requests (:mcp @seen)]
              (is (every? #(= "DPoP" (:scheme %)) requests))
              (is (every? :valid? requests))
              (is (every? :bound? requests))
              (is (every? #(= resource (get-in % [:claims "htu"])) requests))
              (is (apply distinct? (map #(get-in % [:claims "jti"]) requests)))
              (testing "the resource server's nonce, after one use_dpop_nonce"
                (is (nil? (get-in (first requests) [:claims "nonce"])))
                (is (every? #(= "rs-nonce" (get-in % [:claims "nonce"])) (rest requests))))))
          (finally (client/close c))))
      (testing ":dpop false keeps Bearer, which this authorization server refuses"
        (let [e (try (client/connect (http/url resource {:token-provider (oauth/provider {:resource resource :dpop false
                                                                                          :authorizer (oauth/redirect-reader)
                                                                                          :client-id "c"})}))
                     (catch Exception e e))]
          (is (= -32000 (:code (ex-data e))))))
      (finally (stop!)))))
