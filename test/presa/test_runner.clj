(ns presa.test-runner
  "Runs every test namespace and exits with a failing status when any test
  fails, under Babashka (bb test) and on the JVM (clojure -M:test)."
  (:require [clojure.test :as test]
            [presa.auth-client-test]
            [presa.auth-dpop-test]
            [presa.auth-grants-test]
            [presa.auth-test]
            [presa.authoring-test]
            [presa.bb-test]
            [presa.catalog-test]
            [presa.client-cache-test]
            [presa.client-test]
            [presa.content-test]
            [presa.example.hello-test]
            [presa.example.notebook-test]
            [presa.example.clojars-test]
            [presa.guides-test]
            [presa.input-test]
            [presa.jsonrpc-test]
            [presa.limits-test]
            [presa.part-test]
            [presa.protocol-test]
            [presa.proxy-test]
            [presa.schema-test]
            [presa.scopes-test]
            [presa.server-surface-test]
            [presa.server-test]
            [presa.subscriptions-test]
            [presa.tasks-test]
            [presa.transport.http-test]
            [presa.transport.stdio-test]))

(def namespaces
  '[presa.protocol-test
    presa.jsonrpc-test
    presa.content-test
    presa.server-test
    presa.server-surface-test
    presa.authoring-test
    presa.part-test
    presa.bb-test
    presa.schema-test
    presa.transport.stdio-test
    presa.transport.http-test
    presa.client-test
    presa.client-cache-test
    presa.input-test
    presa.subscriptions-test
    presa.catalog-test
    presa.limits-test
    presa.auth-test
    presa.auth-client-test
    presa.auth-dpop-test
    presa.auth-grants-test
    presa.scopes-test
    presa.tasks-test
    presa.proxy-test
    presa.example.hello-test
    presa.example.notebook-test
    presa.example.clojars-test
    presa.guides-test])

(def jvm-namespaces
  "Tests of namespaces that load only on the JVM (test-jvm/)."
  '[presa.schema.networknt-test
    presa.wire-schema-test])

(defn -main [& _]
  (let [jvm? (nil? (System/getProperty "babashka.version"))
        _ (when jvm? (run! require jvm-namespaces))
        {:keys [fail error]} (apply test/run-tests (cond-> namespaces jvm? (into jvm-namespaces)))]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
