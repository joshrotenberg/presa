(ns presa.bb-test
  (:require [clojure.test :refer [deftest is testing]]
            [presa.bb :as pb]
            [presa.client :as client]
            [presa.part :as part]))

(def fixture "test/presa/bb_fixture/bb.edn")

(deftest bb-tasks-as-tools
  (let [p (pb/task-tools {:bb-edn fixture})]
    (is (= ["greet" "test_unit"] (sort (map :name (:tools p)))) "opted-in tasks, with : made _")
    (is (re-find #"Print a greeting" (:description (first (filter #(= "greet" (:name %)) (:tools p))))))
    (part/with-client {:parts [p]}
      (fn [c]
        (testing "a task's output is its result"
          (let [r (client/call-tool c "greet" {"args" ["Ada"]})]
            (is (false? (get r "isError")))
            (is (re-find #"exit 0" (client/text r)))
            (is (re-find #"hello Ada" (client/text r)))))
        (testing "a nonzero exit is an error with the output"
          (let [r (client/call-tool c "test_unit" {})]
            (is (true? (get r "isError")))
            (is (re-find #"exit 3" (client/text r)))
            (is (re-find #"1 failure" (client/text r)))))))
    (testing "args reach the task when the server keywordizes arguments"
      (part/with-client {:parts [p] :keywordize-args? true}
        (fn [c] (is (re-find #"hello Ada" (client/text (client/call-tool c "greet" {"args" ["Ada"]})))))))
    (testing ":only overrides the opt-in"
      (is (= ["shout"] (map :name (:tools (pb/task-tools {:bb-edn fixture :only ['shout]})))))))
  (testing "a timeout is an error, and the task comes from the named file"
    (let [f (java.io.File/createTempFile "presa-bb" ".edn")]
      (spit f "{:tasks {slow {:mcp/tool true :task (Thread/sleep 5000)}}}")
      (part/with-client {:parts [(pb/task-tools {:bb-edn (.getPath f) :timeout-ms 300})]}
        (fn [c]
          (let [r (client/call-tool c "slow" {})]
            (is (true? (get r "isError")))
            (is (re-find #"timed out" (client/text r))))))
      (.delete f))))

(deftest what-a-project-offers
  (is (= [] (pb/project-parts (System/getProperty "java.io.tmpdir"))) "no bb.edn, no parts")
  (is (= ["greet" "test_unit"]
         (sort (map :name (mapcat :tools (pb/project-parts "test/presa/bb_fixture")))))
      "no mcp task: its opted-in tasks"))

(deftest files-as-resources
  (let [p (pb/file-resources [fixture "no-such-file"])]
    (is (= 1 (count (:resources p))))
    (part/with-client {:parts [p]}
      (fn [c] (is (re-find #"greet" (get-in (client/read-resource c (:uri (first (:resources p)))) [0 "text"])))))))
