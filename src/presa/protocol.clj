(ns presa.protocol
  "MCP protocol revisions and JSON-RPC error codes as data.")

(def latest-version
  "The revision presa implements first: stateless, with no initialize
  handshake."
  "2026-07-28")

(def legacy-versions
  "Initialize-era revisions presa serves as an opt-in, newest first."
  ["2025-11-25" "2025-06-18"])

(def versions
  "Every revision presa knows, newest first."
  (into [latest-version] legacy-versions))

(defn known-version?
  "True when version is a revision presa knows."
  [version]
  (boolean (some #{version} versions)))

(defn stateless?
  "True for a revision without the initialize handshake, where each request
  carries its protocol version and client capabilities in _meta."
  [version]
  (= latest-version version))

(def error-codes
  "The JSON-RPC 2.0 error codes, and the MCP ones presa uses, by name."
  {:parse-error -32700
   :invalid-request -32600
   :method-not-found -32601
   :invalid-params -32602
   :internal-error -32603
   :unsupported-protocol-version -32022})

(def meta-keys
  "The request and result _meta keys of the stateless revision."
  {:protocol-version "io.modelcontextprotocol/protocolVersion"
   :client-info "io.modelcontextprotocol/clientInfo"
   :client-capabilities "io.modelcontextprotocol/clientCapabilities"
   :server-info "io.modelcontextprotocol/serverInfo"})
