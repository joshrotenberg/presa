;; The repository conventions AGENTS.md lists, as cj run check checks them,
;; for CI and anyone without cj:
;;
;; - tracked *.md *.clj *.edn *.json files have no em dashes
;; - commit subjects in <base>..HEAD use conventional-commit prefixes
;; - commit messages have no Co-Authored-By, Assisted-By or Generated-By
;;   trailers and no "Generated with" lines
;;
;;   bb lint [base]    (base defaults to origin/main; skipped when missing)
(require '[babashka.process :as p]
         '[clojure.string :as str])

(defn- git [& args] (str/trim (:out (apply p/shell {:out :string :continue true} "git" args))))

(def em-dash (str (char 0x2014)))

(def dash-problems
  (for [file (str/split-lines (git "ls-files" "*.md" "*.clj" "*.edn" "*.json"))
        :when (seq file)
        [n line] (map-indexed vector (str/split-lines (slurp file)))
        :when (str/includes? line em-dash)]
    (str file ":" (inc n) ": em dash")))

(def base (or (first *command-line-args*) "origin/main"))
(def base? (zero? (:exit (p/shell {:out :string :err :string :continue true} "git" "rev-parse" "--verify" "--quiet" base))))

(def prefix #"^(feat|fix|docs|test|refactor|chore)(\([^)]+\))?!?: .+")
(def attribution #"(?im)^\s*(co-authored-by|assisted-by|generated-by):|generated with")

(def commit-problems
  (when base?
    (for [sha (str/split-lines (git "rev-list" (str base "..HEAD")))
          :when (seq sha)
          :let [subject (git "log" "-1" "--format=%s" sha)
                body (git "log" "-1" "--format=%B" sha)]
          problem [(when-not (re-matches prefix subject)
                     (str (subs sha 0 7) " " subject ": no conventional-commit prefix"))
                   (when (re-find attribution body)
                     (str (subs sha 0 7) " " subject ": attribution line"))]
          :when problem]
      problem)))

(when-not base? (println "commit checks skipped:" base "does not name a commit"))
(doseq [p (concat dash-problems commit-problems)] (println p))
(if (seq (concat dash-problems commit-problems))
  (System/exit 1)
  (println "lint ok"))
