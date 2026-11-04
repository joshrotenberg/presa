(ns presa.example.hello-test
  (:require [clojure.test :refer [deftest is]]
            [presa.example.hello :as hello]
            [presa.jsonrpc :as rpc]
            [presa.server :as server]))

(def meta-2026
  {"io.modelcontextprotocol/protocolVersion" "2026-07-28"
   "io.modelcontextprotocol/clientCapabilities" {}})

(defn- call [name arguments]
  (-> (server/handle hello/hello {} (rpc/request 1 "tools/call" {"name" name "arguments" arguments "_meta" meta-2026}))
      second
      (get "result")))

(deftest tools
  (is (= "hello" (get-in (call "echo" {"text" "hello"}) ["content" 0 "text"])))
  (is (true? (get (call "echo" {"text" 1}) "isError")))
  (is (= {"sum" 6.5} (get (call "add" {"numbers" [1 2 3.5]}) "structuredContent")))
  (is (= {"sum" 0} (get (call "add" {"numbers" []}) "structuredContent")))
  (is (true? (get (call "add" {"numbers" ["x"]}) "isError"))))
