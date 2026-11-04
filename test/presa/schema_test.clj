(ns presa.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [presa.jsonrpc :as rpc]
            [presa.schema :as schema]
            [presa.server :as server]))

(defn- failure [v s] (some-> (schema/validate v s) (select-keys [:path :keyword])))

(deftest validation
  (testing "types"
    (is (nil? (schema/validate "x" {"type" "string"})))
    (is (nil? (schema/validate 1.0 {"type" "integer"})))
    (is (nil? (schema/validate nil {"type" ["string" "null"]})))
    (is (= {:path [] :keyword "type"} (failure 1.5 {"type" "integer"})))
    (is (= {:path [] :keyword "type"} (failure [] {"type" "object"})))
    (is (= "expected string or null, got integer" (:message (schema/validate 1 {"type" ["string" "null"]})))))
  (testing "enum and const"
    (is (nil? (schema/validate 2.0 {"enum" [1 2]})))
    (is (= {:path [] :keyword "enum"} (failure 3 {"enum" [1 2]})))
    (is (= {:path [] :keyword "const"} (failure {"a" 1} {"const" {"a" 2}}))))
  (testing "strings"
    (is (= {:path [] :keyword "minLength"} (failure "ab" {"minLength" 3})))
    (is (nil? (schema/validate "😀" {"maxLength" 1})))
    (is (= {:path [] :keyword "pattern"} (failure "abc" {"pattern" "^\\d+$"}))))
  (testing "numbers"
    (is (= {:path [] :keyword "minimum"} (failure 0 {"minimum" 1})))
    (is (= {:path [] :keyword "exclusiveMaximum"} (failure 5 {"exclusiveMaximum" 5})))
    (is (nil? (schema/validate 0.3 {"multipleOf" 0.1})))
    (is (= {:path [] :keyword "multipleOf"} (failure 7 {"multipleOf" 2}))))
  (testing "arrays"
    (is (= {:path [1] :keyword "type"} (failure [1 "x"] {"items" {"type" "number"}})))
    (is (= {:path [] :keyword "uniqueItems"} (failure [1 1.0] {"uniqueItems" true})))
    (is (= {:path [] :keyword "maxItems"} (failure [1 2] {"maxItems" 1}))))
  (testing "objects"
    (let [s {"type" "object"
             "properties" {"name" {"type" "string"} "tags" {"type" "array" "items" {"type" "string"}}}
             "required" ["name"]
             "additionalProperties" false}]
      (is (nil? (schema/validate {"name" "a" "tags" ["x"]} s)))
      (is (= {:path [] :keyword "required"} (failure {} s)))
      (is (= {:path ["tags" 0] :keyword "type"} (failure {"name" "a" "tags" [1]} s)))
      (is (= {:path ["extra"] :keyword "additionalProperties"} (failure {"name" "a" "extra" 1} s)))
      (is (= "at /tags/0: expected string, got integer" (schema/describe (schema/validate {"name" "a" "tags" [1]} s))))))
  (testing "boolean schemas and unknown keywords"
    (is (nil? (schema/validate 1 true)))
    (is (= {:path [] :keyword "false"} (failure 1 false)))
    (is (nil? (schema/validate 1 {"$ref" "#/defs/x" "anyOf" [{"type" "string"}]})))))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}})

(deftest tools-are-validated
  (let [srv (server/server {:name "t" :version "1"
                            :tools [{:name "add"
                                     :input-schema {"type" "object"
                                                    "properties" {"numbers" {"type" "array" "items" {"type" "number"}}}
                                                    "required" ["numbers"]}
                                     :output-schema {"type" "object" "properties" {"sum" {"type" "number"}} "required" ["sum"]}
                                     :handler (fn [{:strs [numbers]} _] {"sum" (reduce + numbers)})}
                                    {:name "liar"
                                     :output-schema {"type" "object" "required" ["sum"]}
                                     :handler (fn [_ _] {"total" 1})}
                                    {:name "texty"
                                     :output-schema {"type" "object"}
                                     :handler (fn [_ _] "just text")}]})
        call (fn [s name args]
               (second (server/handle s {} (rpc/request 1 "tools/call" {"name" name "arguments" args "_meta" meta-2026}))))]
    (is (= {"sum" 3} (get-in (call srv "add" {"numbers" [1 2]}) ["result" "structuredContent"])))
    (is (= {"content" [{"type" "text" "text" "Invalid arguments at /numbers/1: expected number, got string"}] "isError" true}
           (select-keys (get (call srv "add" {"numbers" [1 "2"]}) "result") ["content" "isError"])))
    (is (= "Invalid arguments missing required property numbers"
           (get-in (call srv "add" {}) ["result" "content" 0 "text"])))
    (is (= -32603 (get-in (call srv "liar" {}) ["error" "code"])))
    (is (= -32603 (get-in (call srv "texty" {}) ["error" "code"])))
    (testing "validation can be turned off"
      (let [loose (server/server {:name "t" :version "1" :validate? false
                                  :tools [{:name "liar" :output-schema {"type" "object" "required" ["sum"]}
                                           :input-schema {"type" "object" "required" ["x"]}
                                           :handler (fn [_ _] {"total" 1})}]})]
        (is (= {"total" 1} (get-in (call loose "liar" {}) ["result" "structuredContent"])))))))
