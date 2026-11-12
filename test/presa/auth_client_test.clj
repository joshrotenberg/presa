(ns presa.auth-client-test
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
  (:import (java.net URI URLDecoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)))

(deftest challenges
  (is (= [{:scheme "bearer" :params {"error" "invalid_token" "scope" "a b"
                                     "resource_metadata" "http://x/.well-known/oauth-protected-resource"}}]
         (oauth/parse-challenges "Bearer error=\"invalid_token\", scope=\"a b\", resource_metadata=\"http://x/.well-known/oauth-protected-resource\"")))
  (is (= {"scope" "mcp:write" "error" "insufficient_scope"}
         (:params (oauth/bearer-challenge "Bearer scope=\"mcp:write\", error=insufficient_scope"))))
  (is (= "bearer" (:scheme (oauth/bearer-challenge "DPoP algs=\"ES256\", Bearer realm=\"r\""))))
  (is (= {"realm" "a\"b"} (:params (oauth/bearer-challenge "Bearer realm=\"a\\\"b\"")))))

(def now (quot (System/currentTimeMillis) 1000))

(defn- form [^String body]
  (into {} (for [pair (str/split (or body "") #"&") :when (seq pair)
                 :let [[k v] (str/split pair #"=" 2)]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")])))

(defn- auth-server
  "A small authorization server: metadata, DCR, authorize (redirects at
  once), token (a signed JWT). iss-mode is :correct, :wrong or :omit;
  state-mode is :correct or :wrong (the state echoed on the redirect);
  methods is the advertised code_challenge_methods_supported (:omit leaves
  it out); metadata is merged over the metadata document; expires-in is the
  tokens' lifetime in seconds. Tokens come with a refresh token, which the
  refresh_token grant accepts; grants records each grant_type."
  [keypair _resource {:keys [iss-mode state-mode methods metadata expires-in]
                      :or {iss-mode :correct state-mode :correct methods ["S256"] expires-in 600}}]
  (let [port (promise)
        seen (atom [])
        grants (atom [])
        ;; the scope an authorization request asked for, for its token
        last-scope (atom nil)
        issuer #(str "http://127.0.0.1:" @port)
        handler (fn [{:keys [uri request-method query-string body]}]
                  (let [body (when body (slurp body))]
                    (swap! seen conj [request-method uri])
                    (case uri
                      "/.well-known/oauth-authorization-server"
                      {:status 200 :headers {"content-type" "application/json"}
                       :body (json/write-str (cond-> (merge {"issuer" (issuer)
                                                             "authorization_endpoint" (str (issuer) "/authorize")
                                                             "token_endpoint" (str (issuer) "/token")
                                                             "registration_endpoint" (str (issuer) "/register")
                                                             "code_challenge_methods_supported" methods
                                                             "authorization_response_iss_parameter_supported" true
                                                             "token_endpoint_auth_methods_supported" ["client_secret_basic"]}
                                                            metadata)
                                               (= :omit methods) (dissoc "code_challenge_methods_supported")))}
                      "/register"
                      {:status 201 :headers {"content-type" "application/json"}
                       :body (json/write-str {"client_id" "dcr-client" "client_secret" "dcr-secret"})}
                      "/authorize"
                      (let [q (form query-string)
                            _ (reset! last-scope (get q "scope"))
                            iss (case iss-mode :correct (issuer) :wrong "https://evil.example.com" :omit nil)]
                        {:status 302
                         :headers {"location" (str (get q "redirect_uri") "?code=c1&state=" (if (= state-mode :wrong) "forged" (get q "state"))
                                                   (when iss (str "&iss=" (java.net.URLEncoder/encode iss "UTF-8"))))}})
                      "/token"
                      (let [f (form body)]
                        (swap! grants conj (get f "grant_type"))
                        {:status 200 :headers {"content-type" "application/json"}
                         :body (json/write-str
                                {"access_token" (jwt/sign {"sub" (or (get f "client_id") "anon") "aud" (get f "resource")
                                                           "scope" (or (get f "scope") @last-scope "") "exp" (+ now 600)}
                                                          {:alg "RS256" :kid "k" :key (.getPrivate keypair)})
                                 "token_type" "Bearer" "expires_in" expires-in "scope" (or (get f "scope") @last-scope)
                                 "refresh_token" "r1"})})
                      {:status 404 :body ""})))
        srv (hk/run-server handler {:ip "127.0.0.1" :port 0 :legacy-return-value? false})]
    (deliver port (hk/server-port srv))
    {:issuer (issuer) :seen seen :grants grants :stop! #(deref (hk/server-stop! srv {:timeout 100}))}))

(defn- protected-server [as-issuer keypair]
  (let [probe (http/serve! (server/server {:name "x" :version "1"}))
        port (:port probe)
        _ ((:stop! probe))
        resource (str "http://127.0.0.1:" port "/mcp")
        srv (server/server {:name "p" :version "1"
                            :tools [{:name "whoami" :description "Who" :handler (fn [_ ctx] (get-in ctx [:auth :subject]))}]})
        config (auth/resource-server {:resource resource
                                      :verify (jwt/verifier {:audience resource :keys {"k" (.getPublic keypair)}})
                                      :authorization-servers [as-issuer]
                                      :scopes-supported ["mcp:read"]
                                      :required-scopes ["mcp:read"]})]
    (assoc (http/serve! srv {:port port :auth config}) :resource resource)))

(defn- rsa [] (.generateKeyPair (doto (java.security.KeyPairGenerator/getInstance "RSA") (.initialize 2048))))

(deftest code-flow
  (let [kp (rsa)
        as (auth-server kp nil {})
        rs (protected-server (:issuer as) kp)]
    (try
      (testing "without a provider, the refusal is an authorization error, not a version error"
        (let [e (try (client/connect (http/url (:url rs))) (catch Exception e e))]
          (is (= -32000 (:code (ex-data e))))
          (is (= 401 (get-in (ex-data e) [:data "status"])))))
      (testing "discovery, dynamic registration, PKCE and the token exchange"
        (let [provider (oauth/provider {:resource (:url rs) :authorizer (oauth/redirect-reader)})
              c (client/connect (http/url (:url rs) {:token-provider provider}))]
          (try
            (is (= "dcr-client" (client/text (client/call-tool c "whoami"))))
            (is (= [[:get "/.well-known/oauth-authorization-server"] [:post "/register"]
                    [:get "/authorize"] [:post "/token"]]
                   @(:seen as)))
            (finally (client/close c)))))
      (testing "the loopback authorizer, with a stand-in for the browser"
        (let [browser (fn [url] (.send (-> (HttpClient/newBuilder) (.followRedirects HttpClient$Redirect/NORMAL) (.build))
                                       (-> (HttpRequest/newBuilder (URI. url)) (.GET) (.build))
                                       (HttpResponse$BodyHandlers/discarding)))
              authorizer (oauth/loopback-authorizer browser {:timeout-ms 5000})
              c (client/connect (http/url (:url rs) {:token-provider (oauth/provider {:resource (:url rs) :authorizer authorizer
                                                                                      :client-id "pre" :client-secret "s"})}))]
          (try
            (is (= "pre" (client/text (client/call-tool c "whoami"))))
            (finally (client/close c) ((:stop! authorizer))))))
      (finally ((:stop! rs)) ((:stop! as))))))

(deftest iss-checks
  (doseq [mode [:wrong :omit]]
    (let [kp (rsa)
          as (auth-server kp nil {:iss-mode mode})
          rs (protected-server (:issuer as) kp)]
      (try
        (let [e (try (client/connect (http/url (:url rs) {:token-provider (oauth/provider {:resource (:url rs)
                                                                                           :authorizer (oauth/redirect-reader)})}))
                     (catch Exception e e))]
          (is (= -32000 (:code (ex-data e))) (str mode))
          (is (not-any? #{[:post "/token"]} @(:seen as)) (str mode ": no token request")))
        (finally ((:stop! rs)) ((:stop! as)))))))

(deftest state-mismatch-and-pkce-support
  (testing "a mismatched state is refused and no token request is made"
    (let [kp (rsa)
          as (auth-server kp nil {:state-mode :wrong})
          rs (protected-server (:issuer as) kp)]
      (try
        (let [e (try (client/connect (http/url (:url rs) {:token-provider (oauth/provider {:resource (:url rs)
                                                                                           :authorizer (oauth/redirect-reader)})}))
                     (catch Exception e e))]
          (is (= -32000 (:code (ex-data e))))
          (is (some #{[:get "/authorize"]} @(:seen as)) "the authorization request was made")
          (is (not-any? #{[:post "/token"]} @(:seen as)) "no token request"))
        (finally ((:stop! rs)) ((:stop! as))))))
  (testing "metadata without S256 is refused before the authorization request"
    (let [kp (rsa)
          as (auth-server kp nil {:methods ["plain"]})
          rs (protected-server (:issuer as) kp)]
      (try
        (let [e (try (client/connect (http/url (:url rs) {:token-provider (oauth/provider {:resource (:url rs)
                                                                                           :authorizer (oauth/redirect-reader)})}))
                     (catch Exception e e))]
          (is (= -32000 (:code (ex-data e))))
          (is (not-any? #{[:get "/authorize"] [:post "/token"]} @(:seen as))
              "neither an authorization nor a token request"))
        (finally ((:stop! rs)) ((:stop! as)))))))

(deftest client-credentials
  (let [kp (rsa)
        as (auth-server kp nil {})
        rs (protected-server (:issuer as) kp)
        c (client/connect (http/url (:url rs) {:token-provider (oauth/provider {:resource (:url rs) :grant :client-credentials
                                                                                :client-id "svc" :client-secret "s"})}))]
    (try
      (is (= "svc" (client/text (client/call-tool c "whoami"))))
      (is (not-any? #{[:get "/authorize"]} @(:seen as)))
      (finally (client/close c) ((:stop! rs)) ((:stop! as))))))

(deftest pkce-and-https-requirements
  (let [refused? (fn [as-opts provider-opts]
                   (let [kp (rsa)
                         as (auth-server kp nil as-opts)
                         rs (protected-server (:issuer as) kp)]
                     (try
                       (let [e (try (client/connect (http/url (:url rs) {:token-provider (oauth/provider (merge {:resource (:url rs)
                                                                                                                 :authorizer (oauth/redirect-reader)}
                                                                                                                provider-opts))}))
                                    nil
                                    (catch Exception e e))]
                         {:refused? (= -32000 (:code (ex-data e)))
                          :asked? (some #{[:get "/authorize"] [:post "/token"]} @(:seen as))})
                       (finally ((:stop! rs)) ((:stop! as))))))]
    (testing "metadata that does not list code_challenge_methods_supported is refused"
      (is (= {:refused? true :asked? nil} (refused? {:methods :omit} {}))))
    (testing ":allow-unadvertised-pkce? proceeds without it"
      (is (= {:refused? false :asked? [:get "/authorize"]}
             (update (refused? {:methods :omit} {:allow-unadvertised-pkce? true}) :asked? #(when % [:get "/authorize"])))))
    (testing "an http token endpoint on another host is refused before any request to it"
      (is (= {:refused? true :asked? nil} (refused? {:metadata {"token_endpoint" "http://auth.example.com/token"}} {}))))
    (testing ":allow-http? accepts it, and the flow goes on to the authorization request"
      (is (some? (:asked? (refused? {:metadata {"token_endpoint" "http://auth.example.com/token"}} {:allow-http? true})))))))

(deftest refresh-grant
  (let [kp (rsa)
        as (auth-server kp nil {:expires-in 1})
        rs (protected-server (:issuer as) kp)
        c (client/connect (http/url (:url rs) {:token-provider (oauth/provider {:resource (:url rs)
                                                                                :authorizer (oauth/redirect-reader)})}))]
    (try
      (is (= "dcr-client" (client/text (client/call-tool c "whoami"))))
      (is (= "dcr-client" (client/text (client/call-tool c "whoami"))) "an expired token is refreshed")
      (is (= ["authorization_code" "refresh_token"] (take 2 @(:grants as))))
      (is (= 1 (count (filter #{[:get "/authorize"]} @(:seen as)))) "the browser step ran once")
      (finally (client/close c) ((:stop! rs)) ((:stop! as))))))
