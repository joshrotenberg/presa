(ns presa.request-state
  "Signed requestState tokens for multi round-trip requests. A token holds
  a JSON value the server chose, when it was issued and when it expires, and
  a scope bound to the request's method and params, all signed with
  HMAC-SHA256. A client can read a token but cannot change it, use it past
  its expiry, or use it on another tool or argument set. Tokens are not
  encrypted: put nothing secret in them.

  Format: presa1.<base64url payload>.<base64url signature>, where the
  payload is the JSON array [1, issuedAt, expiresAt, scope, data] with times
  in unix milliseconds."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def max-token-length
  "The longest requestState token verify accepts, in characters."
  16384)

(defn random-secret
  "32 random bytes."
  ^bytes []
  (let [b (byte-array 32)]
    (.nextBytes (SecureRandom.) b)
    b))

(defn secret-bytes
  "secret as bytes, checking it holds at least 32."
  ^bytes [secret]
  (let [b (if (bytes? secret) secret (.getBytes (str secret) "UTF-8"))]
    (when (< (alength ^bytes b) 32)
      (throw (ex-info "A request state secret needs at least 32 bytes" {:kind :invalid-input})))
    b))

(defn- hmac ^bytes [^bytes secret ^String s]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. secret "HmacSHA256"))
    (.doFinal mac (.getBytes s "UTF-8"))))

(defn- b64 [^bytes b] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))
(defn- unb64 ^bytes [^String s] (.decode (Base64/getUrlDecoder) s))

(defn- canonical
  "v with every map sorted by key, so equal values encode to equal JSON."
  [v]
  (cond
    (map? v) (into (sorted-map) (map (fn [[k x]] [(str k) (canonical x)])) v)
    (sequential? v) (mapv canonical v)
    :else v))

(defn scope
  "The scope of a request: a digest of its method, its params without
  _meta, requestState and inputResponses, and the caller (the authenticated
  subject, or nil)."
  ([secret method params] (scope secret method params nil))
  ([secret method params principal]
   (let [params (dissoc (or params {}) "_meta" "requestState" "inputResponses")]
     (b64 (hmac secret (str "presa-scope-v1:" (json/write-str (canonical [method params principal]))))))))

(defn sign
  "A token for data, scoped to method, params and principal, valid for
  ttl-ms."
  ([secret method params data ttl-ms now-ms] (sign secret method params data ttl-ms now-ms nil))
  ([secret method params data ttl-ms now-ms principal]
   (let [payload (b64 (.getBytes (json/write-str [1 now-ms (+ now-ms ttl-ms) (scope secret method params principal) data]
                                                 :escape-slash false)
                                 "UTF-8"))]
     (str "presa1." payload "." (b64 (hmac secret (str "presa-token-v1:" payload)))))))

(defn verify
  "The data in token when it is a valid, unexpired token for method, params
  and principal; else throws ex-info with :kind :invalid-state."
  ([secret method params token now-ms] (verify secret method params token now-ms nil))
  ([secret method params token now-ms principal]
   (let [invalid #(throw (ex-info "Invalid or expired request state" {:kind :invalid-state}))]
     (when-not (and (string? token) (<= (count token) max-token-length)) (invalid))
     (let [[prefix payload signature :as parts] (str/split token #"\." -1)]
       (when-not (and (= 3 (count parts)) (= "presa1" prefix)) (invalid))
       (let [expected (hmac secret (str "presa-token-v1:" payload))
             given (try (unb64 signature) (catch Exception _ (invalid)))]
         (when-not (MessageDigest/isEqual expected given) (invalid))
         (let [[version issued expires token-scope data :as decoded]
               (try (json/read-str (String. (unb64 payload) "UTF-8")) (catch Exception _ (invalid)))]
           (when-not (and (sequential? decoded) (= 1 version) (number? issued) (number? expires)) (invalid))
           (when (> now-ms expires) (invalid))
           (when-not (= token-scope (scope secret method params principal)) (invalid))
           data))))))
