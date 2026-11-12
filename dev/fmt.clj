;; Formatting with cljfmt, under Babashka: its core reformats each tracked
;; Clojure file in the lint paths, with .cljfmt.edn's options.
;;
;;   bb fmt          rewrite the files that cljfmt would change
;;   (fmt/problems)  what bb lint reports: one line per file to reformat
(ns fmt
  (:require [babashka.deps :as deps]
            [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(def cljfmt-version "0.13.1")

(deps/add-deps {:deps {'dev.weavejester/cljfmt {:mvn/version cljfmt-version}}})
(require 'cljfmt.core)

(def paths ["src" "test" "test-jvm" "examples" "dev" "conformance" "build.clj"])

(defn- files []
  (->> (:out (apply p/shell {:out :string} "git" "ls-files" "--" (map #(if (str/ends-with? % ".clj") % (str % "/*.clj")) paths)))
       str/split-lines
       (remove str/blank?)))

(def options (edn/read-string (slurp ".cljfmt.edn")))

(defn- reformat [s] ((resolve 'cljfmt.core/reformat-string) s options))

(defn unformatted
  "The files cljfmt would change, as [file formatted] pairs."
  []
  (for [f (files)
        :let [s (slurp f) formatted (reformat s)]
        :when (not= s formatted)]
    [f formatted]))

(defn problems []
  (for [[f _] (unformatted)] (str f ": not formatted (bb fmt fixes it)")))

(defn fix! []
  (doseq [[f formatted] (unformatted)]
    (spit f formatted)
    (println "formatted" f)))

(when (= "fix" (first *command-line-args*)) (fix!))
