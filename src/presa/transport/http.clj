(ns presa.transport.http
  "MCP Streamable HTTP: a server over http-kit and a client transport over
  java.net.http, both bundled with Babashka.

  The server takes POSTs on one path. A 2026-07-28 request is independent:
  its version comes from _meta and must match the MCP-Protocol-Version
  header, and Mcp-Method (and Mcp-Name for tools/call, prompts/get and
  resources/read) must match the body. The response is JSON, or an event
  stream once the request sends progress or log notifications. An
  initialize-era client gets a session: initialize answers with an
  Mcp-Session-Id that later requests carry, and DELETE ends it.

  Origins other than loopback ones are refused (403) unless allowed, which
  guards a local server against DNS rebinding.

  The work is in presa.transport.http.server, .client and .headers; this
  namespace is the API."
  (:require [presa.transport.http.client :as client]
            [presa.transport.http.headers :as headers]
            [presa.transport.http.server :as server]))

(defmacro ^:private defalias
  "Define name as the var target names, with its docstring and arglists."
  [name target]
  (let [m (meta (resolve target))]
    `(def ~(cond-> (vary-meta name assoc :doc (:doc m))
             (:arglists m) (vary-meta assoc :arglists (list 'quote (:arglists m))))
       ~target)))

;; Header values

(defalias encode-header-value headers/encode-header-value)
(defalias decode-header-value headers/decode-header-value)
(defalias param-headers headers/param-headers)

;; Server

(defalias default-max-body-bytes server/default-max-body-bytes)
(defalias handler server/handler)
(defalias serve! server/serve!)

;; Client

(defalias url client/url)
