(ns cj.project.check
  "Run the checks for presa and return a pass/fail report. Changes nothing.

  Written by cj project init from the run: steps of .github/workflows/ci.yml. Edit the steps to match
  how the project is checked.

  Steps, in order, from the repository root:

  - :test       bb test
  - :jvm-test   bb jvm-test
  - :em-dashes  no em dashes in tracked *.md *.clj *.edn *.json files
  - :commits    commits in origin/main..HEAD have conventional-commit subjects and no attribution lines (skipped when origin/main does not exist)

  Every step runs even when an earlier one fails. Returns
  clajent.checks/run-steps' report with :root added."
  {:cj/examples ["cj run check"]}
  (:require [clajent.checks :as checks]))

(defn steps
  "The steps for the repository at root as [name f] pairs in run order."
  [root]
  [[:test #(checks/command root ["bb" "test"] {:failures checks/clojure-test-failures})]
   [:jvm-test #(checks/command root ["bb" "jvm-test"] {:failures checks/clojure-test-failures})]
   [:em-dashes #(checks/em-dashes root {:pathspecs ["*.md" "*.clj" "*.edn" "*.json"]})]
   [:commits #(checks/commit-messages root {:base "origin/main"})]])

(defn -main [& _]
  (let [root (checks/repo-root (System/getProperty "user.dir"))]
    (assoc (checks/run-steps (steps root)) :root root)))
