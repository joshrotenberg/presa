(ns presa.proxy-test
  (:require [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.input :as input]
            [presa.protocol :as protocol]
            [presa.proxy :as proxy]
            [presa.server :as server]))

(defn- backend-a []
  (server/server
   {:name "a" :version "1" :list-changed true
    :tools [{:name "echo" :description "Echo" :handler (fn [{:strs [text]} _] text)}
            {:name "steps" :description "Reports progress"
             :handler (fn [_ ctx] (server/progress! ctx 1 {:total 2}) (server/progress! ctx 2 {:total 2}) "stepped")}
            {:name "ask" :description "Asks for a name"
             :handler (fn [_ ctx]
                        (if-let [r (server/input-response ctx "who")]
                          (str "hi " (get-in r ["content" "name"]))
                          (server/input-required {"who" (input/elicit "who?" {"type" "object"})} {"backend" "state"})))}]
    :resources [{:uri "a:/readme" :name "readme" :handler (fn [_] "A readme")}]
    :resource-templates [{:uri-template "a:/items/{id}" :name "item" :handler (fn [{:strs [id]} _] (str "item " id))}]
    :prompts [{:name "intro" :arguments [{:name "who" :required true}]
               :handler (fn [{:strs [who]} _] (str "introduce " who))
               :complete {"who" (fn [v _ _] (filter #(.startsWith ^String % v) ["ada" "alan"]))}}]}))

(defn- backend-b []
  (server/server {:name "b" :version "1"
                  :tools [{:name "echo" :description "Echo, loudly"
                           :handler (fn [{:strs [text]} _] (clojure.string/upper-case text))}]}))

(defn- connect [srv] (client/connect (client/in-process srv)))

(deftest proxying
  (let [a (backend-a)
        {:keys [server close!]} (proxy/proxy-server [{:prefix "a" :client (connect a)}
                                                     {:prefix "b" :client (connect (backend-b))}]
                                                    {:name "front" :version "1" :versions protocol/versions})
        handlers {"elicitation/create" (fn [_] {"action" "accept" "content" {"name" "Ada"}})}
        c (client/connect (client/in-process server) {:handlers handlers})]
    (try
      (is (= ["a_echo" "a_steps" "a_ask" "b_echo"] (map #(get % "name") (client/list-tools c))))
      (is (= "hi" (client/text (client/call-tool c "a_echo" {"text" "hi"}))))
      (is (= "HI" (client/text (client/call-tool c "b_echo" {"text" "hi"}))))
      (testing "progress is forwarded"
        (let [seen (atom [])]
          (is (= "stepped" (client/text (client/call-tool c "a_steps" {} {:on-progress #(swap! seen conj (get % "progress"))}))))
          (is (= [1 2] @seen))))
      (testing "input requests pass through to the caller and back"
        (is (= "hi Ada" (client/text (client/call-tool c "a_ask")))))
      (testing "resources, templates, prompts and completion"
        (is (= "A readme" (get-in (client/read-resource c "a:/readme") [0 "text"])))
        (is (= "item 7" (get-in (client/read-resource c "a:/items/7") [0 "text"])))
        (is (= "introduce ada" (get-in (client/get-prompt c "a_intro" {"who" "ada"}) ["messages" 0 "content" "text"])))
        (is (= ["ada" "alan"] (get (client/complete c {"type" "ref/prompt" "name" "a_intro"} "who" "a") "values"))))
      (testing "an initialize-era caller gets input as requests"
        (let [old (client/connect (client/in-process server) {:versions ["2025-11-25"] :handlers handlers})]
          (try (is (= "hi Ada" (client/text (client/call-tool old "a_ask"))))
               (finally (client/close old)))))
      (testing "a backend's list change resyncs the proxy"
        (server/add-tool! a {:name "late" :description "Late" :handler (fn [_ _] "late")})
        (is (loop [n 0]
              (cond (some #{"a_late"} (map #(get % "name") (client/list-tools c))) true
                    (> n 100) false
                    :else (do (Thread/sleep 20) (recur (inc n))))))
        (is (= "late" (client/text (client/call-tool c "a_late")))))
      (finally (client/close c) (close!)))))

(deftest collisions-are-refused
  (is (= :invalid-input
         (:kind (ex-data (try (proxy/proxy-server [{:prefix "x" :client (connect (backend-b))}
                                                   {:prefix "x" :client (connect (backend-b))}]
                                                  {:name "p" :version "1"})
                              (catch Exception e e)))))))
