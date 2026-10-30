(ns presa.jsonrpc-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [presa.jsonrpc :as rpc]))

(deftest messages
  (is (= {"jsonrpc" "2.0" "id" 1 "method" "tools/list"} (rpc/request 1 "tools/list")))
  (is (= {"jsonrpc" "2.0" "id" "a" "method" "tools/call" "params" {"name" "x"}}
         (rpc/request "a" "tools/call" {"name" "x"})))
  (is (= {"jsonrpc" "2.0" "method" "notifications/cancelled" "params" {"requestId" 1}}
         (rpc/notification "notifications/cancelled" {"requestId" 1})))
  (is (= {"jsonrpc" "2.0" "id" 1 "result" {}} (rpc/result 1 {})))
  (testing "errors take a code by name or by number"
    (is (= {"jsonrpc" "2.0" "id" 1 "error" {"code" -32601 "message" "no such method"}}
           (rpc/error 1 :method-not-found "no such method")))
    (is (= {"jsonrpc" "2.0" "id" nil "error" {"code" -32022 "message" "unsupported" "data" {"supported" ["2026-07-28"]}}}
           (rpc/error nil -32022 "unsupported" {"supported" ["2026-07-28"]})))
    (is (= :invalid-input (:kind (ex-data (try (rpc/error 1 :nope "x") (catch Exception e e))))))))

(deftest kinds
  (is (= :request (rpc/kind (rpc/request 1 "m"))))
  (is (= :notification (rpc/kind (rpc/notification "m"))))
  (is (= :response (rpc/kind (rpc/result 1 {}))))
  (is (= :error (rpc/kind (rpc/error 1 :internal-error "x"))))
  (is (= :invalid (rpc/kind {"id" 1 "method" "m"})))
  (is (= :invalid (rpc/kind {"jsonrpc" "2.0" "id" 1 "method" 7})))
  (is (= :invalid (rpc/kind {"jsonrpc" "2.0" "id" 1 "result" {} "error" {}})))
  (is (= :invalid (rpc/kind "not a map"))))

(deftest framing
  (testing "a message is one line, whatever its strings hold"
    (let [line (rpc/write-line (rpc/result 1 {"text" "a\nb\r\nc/d é"}))]
      (is (str/ends-with? line "\n"))
      (is (= 1 (count (re-seq #"\n" line))))
      (is (= {"jsonrpc" "2.0" "id" 1 "result" {"text" "a\nb\r\nc/d é"}}
             (rpc/parse-line (subs line 0 (dec (count line))))))))
  (testing "parse errors"
    (doseq [line ["" "   " "{" "not json" "1 2" "{\"a\":1} x"]]
      (is (= :parse-error (:kind (ex-data (try (rpc/parse-line line) (catch Exception e e)))))
          (pr-str line))))
  (testing "trailing whitespace is allowed"
    (is (= {"a" 1} (rpc/parse-line "{\"a\":1}  ")))))
