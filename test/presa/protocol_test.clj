(ns presa.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [presa.protocol :as protocol]))

(deftest revisions
  (testing "the latest revision comes first and is the stateless one"
    (is (= "2026-07-28" protocol/latest-version (first protocol/versions)))
    (is (protocol/stateless? "2026-07-28"))
    (is (not-any? protocol/stateless? protocol/legacy-versions)))
  (testing "known revisions"
    (is (every? protocol/known-version? protocol/versions))
    (is (not (protocol/known-version? "2024-11-05")))
    (is (not (protocol/known-version? nil)))))

(deftest error-codes
  (is (= -32700 (:parse-error protocol/error-codes)))
  (is (= -32602 (:invalid-params protocol/error-codes))))
