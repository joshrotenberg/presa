(ns presa.schema.networknt-test
  "JVM only: presa.schema.networknt needs com.networknt/json-schema-validator."
  (:require [clojure.test :refer [deftest is testing]]
            [presa.jsonrpc :as rpc]
            [presa.schema.networknt :as nt]
            [presa.server :as server]))

(def schema
  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   "type" "object"
   "$defs" {"address" {"$anchor" "addr" "type" "object" "properties" {"street" {"type" "string"}}}}
   "properties" {"name" {"type" "string"}
                 "address" {"$ref" "#/$defs/address"}
                 "contactMethod" {"type" "string" "enum" ["phone" "email"]}
                 "phone" {"type" "string"}
                 "email" {"type" "string"}}
   "allOf" [{"anyOf" [{"required" ["phone"]} {"required" ["email"]}]}]
   "if" {"properties" {"contactMethod" {"const" "phone"}} "required" ["contactMethod"]}
   "then" {"required" ["phone"]}
   "else" {"required" ["email"]}
   "additionalProperties" false})

(deftest full-validation
  (let [v (nt/validator)]
    (is (nil? (v {"name" "a" "email" "a@b"} schema)))
    (is (some? (v {"name" "a"} schema)) "allOf/anyOf and else need email")
    (is (some? (v {"contactMethod" "phone" "email" "x"} schema)) "if/then needs phone")
    (is (= ["address" "street"] (:path (v {"email" "x" "address" {"street" 5}} schema))) "$ref is followed")
    (is (= "additionalProperties" (:keyword (v {"email" "x" "zz" 1} schema))))
    (testing "remote references are not fetched"
      (is (= "schema" (:keyword (v {"x" 1} {"$ref" "http://127.0.0.1:1/never.json"})))))))

(deftest as-the-server-validator
  (let [srv (server/server {:name "t" :version "1" :validator (nt/validator)
                            :tools [{:name "contact" :input-schema schema :handler (fn [_ _] "ok")}]})
        call (fn [args] (get (second (server/handle srv {} (rpc/request 1 "tools/call"
                                                                        {"name" "contact" "arguments" args
                                                                         "_meta" {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
                                                                                  "io.modelcontextprotocol/clientCapabilities" {}}})))
                             "result"))]
    (is (= "ok" (get-in (call {"email" "a@b"}) ["content" 0 "text"])))
    (is (true? (get (call {"name" "no contact"}) "isError")))))
