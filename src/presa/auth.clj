(ns presa.auth
  "The OAuth 2.1 resource server side of MCP authorization, for
  presa.transport.http: an MCP server over HTTP publishes protected resource
  metadata (RFC 9728), requires a bearer token on every request (RFC 6750),
  and answers a missing or invalid token with a WWW-Authenticate challenge
  naming that metadata, so a client can find the authorization server.

  Token checking is a function (see presa.auth.jwt/verifier, or bring your
  own: introspection, opaque tokens). The verified identity reaches handlers
  as (:auth context): {:subject :client-id :scopes :claims}; the raw token
  never does. Follows snodo_oauth's resource server."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import (java.net URI)))

(def ^:private well-known "/.well-known/oauth-protected-resource")

(defn canonical-resource
  "resource in canonical form: an absolute http or https URI with a host and
  no fragment, its scheme and host lowercased and a lone trailing slash
  removed. Throws ex-info with :kind :invalid-input otherwise."
  [resource]
  (let [u (try (URI. (str resource)) (catch Exception _ nil))
        scheme (some-> u .getScheme str/lower-case)]
    (when-not (and u (#{"http" "https"} scheme) (seq (.getHost u)) (nil? (.getFragment u)))
      (throw (ex-info (str "The resource must be an absolute http or https URI with a host: " resource)
                      {:kind :invalid-input :resource resource})))
    (let [path (or (.getRawPath u) "")
          path (if (= "/" path) "" path)]
      (str scheme "://" (str/lower-case (.getHost u))
           (when (not= -1 (.getPort u)) (str ":" (.getPort u)))
           path
           (when (.getRawQuery u) (str "?" (.getRawQuery u)))))))

(defn metadata-path
  "The path that serves the metadata of resource: the well-known prefix,
  then the resource's path (RFC 9728 section 3.1)."
  [resource]
  (let [path (.getRawPath (URI. resource))]
    (str well-known (when-not (str/blank? path) path))))

(defn metadata-url [resource]
  (let [u (URI. resource)]
    (str (.getScheme u) "://" (.getRawAuthority u) (metadata-path resource))))

(defn resource-server
  "A resource-server config for presa.transport.http's :auth option:

  - :resource               the canonical URI clients name as the RFC 8707
                            resource, such as \"https://mcp.example.com/mcp\"
                            (required)
  - :verify                 (fn [token]) -> {:subject :client-id :scopes :claims}
                            for a valid token, or {:error kind :description s}
                            (kind :invalid-token, :expired, :wrong-issuer,
                            :wrong-audience) or nil (required)
  - :authorization-servers  issuer URLs, at least one (required)
  - :scopes-supported       scopes clients may request
  - :required-scopes        scopes every request needs; named on every
                            challenge so clients know what to ask for
  - :resource-name          a human-readable name for the metadata
  - :realm                  the realm parameter of challenges"
  [{:keys [resource verify authorization-servers scopes-supported required-scopes resource-name realm]}]
  (when-not (ifn? verify)
    (throw (ex-info "A resource server needs a :verify function" {:kind :invalid-input})))
  (when (empty? authorization-servers)
    (throw (ex-info "A resource server needs :authorization-servers" {:kind :invalid-input})))
  (let [resource (canonical-resource resource)]
    {:resource resource
     :verify verify
     :required-scopes (vec required-scopes)
     :realm realm
     :metadata-path (metadata-path resource)
     :metadata-url (metadata-url resource)
     :metadata (cond-> {"resource" resource
                        "authorization_servers" (vec authorization-servers)
                        "bearer_methods_supported" ["header"]}
                 (seq scopes-supported) (assoc "scopes_supported" (vec scopes-supported))
                 resource-name (assoc "resource_name" resource-name))}))

;; Challenges

(defn- quoted-safe
  "A value safe inside an RFC 6750 quoted string: %x20-21 / %x23-5B / %x5D-7E."
  [s]
  (apply str (filter #(let [c (int %)] (or (<= 0x20 c 0x21) (<= 0x23 c 0x5B) (<= 0x5D c 0x7E))) (str s))))

(defn challenge
  "A WWW-Authenticate value for config. params are the RFC 6750
  parameters: :error, :description, :scope (a sequence)."
  [config {:keys [error description scope]}]
  (let [scope (or (seq scope) (seq (:required-scopes config)))
        parts (cond-> []
                (:realm config) (conj (str "realm=\"" (quoted-safe (:realm config)) "\""))
                error (conj (str "error=\"" (quoted-safe error) "\""))
                description (conj (str "error_description=\"" (quoted-safe description) "\""))
                scope (conj (str "scope=\"" (quoted-safe (str/join " " scope)) "\""))
                true (conj (str "resource_metadata=\"" (quoted-safe (:metadata-url config)) "\"")))]
    (str "Bearer " (str/join ", " parts))))

(defn- refusal [config status params]
  {:status status
   :headers {"content-type" "application/json"
             "www-authenticate" (challenge config params)}
   :body (json/write-str (cond-> {"error" (or (:error params) "invalid_token")}
                           (:description params) (assoc "error_description" (:description params))))})

(def ^:private bearer-token #"(?i)^bearer[ \t]+([A-Za-z0-9\-._~+/]+=*)[ \t]*$")

(defn authenticate
  "Check a ring request against config: {:auth identity} when it carries a
  valid token with the required scopes, else {:response ring-response}."
  [config req]
  (let [header (get-in req [:headers "authorization"])]
    (cond
      (or (nil? header) (not (re-find #"(?i)^bearer([ \t]|$)" header)))
      {:response (assoc (refusal config 401 {}) :body (json/write-str {"error" "unauthorized"}))}

      (str/includes? header ",")
      {:response (refusal config 400 {:error "invalid_request" :description "One Authorization header, please"})}

      :else
      (if-let [[_ token] (re-matches bearer-token header)]
        (let [result (try ((:verify config) token) (catch Exception _ nil))]
          (cond
            (or (nil? result) (:error result))
            {:response (refusal config 401 {:error "invalid_token"
                                            :description (or (:description result) "The token is not valid")})}

            (seq (remove (set (:scopes result)) (:required-scopes config)))
            {:response (refusal config 403 {:error "insufficient_scope"
                                            :description "The token lacks a required scope"
                                            :scope (:required-scopes config)})}

            :else {:auth (select-keys result [:subject :client-id :scopes :claims])}))
        {:response (refusal config 400 {:error "invalid_request" :description "The Authorization header is malformed"})}))))

(defn metadata-response
  "The ring response for a request to the metadata path."
  [config req]
  (if (#{:get :head} (:request-method req))
    {:status 200
     :headers {"content-type" "application/json"}
     :body (if (= :head (:request-method req)) "" (json/write-str (:metadata config) :escape-slash false))}
    {:status 405 :headers {"allow" "GET, HEAD"} :body ""}))
