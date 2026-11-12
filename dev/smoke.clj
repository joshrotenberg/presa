;; Smoke-test the built jar (clojure -T:build jar): require each namespace
;; in it under Babashka with only the jar on the classpath, and on the JVM
;; with the jar as a dependency, so the dependencies its pom names are the
;; ones that resolve. presa.schema.networknt is skipped: its dependency is
;; optional and not in the pom.
;;
;;   bb smoke
(ns smoke
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]))

(def jar (or (first (fs/glob "target" "presa-*.jar"))
             (do (println "no target/presa-*.jar: run clojure -T:build jar first") (System/exit 1))))

(def namespaces
  (with-open [z (java.util.zip.ZipFile. (str jar))]
    (->> (enumeration-seq (.entries z))
         (map #(.getName ^java.util.zip.ZipEntry %))
         (filter #(re-matches #"presa/.*\.clj" %))
         (map #(-> % (str/replace #"\.clj$" "") (str/replace "/" ".") (str/replace "_" "-")))
         (remove #{"presa.schema.networknt"})
         sort vec)))

(def form (pr-str (list* 'require (map #(list 'quote (symbol %)) namespaces))))

(println "jar:" (str jar) "-" (count namespaces) "namespaces")

(defn- run [label cmd]
  (let [{:keys [exit err]} (apply p/shell {:continue true :err :string :out :string} cmd)]
    (if (zero? exit)
      (println label "ok")
      (do (println label "failed:" err) (System/exit 1)))))

(run "bb" ["bb" "-cp" (str jar) "-e" form])
(run "jvm" ["clojure" "-Sdeps" (pr-str {:deps {'io.github.joshrotenberg/presa {:local/root (str (fs/absolutize jar))}}})
            "-M" "-e" form])
