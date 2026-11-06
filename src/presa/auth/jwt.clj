(ns presa.auth.jwt
  "Verify JWS compact access tokens (RFC 7515, RFC 9068) for presa.auth:
  RS256 and ES256 against a JWKS document or static keys, and HS256 with a
  shared secret. Checks exp and nbf (with leeway), iss, and aud (RFC 8707).

  Public keys are built from JWK n/e (RSA) and x/y (P-256) as a DER
  SubjectPublicKeyInfo and loaded with X509EncodedKeySpec, because Babashka
  lacks the RSA and EC key spec classes; the same code runs on the JVM."
  (:require [clojure.data.json :as json]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.security KeyFactory MessageDigest Signature)
           (java.security.spec X509EncodedKeySpec)
           (java.time Duration)
           (java.util Base64)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

;; base64url and DER

(defn- unb64 ^bytes [^String s] (.decode (Base64/getUrlDecoder) s))
(defn b64
  "bytes as unpadded base64url."
  [^bytes b]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))

(defn- der-length [n]
  (if (< n 128)
    [n]
    (let [bs (loop [n n acc ()] (if (zero? n) acc (recur (quot n 256) (conj acc (bit-and n 255)))))]
      (into [(bit-or 0x80 (count bs))] bs))))

(defn- der [tag ^bytes content]
  (byte-array (concat [tag] (der-length (alength content)) content)))

(defn- der-unsigned-int
  "A DER INTEGER for the unsigned big-endian bytes b."
  [^bytes b]
  (let [b (drop-while zero? b)
        b (if (or (empty? b) (bit-test (first b) 7)) (cons 0 b) b)]
    (der 0x02 (byte-array (map unchecked-byte b)))))

(defn- concat-bytes ^bytes [& arrays] (byte-array (mapcat seq arrays)))

(def ^:private rsa-algorithm-id
  ;; SEQUENCE { OID 1.2.840.113549.1.1.1, NULL }
  (byte-array (map unchecked-byte [0x30 0x0d 0x06 0x09 0x2a 0x86 0x48 0x86 0xf7 0x0d 0x01 0x01 0x01 0x05 0x00])))

(def ^:private p256-spki-prefix
  ;; SEQUENCE { SEQUENCE { OID ecPublicKey, OID prime256v1 }, BIT STRING header }
  (byte-array (map unchecked-byte [0x30 0x59 0x30 0x13 0x06 0x07 0x2a 0x86 0x48 0xce 0x3d 0x02 0x01
                                   0x06 0x08 0x2a 0x86 0x48 0xce 0x3d 0x03 0x01 0x07 0x03 0x42 0x00])))

(defn- rsa-spki [^bytes n ^bytes e]
  (let [rsa-key (der 0x30 (concat-bytes (der-unsigned-int n) (der-unsigned-int e)))
        bit-string (der 0x03 (concat-bytes (byte-array [0]) rsa-key))]
    (der 0x30 (concat-bytes rsa-algorithm-id bit-string))))

(defn- left-pad ^bytes [^bytes b n]
  (let [b (drop-while zero? b)]
    (byte-array (concat (repeat (- n (count b)) 0) b))))

(defn- p256-spki [^bytes x ^bytes y]
  (concat-bytes p256-spki-prefix (byte-array [4]) (left-pad x 32) (left-pad y 32)))

(defn jwk->public-key
  "A java.security.PublicKey from an RSA or P-256 EC JWK (a map with string
  keys), or nil for another key type."
  [{:strs [kty n e crv x y]}]
  (case kty
    "RSA" (.generatePublic (KeyFactory/getInstance "RSA") (X509EncodedKeySpec. (rsa-spki (unb64 n) (unb64 e))))
    "EC" (when (= "P-256" crv)
           (.generatePublic (KeyFactory/getInstance "EC") (X509EncodedKeySpec. (p256-spki (unb64 x) (unb64 y)))))
    nil))

(defn- der-read
  "[tag content-bytes rest-bytes] of the DER element at the start of b."
  [^bytes b]
  (let [tag (bit-and (aget b 0) 0xff)
        l0 (bit-and (aget b 1) 0xff)
        [len header] (if (< l0 128)
                       [l0 2]
                       (let [k (bit-and l0 0x7f)]
                         [(reduce (fn [acc i] (+ (* acc 256) (bit-and (aget b (+ 2 i)) 0xff))) 0 (range k))
                          (+ 2 k)]))]
    [tag
     (java.util.Arrays/copyOfRange b (int header) (int (+ header len)))
     (java.util.Arrays/copyOfRange b (int (+ header len)) (alength b))]))

(defn public-key->jwk
  "The JWK (string keys) of an RSA or P-256 EC public key, from its encoded
  SubjectPublicKeyInfo. Used to publish keys and in tests."
  [k]
  (let [[_ spki] (der-read (.getEncoded k))
        [_ _alg-id bit-string-and-rest] (der-read spki)
        [_ bit-string] (der-read bit-string-and-rest)
        key-bytes (java.util.Arrays/copyOfRange bit-string 1 (alength bit-string))
        strip #(byte-array (drop-while zero? %))]
    (case (.getAlgorithm k)
      "RSA" (let [[_ seq-content] (der-read key-bytes)
                  [_ n more] (der-read seq-content)
                  [_ e] (der-read more)]
              {"kty" "RSA" "n" (b64 (strip n)) "e" (b64 (strip e))})
      "EC" (do (when-not (= 65 (alength key-bytes))
                 (throw (ex-info "Only uncompressed P-256 keys are supported" {:kind :invalid-input})))
               {"kty" "EC" "crv" "P-256"
                "x" (b64 (java.util.Arrays/copyOfRange key-bytes 1 33))
                "y" (b64 (java.util.Arrays/copyOfRange key-bytes 33 65))}))))

;; Signatures

(def ^:private algorithms
  {"RS256" "SHA256withRSA"
   "ES256" "SHA256withECDSAinP1363Format"})

(defn- verify-signature [alg key ^bytes signing-input ^bytes signature]
  (case alg
    "HS256" (let [mac (Mac/getInstance "HmacSHA256")]
              (.init mac (SecretKeySpec. ^bytes key "HmacSHA256"))
              (MessageDigest/isEqual (.doFinal mac signing-input) signature))
    ("RS256" "ES256") (try
                        (let [s (Signature/getInstance (algorithms alg))]
                          (.initVerify s key)
                          (.update s signing-input)
                          (.verify s signature))
                        ;; a signature of the wrong length or shape throws
                        (catch Exception _ false))
    false))

(defn sign
  "A compact JWS of claims, for tests, servers that mint tokens, and DPoP
  proofs. key is a private key (RS256, ES256) or secret bytes (HS256).
  :typ replaces the default \"at+jwt\", and :header adds header entries."
  [claims {:keys [alg kid key typ header]}]
  (let [header (cond-> (merge {"alg" alg "typ" (or typ "at+jwt")} header) kid (assoc "kid" kid))
        enc #(b64 (.getBytes (json/write-str %) "UTF-8"))
        input (str (enc header) "." (enc claims))
        sig (case alg
              "HS256" (let [mac (Mac/getInstance "HmacSHA256")]
                        (.init mac (SecretKeySpec. ^bytes key "HmacSHA256"))
                        (.doFinal mac (.getBytes input "UTF-8")))
              (let [s (Signature/getInstance (algorithms alg))]
                (.initSign s key)
                (.update s (.getBytes input "UTF-8"))
                (.sign s)))]
    (str input "." (b64 sig))))

;; JWKS

(defn- fetch-jwks [url]
  (let [client (-> (HttpClient/newBuilder) (.connectTimeout (Duration/ofSeconds 10)) (.build))
        resp (.send client (-> (HttpRequest/newBuilder (URI. url))
                               (.header "accept" "application/json")
                               (.timeout (Duration/ofSeconds 10))
                               (.GET) (.build))
                    (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode resp))
      (throw (ex-info (str "JWKS fetch returned " (.statusCode resp)) {:kind :jwks :status (.statusCode resp)})))
    (get (json/read-str (.body resp)) "keys")))

(defn- jwks-source
  "A function of kid that returns the public key, fetching url at first use
  and again for an unknown kid, at most once per refresh-ms."
  [url refresh-ms]
  (let [cache (atom {:keys {} :fetched 0})
        refresh! (fn []
                   (let [ks (fetch-jwks url)]
                     (reset! cache {:fetched (System/currentTimeMillis)
                                    :keys (into {} (keep (fn [jwk]
                                                           (when-let [k (try (jwk->public-key jwk) (catch Exception _ nil))]
                                                             [(get jwk "kid") {:key k :alg (get jwk "alg")}])))
                                                ks)})))]
    (fn [kid]
      (or (get-in @cache [:keys kid])
          (when (> (- (System/currentTimeMillis) (:fetched @cache)) refresh-ms)
            (try (refresh!) (catch Exception _ nil))
            (get-in @cache [:keys kid]))))))

;; The verifier

(defn- audiences [aud] (cond (string? aud) #{aud} (sequential? aud) (set aud) :else #{}))

(defn- scopes [{:strs [scope scp]}]
  (cond (string? scope) (vec (remove str/blank? (str/split scope #"\s+")))
        (sequential? scp) (vec scp)
        :else []))

(defn verifier
  "A :verify function for presa.auth/resource-server. Options:

  - :audience  the accepted aud values, a string or a collection (required:
               the resource's canonical URI)
  - :issuer    the expected iss (recommended)
  - :jwks-url  a JWKS document of RS256 or ES256 keys, fetched at first use
               and again for an unknown kid at most once per :refresh-ms
               (default 60000)
  - :keys      static keys instead: a map of kid to a JWK map or a
               java.security.PublicKey (a nil kid matches tokens without one)
  - :secret    an HS256 shared secret (string or bytes)
  - :leeway-s  clock skew allowed for exp and nbf, in seconds (default 0)
  - :require-exp?  refuse a token without a numeric exp (default true; RFC
               9068 requires exp in access tokens)

  The function returns {:subject :client-id :scopes :claims} for a valid
  token, or {:error kind :description text} with kind :invalid-token,
  :expired, :wrong-issuer or :wrong-audience."
  [{:keys [audience issuer jwks-url keys secret leeway-s refresh-ms require-exp?]
    :or {leeway-s 0 refresh-ms 60000 require-exp? true}}]
  (when-not audience
    (throw (ex-info "A JWT verifier needs :audience" {:kind :invalid-input})))
  (let [accepted (audiences (if (string? audience) [audience] (vec audience)))
        static (into {} (map (fn [[kid k]] [kid {:key (if (map? k) (jwk->public-key k) k)
                                                 :alg (when (map? k) (get k "alg"))}]))
                     keys)
        from-jwks (when jwks-url (jwks-source jwks-url refresh-ms))
        secret (when secret (if (bytes? secret) secret (.getBytes (str secret) "UTF-8")))
        refuse (fn [kind description] {:error kind :description description})]
    (fn [token]
      (let [parts (str/split (str token) #"\." -1)]
        (if-not (= 3 (count parts))
          (refuse :invalid-token "The token is not a compact JWS")
          (let [[h p s] parts
                header (try (json/read-str (String. (unb64 h) "UTF-8")) (catch Exception _ nil))
                claims (try (json/read-str (String. (unb64 p) "UTF-8")) (catch Exception _ nil))
                signature (try (unb64 s) (catch Exception _ nil))
                alg (get header "alg")
                kid (get header "kid")
                key (case alg
                      "HS256" secret
                      ("RS256" "ES256") (:key (or (get static kid) (when from-jwks (from-jwks kid))))
                      nil)
                now (quot (System/currentTimeMillis) 1000)]
            (cond
              (not (and (map? header) (map? claims) signature)) (refuse :invalid-token "The token is malformed")
              (nil? key) (refuse :invalid-token "No key verifies this token")
              (not (verify-signature alg key (.getBytes (str h "." p) "UTF-8") signature))
              (refuse :invalid-token "The token signature is invalid")
              (and require-exp? (not (number? (get claims "exp")))) (refuse :invalid-token "The token has no exp")
              (and (number? (get claims "exp")) (> now (+ (get claims "exp") leeway-s))) (refuse :expired "The token has expired")
              (and (number? (get claims "nbf")) (< (+ now leeway-s) (get claims "nbf"))) (refuse :invalid-token "The token is not yet valid")
              (and issuer (not= issuer (get claims "iss"))) (refuse :wrong-issuer "The token is from another issuer")
              (empty? (set/intersection accepted (audiences (get claims "aud"))))
              (refuse :wrong-audience "The token is not for this resource")
              :else {:subject (get claims "sub")
                     :client-id (get claims "client_id")
                     :scopes (scopes claims)
                     :claims claims})))))))
