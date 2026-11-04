(ns ^:no-doc presa.server.access
  "Internal to presa.server; not part of the API. Scope checks for :required-
  scopes."
  (:require [clojure.string :as str]
            [presa.server.core :refer [error]]))

(defn match-template
  "The template of srv that matches uri, or nil."
  [srv uri]
  (some (fn [t] (when ((:presa.server/match t) uri) t)) (:templates srv)))

(defn required-scopes [srv kind k]
  (get (:required-scopes srv) [kind k] (:default-scopes srv)))

(defn permitted?
  "Whether the caller's granted scopes (from presa.auth) cover what the
  component needs. Without a scope policy every component needs nothing."
  [srv context kind k]
  (let [granted (set (get-in context [:auth :scopes]))]
    (every? granted (required-scopes srv kind k))))

(defn may-read?
  "Whether the caller of context may read uri: the scopes of the resource at
  uri, else of the template it matches, else the default scopes. srv is a
  snapshot."
  [srv context uri]
  (cond
    (contains? (:resources srv) uri) (permitted? srv context :resource uri)
    :else (if-let [t (match-template srv uri)]
            (permitted? srv context :resource-template (:uri-template t))
            (every? (set (get-in context [:auth :scopes])) (:default-scopes srv)))))

(defn check-permitted! [srv context kind k label]
  (when-not (permitted? srv context kind k)
    (throw (error :insufficient-scope (str "Insufficient scope for " label)
                  {"error" "insufficient_scope"
                   "scope" (str/join " " (required-scopes srv kind k))}))))
