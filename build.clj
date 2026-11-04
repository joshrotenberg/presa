(ns build
  "Build and deploy the presa jar:

      clojure -T:build jar      ; target/presa-<version>.jar and its pom
      clojure -T:build deploy   ; to Clojars, with CLOJARS_USERNAME and CLOJARS_PASSWORD

  The version is version.txt, which release-please keeps."
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.joshrotenberg/presa)
(def version (str/trim (slurp "version.txt")))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def url "https://github.com/joshrotenberg/presa")

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis (b/create-basis {:project "deps.edn"})
                :src-dirs ["src"]
                :scm {:url url
                      :connection "scm:git:https://github.com/joshrotenberg/presa.git"
                      :developerConnection "scm:git:ssh://git@github.com/joshrotenberg/presa.git"
                      :tag (str "v" version)}
                :pom-data [[:description "Model Context Protocol servers and clients for Babashka and the JVM"]
                           [:url url]
                           [:licenses
                            [:license
                             [:name "MIT License"]
                             [:url "https://opensource.org/license/mit"]]]]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/jar {:class-dir class-dir :jar-file jar-file})
  (println "wrote" jar-file))

(defn deploy [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
