(ns presa.example.notebook-test
  "The notebook example, driven in process and over HTTP, on Babashka and
  the JVM. The REPL walkthrough (notebook_repl.clj) is run by
  presa.guides-test."
  (:require [clojure.test :refer [deftest is testing]]
            [presa.auth.jwt :as jwt]
            [presa.client :as client]
            [presa.example.notebook :as notebook]
            [presa.example.notebook-client :as walk]
            [presa.transport.http :as http]))

(defn- quiet-walk [c] (binding [*out* (java.io.StringWriter.)] (walk/walk c)))

(defn- fresh [] (notebook/notebook {:notes notebook/sample-notes :export-delay-ms 5}))

(deftest the-walk-through
  (doseq [version ["2026-07-28" "2025-11-25" "2025-06-18"]]
    (testing version
      (let [c (client/connect (client/in-process (fresh)) {:versions [version] :tasks true :handlers walk/handlers})]
        (try
          (let [seen (quiet-walk c)]
            (is (= version (:version seen)))
            (is (= "n3" (:created seen)))
            (is (= ["n1" "n2" "n3"] (:hits seen)))
            (is (= ["notes://index" "notes://notes/n1" "notes://notes/n2" "notes://notes/n3"] (:resources seen)))
            (is (= ["n1" "n2" "n3"] (:completed seen)))
            (is (= "A note about proxies." (:summary seen)))
            (is (= "Deleted n3" (:deleted seen)))
            (is (= 2 (:exported seen)))
            (when (= "2026-07-28" version)
              (is (= #{"notifications/subscriptions/acknowledged" "notifications/resources/list_changed"
                       "notifications/resources/updated"}
                     (:changes seen)))))
          (finally (client/close c)))))))

(deftest export-is-cancellable
  (let [srv (notebook/notebook {:notes notebook/sample-notes :export-delay-ms 300})
        c (client/connect (client/in-process srv) {:tasks true})]
    (try
      (let [created (client/call-tool c "export_notes" {})]
        (is (client/task? created))
        (client/cancel-task c (get created "taskId"))
        (is (= "cancelled" (get (client/await-task c created {:timeout-ms 5000}) "status"))))
      (finally (client/close c)))))

(deftest a-missing-note-is-a-tool-error
  (let [c (client/connect (client/in-process (fresh)))]
    (try
      (let [r (client/call-tool c "tag_note" {"id" "n9" "tag" "x"})]
        (is (true? (get r "isError")))
        (is (= "No note n9" (client/text r))))
      (finally (client/close c)))))

(deftest oauth-scopes
  (let [kp (.generateKeyPair (doto (java.security.KeyPairGenerator/getInstance "RSA") (.initialize 2048)))
        port (let [probe (http/serve! (fresh))] ((:stop! probe)) (:port probe))
        issuer "https://auth.example.com"
        {:keys [url stop!]} (notebook/serve-http {:port port :issuer issuer :keys {"k1" (.getPublic kp)}})
        token (fn [scope] (jwt/sign {"sub" "ada" "iss" issuer "aud" url "scope" scope
                                     "exp" (+ 600 (quot (System/currentTimeMillis) 1000))}
                                    {:alg "RS256" :kid "k1" :key (.getPrivate kp)}))
        as (fn [scope] (client/connect (http/url url {:headers {"authorization" (str "Bearer " (token scope))}})))]
    (try
      (testing "a reader does not see the tools that change notes"
        (let [c (as "notes:read")]
          (try (is (= ["search_notes" "summarize_note" "export_notes"] (map #(get % "name") (client/list-tools c))))
               (is (= -32003 (:code (ex-data (try (client/call-tool c "create_note" {"title" "t" "body" "b"})
                                                  (catch Exception e e))))))
               (finally (client/close c)))))
      (testing "a writer can create notes"
        (let [c (as "notes:read notes:write")]
          (try (is (= "n3" (get-in (client/call-tool c "create_note" {"title" "t" "body" "b"}) ["structuredContent" "id"])))
               (finally (client/close c)))))
      (testing "no token is a 401"
        (is (= 401 (get-in (ex-data (try (client/connect (http/url url)) (catch Exception e e))) [:data "status"]))))
      (finally (stop!)))))
