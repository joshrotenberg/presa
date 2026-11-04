(ns presa.auth.dpop
  "DPoP (RFC 9449) for the OAuth client: an ES256 key pair, and the proof
  JWTs a client sends in the DPoP header of token requests and of requests
  to the resource. A proof is a JWS with typ dpop+jwt and the public key in
  its jwk header, and claims jti, htm, htu (the URL without query or
  fragment) and iat; for a resource request also ath, the base64url SHA-256
  of the access token; and nonce when the server asked for one."
  (:require [clojure.string :as str]
            [presa.auth.jwt :as jwt])
  (:import (java.net URI)
           (java.security KeyPairGenerator MessageDigest SecureRandom)))

(defn key-pair
  "A new P-256 key pair for ES256 proofs."
  []
  (.generateKeyPair (doto (KeyPairGenerator/getInstance "EC") (.initialize 256))))

(defn public-jwk
  "The public JWK of key-pair."
  [key-pair]
  (jwt/public-key->jwk (.getPublic ^java.security.KeyPair key-pair)))

(defn- b64 [^bytes b] (jwt/b64 b))

(defn htu
  "url without its query and fragment, as htu carries it."
  [url]
  (let [u (URI. (str url))]
    (str (.getScheme u) "://" (.getRawAuthority u) (.getRawPath u))))

(defn ath
  "The ath claim for access-token."
  [^String access-token]
  (b64 (.digest (MessageDigest/getInstance "SHA-256") (.getBytes access-token "US-ASCII"))))

(defn- jti []
  (let [b (byte-array 18)] (.nextBytes (SecureRandom.) b) (b64 b)))

(defn proof
  "A DPoP proof for an HTTP request: method (\"POST\", \"GET\"), url, and
  optionally :access-token (adds ath) and :nonce."
  [key-pair method url {:keys [access-token nonce]}]
  (jwt/sign (cond-> {"jti" (jti)
                     "htm" (str/upper-case (name method))
                     "htu" (htu url)
                     "iat" (quot (System/currentTimeMillis) 1000)}
              access-token (assoc "ath" (ath access-token))
              nonce (assoc "nonce" nonce))
            {:alg "ES256" :typ "dpop+jwt" :key (.getPrivate ^java.security.KeyPair key-pair)
             :header {"jwk" (public-jwk key-pair)}}))

(defn thumbprint
  "The JWK SHA-256 thumbprint (RFC 7638) of an EC P-256 public JWK, as a
  token's cnf.jkt carries it."
  [{:strs [crv x y]}]
  (b64 (.digest (MessageDigest/getInstance "SHA-256")
                (.getBytes (str "{\"crv\":\"" crv "\",\"kty\":\"EC\",\"x\":\"" x "\",\"y\":\"" y "\"}") "UTF-8"))))
