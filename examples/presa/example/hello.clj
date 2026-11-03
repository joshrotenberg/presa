(ns presa.example.hello
  "A small MCP tools server over stdio, with two tools. It serves 2026-07-28
  and the initialize-era revisions, so Claude Code and Codex can both use
  it. Run it with

      bb --config /path/to/presa/bb.edn -m presa.example.hello"
  (:require [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.stdio :as stdio]))

(def tools
  [{:name "echo"
    :description "Return the given text unchanged."
    :input-schema {"type" "object"
                   "properties" {"text" {"type" "string" "description" "The text to echo"}}
                   "required" ["text"]}
    :handler (fn [{:strs [text]} _context]
               (if (string? text)
                 text
                 (server/tool-error "text must be a string")))}
   {:name "add"
    :description "Add a list of numbers and return the sum."
    :input-schema {"type" "object"
                   "properties" {"numbers" {"type" "array" "items" {"type" "number"}}}
                   "required" ["numbers"]}
    :output-schema {"type" "object"
                    "properties" {"sum" {"type" "number"}}
                    "required" ["sum"]}
    :handler (fn [{:strs [numbers]} _context]
               (if (and (sequential? numbers) (every? number? numbers))
                 {"sum" (reduce + 0 numbers)}
                 (server/tool-error "numbers must be an array of numbers")))}])

(def hello
  (server/server {:name "presa-hello"
                  :version "0.1.0"
                  :instructions "Example presa server with echo and add tools."
                  :versions protocol/versions
                  :tools tools}))

(defn -main [& _]
  (stdio/serve! hello)
  (shutdown-agents))
