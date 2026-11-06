;; The repository conventions AGENTS.md lists, as cj run check checks them,
;; for CI and anyone without cj:
;;
;; - clj-kondo (through its Babashka pod, configured in .clj-kondo/config.edn)
;;   finds no errors or warnings in the Clojure sources; info findings are
;;   printed as notes
;; - tracked *.md *.clj *.edn *.json files have no em dashes (vendored
;;   files under conformance/schema/ aside)
;; - commit subjects in <base>..HEAD (merge commits aside) use
;;   conventional-commit prefixes
;; - commit messages have no Co-Authored-By, Assisted-By or Generated-By
;;   trailers and no "Generated with" lines; a Co-authored-by trailer for a
;;   GitHub bot account (GitHub adds one when a bot's pull request, such as
;;   release-please's, is squash-merged) is not attribution and is allowed
;;
;;   bb lint [base]    (base defaults to origin/main; skipped when missing)
(ns lint
  (:require [babashka.pods :as pods]
            [babashka.process :as p]
            [clojure.string :as str]))

(defn- git [& args] (str/trim (:out (apply p/shell {:out :string :continue true} "git" args))))

(def clj-kondo-version "2026.08.04")
(def lint-paths ["src" "test" "test-jvm" "examples" "dev" "conformance" "bb.edn" "deps.edn" "build.clj"])

(def kondo-findings
  (do (pods/load-pod 'clj-kondo/clj-kondo clj-kondo-version)
      (:findings ((requiring-resolve 'pod.borkdude.clj-kondo/run!) {:lint lint-paths :parallel true}))))

(defn- finding [f] (str (:filename f) ":" (:row f) ": " (name (:type f)) ": " (:message f)))

(def kondo-problems
  (for [f kondo-findings :when (#{:error :warning} (:level f))] (finding f)))

(doseq [f kondo-findings :when (= :info (:level f))] (println "note:" (finding f)))

(def em-dash (str (char 0x2014)))

(def dash-problems
  ;; conformance/schema/ holds vendored files, pinned by hash
  (for [file (str/split-lines (git "ls-files" "*.md" "*.clj" "*.edn" "*.json" ":(exclude)conformance/schema/**"))
        :when (seq file)
        [n line] (map-indexed vector (str/split-lines (slurp file)))
        :when (str/includes? line em-dash)]
    (str file ":" (inc n) ": em dash")))

(def base (or (first *command-line-args*) "origin/main"))
(def base? (zero? (:exit (p/shell {:out :string :err :string :continue true} "git" "rev-parse" "--verify" "--quiet" base))))

;; the same rule as clajent.checks/conventional-subject, which cj run check uses
(def prefix #"^(?:(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\([^)\s]+\))?!?: \S|Revert \").*")
(def attribution #"(?im)^\s*(co-authored-by|assisted-by|generated-by):|generated with")

;; Co-authored-by: name[bot] <id+name[bot]@users.noreply.github.com>
(def bot-co-author #"(?im)^\s*co-authored-by:\s*[^<\n]*\[bot\]\s*<[^>\n]*\[bot\]@users\.noreply\.github\.com>\s*$")

(def commit-problems
  (when base?
    ;; merge commits are GitHub's or git's own wording, not ours
    (for [sha (str/split-lines (git "rev-list" "--no-merges" (str base "..HEAD")))
          :when (seq sha)
          :let [subject (git "log" "-1" "--format=%s" sha)
                body (git "log" "-1" "--format=%B" sha)]
          problem [(when-not (re-matches prefix subject)
                     (str (subs sha 0 7) " " subject ": no conventional-commit prefix"))
                   (when (re-find attribution (str/replace body bot-co-author ""))
                     (str (subs sha 0 7) " " subject ": attribution line"))]
          :when problem]
      problem)))

(when-not base? (println "commit checks skipped:" base "does not name a commit"))
(doseq [p (concat kondo-problems dash-problems commit-problems)] (println p))
(if (seq (concat kondo-problems dash-problems commit-problems))
  (System/exit 1)
  (println "lint ok"))
