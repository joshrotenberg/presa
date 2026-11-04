(ns presa.test-runner
  "Runs every test namespace and exits with a failing status when any test
  fails, under Babashka (bb test) and on the JVM (clojure -M:test)."
  (:require [clojure.test :as test]
            [presa.catalog-test]
            [presa.client-test]
            [presa.content-test]
            [presa.example.hello-test]
            [presa.input-test]
            [presa.jsonrpc-test]
            [presa.protocol-test]
            [presa.schema-test]
            [presa.server-surface-test]
            [presa.server-test]
            [presa.subscriptions-test]
            [presa.transport.http-test]
            [presa.transport.stdio-test]))

(def namespaces
  '[presa.protocol-test
    presa.jsonrpc-test
    presa.content-test
    presa.server-test
    presa.server-surface-test
    presa.schema-test
    presa.transport.stdio-test
    presa.transport.http-test
    presa.client-test
    presa.input-test
    presa.subscriptions-test
    presa.catalog-test
    presa.example.hello-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply test/run-tests namespaces)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
