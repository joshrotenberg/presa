(ns presa.test-runner
  "Runs every test namespace and exits with a failing status when any test
  fails, under Babashka (bb test) and on the JVM (clojure -M:test)."
  (:require [clojure.test :as test]
            [presa.example.hello-test]
            [presa.jsonrpc-test]
            [presa.protocol-test]
            [presa.server-test]
            [presa.transport.stdio-test]))

(def namespaces
  '[presa.protocol-test
    presa.jsonrpc-test
    presa.server-test
    presa.transport.stdio-test
    presa.example.hello-test])

(defn -main [& _]
  (let [{:keys [fail error]} (apply test/run-tests namespaces)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
