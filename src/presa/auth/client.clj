(ns presa.auth.client
  "The client side of MCP authorization (OAuth 2.1): a token provider for
  presa.transport.http/url. On a 401 (or a 403 insufficient_scope) it finds
  the server's protected resource metadata (RFC 9728) and authorization
  server metadata (RFC 8414, OpenID), identifies the client (a client ID
  metadata document, pre-registered credentials, or dynamic registration,
  RFC 7591), runs the authorization code flow with PKCE (RFC 7636) and the
  resource indicator (RFC 8707), checks the authorization response's iss
  (RFC 9207), and exchanges the code for a token; or runs the client
  credentials grant. See docs/oauth-client.md; follows snodo_oauth's client.

  The browser step is a function the application supplies (:authorizer):
  it gets the authorization URL and returns the URL the authorization server
  redirected to. `loopback-authorizer` opens a listener on 127.0.0.1 for
  real use; a test or harness can read the redirect's Location itself."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [org.httpkit.server :as hk])
  (:import (java.net URI URLDecoder URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpClient$Version HttpRequest
                          HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.security KeyFactory MessageDigest SecureRandom Signature)
           (java.security.spec PKCS8EncodedKeySpec)
           (java.time Duration)
           (java.util Base64)))

;; Small helpers

(defn- b64url [^bytes b] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- random-b64 [n]
  (let [b (byte-array n)] (.nextBytes (SecureRandom.) b) (b64url b)))

(defn- sha256 ^bytes [^String s] (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "US-ASCII")))

(defn- fail [step message & [data]]
  (throw (ex-info message (merge {:kind :authorization :step step} data))))

(defn- form-encode [m]
  (str/join "&" (for [[k v] m :when (some? v)]
                  (str (URLEncoder/encode (str k) "UTF-8") "=" (URLEncoder/encode (str v) "UTF-8")))))

(defn query-params
  "The query parameters of a URL, decoded."
  [url]
  (let [q (.getRawQuery (URI. url))]
    (into {} (for [pair (when q (str/split q #"&"))
                   :let [[k v] (str/split pair #"=" 2)]
                   :when (seq k)]
               [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")]))))

;; HTTP for the flow: no redirects, JSON or form bodies

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.version HttpClient$Version/HTTP_1_1)
             (.followRedirects HttpClient$Redirect/NEVER)
             (.connectTimeout (Duration/ofSeconds 10))
             (.build))))

(defn- http [method url {:keys [headers body]}]
  (let [b (-> (HttpRequest/newBuilder (URI. url)) (.timeout (Duration/ofSeconds 30)))]
    (doseq [[k v] headers] (.header b k v))
    (.method b method (if body (HttpRequest$BodyPublishers/ofString body) (HttpRequest$BodyPublishers/noBody)))
    (let [r (.send ^HttpClient @http-client (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode r)
       :header (fn [k] (.orElse (.firstValue (.headers r) k) nil))
       :body (.body r)})))

(defn- json-body [{:keys [body]}]
  (let [v (try (json/read-str body) (catch Exception _ nil))]
    (when (map? v) v)))

(defn- get-json
  "The JSON object at url, or nil when it is not a 200 JSON object."
  [url]
  (let [r (http "GET" url {:headers {"accept" "application/json"}})]
    (when (= 200 (:status r)) (json-body r))))

;; Challenges (RFC 7235)

(defn parse-challenges
  "The challenges of a WWW-Authenticate value: [{:scheme \"bearer\" :params {..}}],
  scheme and parameter names lowercased, a repeated parameter's first value
  kept."
  [header]
  (let [s (str header) n (count s)]
    (loop [i 0 acc []]
      (let [i (loop [i i] (if (and (< i n) (#{\space \tab \,} (.charAt s i))) (recur (inc i)) i))]
        (if (>= i n)
          acc
          (let [token-end (loop [j i] (if (and (< j n) (not (#{\space \tab \, \=} (.charAt s j)))) (recur (inc j)) j))
                token (subs s i token-end)
                after (loop [j token-end] (if (and (< j n) (#{\space \tab} (.charAt s j))) (recur (inc j)) j))]
            (if (and (< after n) (= \= (.charAt s after)) (seq acc))
              ;; a parameter of the current challenge
              (let [v-start (loop [j (inc after)] (if (and (< j n) (#{\space \tab} (.charAt s j))) (recur (inc j)) j))
                    [value end] (if (and (< v-start n) (= \" (.charAt s v-start)))
                                  (loop [j (inc v-start) sb (StringBuilder.)]
                                    (cond (>= j n) [(str sb) j]
                                          (= \\ (.charAt s j)) (recur (+ j 2) (if (< (inc j) n) (.append sb (.charAt s (inc j))) sb))
                                          (= \" (.charAt s j)) [(str sb) (inc j)]
                                          :else (recur (inc j) (.append sb (.charAt s j)))))
                                  (let [e (loop [j v-start] (if (and (< j n) (not (#{\, \space \tab} (.charAt s j)))) (recur (inc j)) j))]
                                    [(subs s v-start e) e]))
                    k (str/lower-case token)
                    acc (update-in acc [(dec (count acc)) :params] #(if (contains? % k) % (assoc % k value)))]
                (recur end acc))
              (recur after (conj acc {:scheme (str/lower-case token) :params {}})))))))))

(defn bearer-challenge
  "The bearer challenge of a WWW-Authenticate value, else its first one."
  [header]
  (let [cs (parse-challenges header)]
    (or (first (filter #(= "bearer" (:scheme %)) cs)) (first cs))))

(defn- challenge-scopes [challenge]
  (let [s (get-in challenge [:params "scope"])]
    (when-not (str/blank? s) (vec (remove str/blank? (str/split s #"\s+"))))))

;; Discovery

(defn- origin [^URI u]
  (str (.getScheme u) "://" (.getRawAuthority u)))

(defn- trim-slash [p] (if (and (str/ends-with? p "/") (> (count p) 1)) (subs p 0 (dec (count p))) p))

(defn- prm-urls
  "Where the protected resource metadata may be: the challenge's URL alone
  when it names one; else path-based, then root (RFC 9728 section 3)."
  [server-url challenge]
  (if-let [u (get-in challenge [:params "resource_metadata"])]
    [u]
    (let [uri (URI. server-url)
          path (trim-slash (or (.getRawPath uri) ""))
          root (str (origin uri) "/.well-known/oauth-protected-resource")]
      (if (or (str/blank? path) (= "/" path)) [root] [(str root path) root]))))

(defn- same-resource?
  "Whether the PRM's resource names server-url: the same scheme, host and
  port, and a path that is empty, equal, or a prefix at a segment boundary."
  [resource server-url]
  (try
    (let [r (URI. resource) s (URI. server-url)
          port (fn [^URI u] (if (neg? (.getPort u)) (if (= "https" (str/lower-case (.getScheme u))) 443 80) (.getPort u)))
          rp (trim-slash (or (.getRawPath r) "")) sp (trim-slash (or (.getRawPath s) ""))]
      (and (= (str/lower-case (.getScheme r)) (str/lower-case (.getScheme s)))
           (= (str/lower-case (.getHost r)) (str/lower-case (.getHost s)))
           (= (port r) (port s))
           (or (contains? #{"" "/"} rp) (= rp sp) (str/starts-with? sp (str rp "/")))))
    (catch Exception _ false)))

(defn- discover-prm [server-url challenge]
  (or (some (fn [u] (when-let [doc (get-json u)] (assoc doc ::url u))) (prm-urls server-url challenge))
      (fail :resource-metadata "No protected resource metadata was found")))

(defn- as-metadata-urls [issuer]
  (let [uri (URI. issuer)
        o (origin uri)
        path (trim-slash (or (.getRawPath uri) ""))]
    (if (or (str/blank? path) (= "/" path))
      [(str o "/.well-known/oauth-authorization-server") (str o "/.well-known/openid-configuration")]
      [(str o "/.well-known/oauth-authorization-server" path)
       (str o "/.well-known/openid-configuration" path)
       (str o path "/.well-known/openid-configuration")])))

(defn- discover-as [issuer]
  (let [doc (or (some get-json (as-metadata-urls issuer))
                (fail :authorization-server-metadata (str "No authorization server metadata for " issuer)))]
    ;; RFC 8414 section 3.3: exactly the issuer the URL was built from
    (when-not (= issuer (get doc "issuer"))
      (fail :authorization-server-metadata "The authorization server metadata names another issuer"
            {:expected issuer :found (get doc "issuer")}))
    (when-not (string? (get doc "token_endpoint"))
      (fail :authorization-server-metadata "The authorization server metadata has no token_endpoint"))
    doc))

;; Client identity and authentication

(defn- choose-method [{:keys [auth-method]} registered as secret?]
  (let [supported (or (get as "token_endpoint_auth_methods_supported") ["client_secret_basic"])]
    (or auth-method
        (get registered "token_endpoint_auth_method")
        (cond
          (not secret?) "none"
          (some #{"client_secret_basic"} supported) "client_secret_basic"
          (some #{"client_secret_post"} supported) "client_secret_post"
          (some #{"none"} supported) "none"
          :else "client_secret_basic"))))

(defn- register! [state opts as issuer redirect-uri]
  (let [endpoint (or (get as "registration_endpoint")
                     (fail :client-identity "No client identity: no registration endpoint and no client_id"))
        r (http "POST" endpoint
                {:headers {"content-type" "application/json" "accept" "application/json"}
                 :body (json/write-str
                        {"client_name" (:client-name opts "presa")
                         "redirect_uris" [redirect-uri]
                         "grant_types" ["authorization_code" "refresh_token"]
                         "response_types" ["code"]
                         "token_endpoint_auth_method" (if (some #{"none"} (get as "token_endpoint_auth_methods_supported" []))
                                                        "none" "client_secret_basic")
                         "application_type" (if (str/starts-with? redirect-uri "https:") "web" "native")})})
        doc (json-body r)]
    (when-not (and (<= 200 (:status r) 299) (string? (get doc "client_id")))
      (fail :client-registration (str "Client registration failed with " (:status r))))
    (let [entry {:client-id (get doc "client_id") :client-secret (get doc "client_secret")
                 :registered doc :redirect-uri redirect-uri}]
      (swap! state assoc-in [:registrations issuer] entry)
      entry)))

(defn- client-identity
  "{:client-id :client-secret :registered} for this authorization server."
  [state opts as issuer redirect-uri]
  (cond
    (and (:client-metadata-url opts) (true? (get as "client_id_metadata_document_supported")))
    {:client-id (:client-metadata-url opts) :cimd? true}

    (:client-id opts)
    {:client-id (:client-id opts) :client-secret (:client-secret opts)}

    :else
    (let [stored (get-in @state [:registrations issuer])]
      (if (and stored (= redirect-uri (:redirect-uri stored)))
        stored
        (register! state opts as issuer redirect-uri)))))

(defn- pem->private-key [pem]
  (let [der (.decode (Base64/getMimeDecoder) ^String (str/replace pem #"-----[A-Z ]+-----" ""))]
    (.generatePrivate (KeyFactory/getInstance "EC") (PKCS8EncodedKeySpec. der))))

(defn- client-assertion [{:keys [private-key-pem]} client-id audience]
  (let [now (quot (System/currentTimeMillis) 1000)
        enc #(b64url (.getBytes (json/write-str %) "UTF-8"))
        input (str (enc {"alg" "ES256" "typ" "JWT"}) "."
                   (enc {"iss" client-id "sub" client-id "aud" audience "jti" (random-b64 16)
                         "iat" now "exp" (+ now 300)}))
        s (doto (Signature/getInstance "SHA256withECDSAinP1363Format")
            (.initSign (pem->private-key private-key-pem))
            (.update (.getBytes input "UTF-8")))]
    (str input "." (b64url (.sign s)))))

(defn- token-request!
  "POST form to the token endpoint with the client's authentication; the
  token response, checked."
  [opts as identity form]
  (let [{:keys [client-id client-secret registered]} identity
        method (if (:private-key-pem opts)
                 "private_key_jwt"
                 (choose-method opts registered as (some? client-secret)))
        basic (when (= "client_secret_basic" method)
                (str "Basic " (.encodeToString (Base64/getEncoder)
                                                (.getBytes (str (URLEncoder/encode client-id "UTF-8") ":"
                                                                (URLEncoder/encode (str client-secret) "UTF-8"))
                                                           "UTF-8"))))
        form (cond-> (assoc form "client_id" client-id)
               (= "client_secret_post" method) (assoc "client_secret" client-secret)
               (= "private_key_jwt" method)
               (assoc "client_assertion_type" "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
                      "client_assertion" (client-assertion opts client-id (get as "issuer"))))
        r (http "POST" (get as "token_endpoint")
                {:headers (cond-> {"content-type" "application/x-www-form-urlencoded" "accept" "application/json"}
                            basic (assoc "authorization" basic))
                 :body (form-encode form)})
        doc (json-body r)]
    (when-not (and (= 200 (:status r)) (string? (get doc "access_token"))
                   (= "bearer" (str/lower-case (str (get doc "token_type")))))
      (fail :token (str "The token request failed with " (:status r)) {:error (get doc "error")}))
    doc))

;; Scopes and the code flow

(defn- select-scopes [opts challenge prm as granted step-up]
  (distinct
   (concat (or (challenge-scopes challenge) (get prm "scopes_supported"))
           step-up
           granted
           (:scopes opts)
           (when (and (:offline-access? opts true)
                      (some #{"offline_access"} (get as "scopes_supported")))
             ["offline_access"]))))

(defn- check-response!
  "RFC 9207 and the rest: the redirect parameters of an authorization
  response, or a failure."
  [params state-value as]
  (let [iss (get params "iss")
        issuer (get as "issuer")]
    (when-not (and (string? (get params "state"))
                   (MessageDigest/isEqual (.getBytes ^String (get params "state") "UTF-8")
                                          (.getBytes ^String state-value "UTF-8")))
      (fail :authorization-response "The authorization response's state does not match"))
    (cond
      ;; compared as strings, with no URL normalization
      (some? iss) (when-not (= iss issuer)
                    (fail :authorization-response "The authorization response names another issuer"
                          {:expected issuer :found iss}))
      (true? (get as "authorization_response_iss_parameter_supported"))
      (fail :authorization-response "The authorization response has no iss, which the server promised"))
    (when-let [e (get params "error")]
      (fail :authorization-response (str "Authorization was denied: " e) {:error e}))
    (or (get params "code") (fail :authorization-response "The authorization response has no code"))))

(defn- authorize!
  "The authorization code flow: a token response."
  [state opts as prm issuer scopes]
  (let [{:keys [redirect-uri authorize]} (:authorizer opts)
        _ (when-not (and redirect-uri authorize)
            (fail :authorizer "The code flow needs an :authorizer with :redirect-uri and :authorize"))
        methods (get as "code_challenge_methods_supported")
        _ (when (and methods (not (some #{"S256"} methods)))
            (fail :authorization-server-metadata "The authorization server does not support PKCE S256"))
        identity (client-identity state opts as issuer redirect-uri)
        verifier (random-b64 32)
        state-value (random-b64 32)
        resource (get prm "resource")
        endpoint (or (get as "authorization_endpoint")
                     (fail :authorization-server-metadata "The authorization server has no authorization_endpoint"))
        url (str endpoint (if (str/includes? endpoint "?") "&" "?")
                 (form-encode (cond-> {"response_type" "code"
                                       "client_id" (:client-id identity)
                                       "redirect_uri" redirect-uri
                                       "code_challenge" (b64url (sha256 verifier))
                                       "code_challenge_method" "S256"
                                       "state" state-value
                                       "resource" resource}
                                (seq scopes) (assoc "scope" (str/join " " scopes)))))
        redirected (authorize url)
        code (check-response! (query-params (str redirected)) state-value as)]
    [identity (token-request! opts as identity {"grant_type" "authorization_code"
                                                "code" code
                                                "redirect_uri" redirect-uri
                                                "code_verifier" verifier
                                                "resource" resource})]))

(defn- obtain!
  "Run a flow for a challenge and store the token. step-up names the new
  scopes a 403 asked for."
  [state opts challenge step-up]
  (let [server-url (:resource opts)
        prm (discover-prm server-url challenge)
        resource (get prm "resource")
        _ (when-not (and (string? resource) (same-resource? resource server-url))
            (fail :resource-metadata "The protected resource metadata names another resource"
                  {:resource resource :server server-url}))
        issuer (or (first (get prm "authorization_servers"))
                   (fail :resource-metadata "The protected resource metadata names no authorization server"))
        as (discover-as issuer)
        current (:token @state)
        ;; a token from another authorization server is not reused
        granted (when (= issuer (:issuer current)) (:scopes current))
        scopes (select-scopes opts challenge prm as granted step-up)
        [identity doc] (if (= :client-credentials (:grant opts))
                         (let [identity {:client-id (:client-id opts) :client-secret (:client-secret opts)}]
                           [identity (token-request! opts as identity
                                                     (cond-> {"grant_type" "client_credentials" "resource" resource}
                                                       (seq scopes) (assoc "scope" (str/join " " scopes))))])
                         (authorize! state opts as prm issuer scopes))
        token {:access-token (get doc "access_token")
               :refresh-token (get doc "refresh_token")
               :scopes (if-let [s (get doc "scope")] (vec (str/split s #"\s+")) (vec scopes))
               :expires-at (when (number? (get doc "expires_in"))
                             (+ (System/currentTimeMillis) (* 1000 (long (get doc "expires_in")))))
               :issuer issuer
               :client-id (:client-id identity)
               :resource-metadata (::url prm)}]
    (swap! state assoc :token token)
    (:access-token token)))

(defn provider
  "A token provider for presa.transport.http/url's :token-provider. Options:

  - :resource      the MCP server URL (required)
  - :authorizer    {:redirect-uri uri :authorize (fn [authorization-url])}: the
                   browser step, returning the URL the authorization server
                   redirected to (see `loopback-authorizer`); needed for the
                   code flow
  - :client-id, :client-secret  pre-registered credentials
  - :client-metadata-url  an https client ID metadata document URL, used
                   when the authorization server supports them
  - :client-name   for dynamic registration (default \"presa\")
  - :scopes        extra scopes to ask for
  - :offline-access?  ask for offline_access when the authorization server
                   lists it (default true)
  - :grant         :client-credentials for the client credentials grant
                   (with :client-secret or :private-key-pem, an EC P-256
                   PKCS#8 PEM for private_key_jwt)
  - :auth-method   force a token endpoint auth method

  Flows run one at a time; a refusal that arrives while one runs gets its
  result."
  [opts]
  (when-not (:resource opts)
    (throw (ex-info "An OAuth provider needs :resource, the MCP server URL" {:kind :invalid-input})))
  (let [state (atom {})
        lock (Object.)]
    {:token (fn [_]
              (let [{:keys [access-token expires-at]} (:token @state)]
                (cond
                  (and access-token (or (nil? expires-at) (< (System/currentTimeMillis) (- expires-at 30000))))
                  access-token

                  ;; a client credentials client can get a token up front
                  (= :client-credentials (:grant opts))
                  (locking lock (obtain! state opts nil nil))

                  :else nil)))
     :refresh (fn [{:keys [challenge status token]}]
                (locking lock
                  (let [current (get-in @state [:token :access-token])]
                    (if (and current (not= current token))
                      ;; another request already got a new token
                      current
                      (if (= 403 status)
                        (let [asked (challenge-scopes challenge)
                              granted (set (get-in @state [:token :scopes]))
                              new (remove granted asked)]
                          (cond
                            (empty? asked) (fail :step-up "A 403 asked for more scope without naming any")
                            (empty? new) (fail :step-up "The server asks for scopes the token already has"
                                               {:scopes (vec asked)})
                            :else (obtain! state opts challenge new)))
                        (obtain! state opts challenge nil))))))}))

(defn loopback-authorizer
  "An authorizer for the code flow: a listener on 127.0.0.1 (a free port, or
  :port) that receives the redirect at /callback. open-url is called with the
  authorization URL (open a browser there). Waits up to :timeout-ms (default
  300000) for the redirect."
  ([open-url] (loopback-authorizer open-url {}))
  ([open-url {:keys [port timeout-ms] :or {port 0 timeout-ms 300000}}]
   (let [result (atom (promise))
         srv (hk/run-server (fn [req]
                              (if (= "/callback" (:uri req))
                                (do (deliver @result (str "http://127.0.0.1" (:uri req) "?" (:query-string req)))
                                    {:status 200 :headers {"content-type" "text/plain"}
                                     :body "Authorization complete. You can close this window."})
                                {:status 404 :body ""}))
                            {:ip "127.0.0.1" :port port :legacy-return-value? false})]
     {:redirect-uri (str "http://127.0.0.1:" (hk/server-port srv) "/callback")
      :authorize (fn [url]
                   (reset! result (promise))
                   (open-url url)
                   (let [r (deref @result timeout-ms nil)]
                     (or r (fail :authorizer "No authorization response arrived in time"))))
      :stop! (fn [] @(hk/server-stop! srv {:timeout 100}) nil)})))

(defn redirect-reader
  "An authorizer that needs no browser: it requests the authorization URL
  and reads where the server redirects. For tests and for authorization
  servers that answer without a user, such as the conformance runner's."
  ([] (redirect-reader "http://127.0.0.1:8976/callback"))
  ([redirect-uri]
   {:redirect-uri redirect-uri
    :authorize (fn [url]
                 (let [r (http "GET" url {})]
                   (or ((:header r) "location")
                       (fail :authorizer (str "The authorization endpoint answered " (:status r) " without a redirect")))))}))
