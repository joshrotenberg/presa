(ns presa.client-cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.input :as input]
            [presa.server :as server]))

(defn- counting-server [ttl-ms]
  (let [reads (atom 0)
        srv (server/server
             {:name "t" :version "1" :list-changed true :subscribe true :cache {:ttl-ms ttl-ms}
              :tools [{:name "x" :handler (fn [_ _] "x")}]
              :resources [{:uri "file:///a" :name "a" :handler (fn [_] (str "read " (swap! reads inc)))}
                          {:uri "file:///asked" :name "asked"
                           :handler (fn [ctx]
                                      (if (server/input-response ctx "ok")
                                        (str "asked " (swap! reads inc))
                                        (server/input-required {"ok" (input/elicit "ok?" {"type" "object"})})))}]})]
    [srv reads]))

(defn- text [c uri & [opts]] (get-in (client/read-resource c uri opts) [0 "text"]))

(deftest results-are-kept-for-their-ttl
  (let [[srv reads] (counting-server 60000)
        c (client/connect (client/in-process srv)
                          {:cache true :handlers {"elicitation/create" (fn [_] {"action" "accept" "content" {}})}})]
    (try
      (is (= "read 1" (text c "file:///a")))
      (is (= "read 1" (text c "file:///a")) "served from the cache")
      (is (= "read 2" (text c "file:///a" {:no-cache true})))
      (testing "a result that took input is not kept"
        (is (= "asked 3" (text c "file:///asked")))
        (is (= "asked 4" (text c "file:///asked"))))
      (testing "a resource update drops the cached read"
        (let [sub (client/listen c {"resourceSubscriptions" ["file:///a"]} (fn [_]))]
          (deref (:acknowledged sub) 2000 nil)
          (is (= "read 2" (text c "file:///a")))
          (server/resource-updated! srv "file:///a")
          ;; the invalidation arrives asynchronously: poll for the refetch
          (is (= "read 5" (loop [n 0]
                            (let [t (text c "file:///a")]
                              (if (and (= "read 2" t) (< n 500))
                                (do (Thread/sleep 10) (recur (inc n)))
                                t)))))
          ((:stop! sub))))
      (client/clear-cache! c)
      (is (= "read 6" (text c "file:///a")))
      (finally (client/close c)))))

(deftest nothing-is-kept-without-a-ttl-or-without-cache
  (let [[srv _] (counting-server 0)
        c (client/connect (client/in-process srv) {:cache true})]
    (try
      (is (= "read 1" (text c "file:///a")))
      (is (= "read 2" (text c "file:///a")))
      (finally (client/close c))))
  (let [[srv _] (counting-server 60000)
        c (client/connect (client/in-process srv))]
    (try
      (is (= "read 1" (text c "file:///a")))
      (is (= "read 2" (text c "file:///a")))
      (finally (client/close c)))))

(deftest expiry
  (let [ttl 300
        [srv _] (counting-server ttl)
        c (client/connect (client/in-process srv) {:cache true})]
    (try
      (let [start (System/currentTimeMillis)]
        (is (= "read 1" (text c "file:///a")))
        (is (= "read 1" (text c "file:///a")))
        ;; wait past the TTL, measured from before the first read
        (loop [n 0]
          (when (and (<= (System/currentTimeMillis) (+ start ttl 20)) (< n 1000))
            (Thread/sleep 10)
            (recur (inc n)))))
      (is (= "read 2" (text c "file:///a")))
      (finally (client/close c)))))
