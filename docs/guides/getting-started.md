# Getting started

presa builds MCP servers and clients in Clojure, for Babashka and the JVM
from the same source. This guide writes a server with one tool, calls it
from a presa client in the same process, serves it over stdio, and
registers it with Claude Code and Codex.

## Depend on presa

presa is released as git tags; see [RELEASING.md](../../RELEASING.md) for
the coordinates. In `bb.edn` or `deps.edn`:

<!-- no-run -->
```clojure
{:deps {io.github.joshrotenberg/presa {:git/tag "v0.1.0" :git/sha "<the tag's sha>"}}}
```

## A server

A server is a map built from a name, a version and definitions. A tool is a
map with a name, a description, an input schema and a handler, a function
of the call's arguments (a map with string keys) and a context map.

```clojure
(require '[presa.protocol :as protocol]
         '[presa.server :as server])

(def srv
  (server/server
   {:name "greeter"
    :version "0.1.0"
    ;; the default is 2026-07-28 alone; the initialize-era revisions let
    ;; Codex and other older clients connect too
    :versions protocol/versions
    :tools [{:name "greet"
             :description "Greet someone by name."
             :input-schema {"type" "object"
                            "properties" {"name" {"type" "string"}}
                            "required" ["name"]}
             :handler (fn [{:strs [name]} _context]
                        (str "Hello, " name "!"))}]}))
```

## Call it in process

`presa.client/in-process` connects a client to a server map without a
transport, which is the quickest way to try a server and how presa's tests
drive servers.

```clojure
(require '[presa.client :as client])

(def c (client/connect (client/in-process srv)))

(:version (client/info c))
;; => "2026-07-28"

(client/text (client/call-tool c "greet" {"name" "Ada"}))
;; => "Hello, Ada!"

(client/close c)
```

## Serve it over stdio

Hosts such as Claude Code and Codex start an MCP server as a subprocess and
speak newline-delimited JSON-RPC on its stdin and stdout. `stdio/serve!`
does that until stdin closes:

<!-- no-run -->
```clojure
(ns greeter.main
  (:require [presa.transport.stdio :as stdio]))

(defn -main [& _]
  (stdio/serve! srv)
  (shutdown-agents))
```

stdout carries only protocol messages: handlers run with `*out*` bound to
`*err*`, so a stray `println` cannot break the stream.

## Register it with a host

With the server's namespace on a classpath that `bb.edn` sets up, Claude
Code:

```sh
claude mcp add greeter -- bb --config /path/to/project/bb.edn -m greeter.main
```

Codex:

```sh
codex mcp add greeter -- bb --config /path/to/project/bb.edn -m greeter.main
```

Claude Code speaks 2026-07-28 and Codex speaks 2025-06-18; serving
`protocol/versions` answers both. [hosts.md](../hosts.md) records what each
sends.

## Next

- [Tools, resources and prompts](components.md)
- [The client](client.md)
- [Transports](transports.md)
