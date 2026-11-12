(ns presa.guides-test
  "Runs the clojure code blocks of docs/guides/*.md, in order, one fresh
  namespace per guide. A block after an HTML comment <!-- no-run --> is
  skipped. A line `;; => value` after an expression checks that the code
  since the previous check returns value.

  REPL walkthroughs (`walkthroughs`) are checked the same way: the forms of
  the file's top-level (comment ...) block, in the file's namespace."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- blocks
  "The code of each runnable clojure block in markdown."
  [markdown]
  (loop [lines (str/split-lines markdown) skip? false in nil acc []]
    (if-let [line (first lines)]
      (cond
        (and (nil? in) (= "<!-- no-run -->" (str/trim line))) (recur (rest lines) true nil acc)
        (and (nil? in) (= "```clojure" (str/trim line))) (recur (rest lines) skip? [] acc)
        (and in (= "```" (str/trim line))) (recur (rest lines) false nil (if skip? acc (conj acc in)))
        in (recur (rest lines) skip? (conj in line) acc)
        (str/blank? line) (recur (rest lines) skip? nil acc)
        :else (recur (rest lines) false nil acc))
      acc)))

(defn- steps
  "[code expected-or-nil] pairs from a block's lines."
  [lines]
  (loop [lines lines code [] acc []]
    (if-let [line (first lines)]
      (if-let [[_ expected] (re-matches #"\s*;; => (.*)" line)]
        (recur (rest lines) [] (conj acc [(str/join "\n" code) expected]))
        (recur (rest lines) (conj code line) acc))
      (cond-> acc (seq (remove str/blank? code)) (conj [(str/join "\n" code) nil])))))

(defn- run-guide [file]
  (let [ns-sym (gensym "guide-")
        n (create-ns ns-sym)]
    (binding [*ns* n]
      (refer-clojure)
      (doseq [block (blocks (slurp file))
              [code expected] (steps block)]
        (let [v (load-string code)]
          (when expected
            (is (= (read-string expected) v) (str (.getName (io/file file)) ": " (str/trim code)))))))
    (remove-ns ns-sym)))

(def walkthroughs ["examples/presa/example/notebook_repl.clj"])

(defn- comment-body
  "The lines after a line \"(comment\", to the end of the file, without the
  comment form's closing paren (on a line of its own or after the last
  form)."
  [text]
  (let [lines (->> (str/split-lines text)
                   (drop-while #(not= "(comment" (str/trim %)))
                   rest
                   vec)
        body (str/trimr (str/join "\n" lines))]
    (str/split-lines (subs body 0 (dec (count body))))))

(defn- run-walkthrough [file]
  (let [text (slurp file)
        header (subs text 0 (str/index-of text "\n(comment"))]
    (load-string header)
    (binding [*ns* (the-ns (second (read-string header)))]
      (doseq [[code expected] (steps (comment-body text))]
        (let [v (load-string code)]
          (when expected
            (is (= (read-string expected) v) (str (.getName (io/file file)) ": " (str/trim code)))))))))

(deftest repl-walkthroughs
  (doseq [f walkthroughs]
    (testing f
      (run-walkthrough f))))

(deftest guides
  (doseq [f (sort-by str (filter #(str/ends-with? (str %) ".md") (file-seq (io/file "docs/guides"))))]
    (testing (str f)
      (run-guide f))))
