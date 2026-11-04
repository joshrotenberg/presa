(ns presa.example.clojars-test
  "The Clojars MCP server spike against a stubbed fetch function."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.example.clojars :as cj]))

(def ring-core
  {"group_name" "ring" "jar_name" "ring-core" "description" "Ring core libraries."
   "latest_version" "1.15.6" "latest_release" "1.15.6" "downloads" 100 "user" "weavejester"
   "homepage" "https://github.com/ring-clojure/ring"
   "scm" {"url" "https://github.com/ring-clojure/ring"}
   "licenses" [{"name" "The MIT License"}]
   "recent_versions" [{"version" "1.16.0-SNAPSHOT" "downloads" 1} {"version" "1.15.6" "downloads" 10}
                      {"version" "1.15.0-RC1" "downloads" 2} {"version" "1.14.2" "downloads" 30}]
   "dependencies" [{"group_name" "org.clojure" "jar_name" "clojure" "version" "1.9.0" "scope" "compile"}]})

(def pom
  "<project><dependencies>
     <dependency><groupId>org.clojure</groupId><artifactId>clojure</artifactId><version>1.9.0</version></dependency>
     <dependency><groupId>commons-io</groupId><artifactId>commons-io</artifactId><version>2.15.1</version><optional>true</optional></dependency>
     <dependency><groupId>clj-time</groupId><artifactId>clj-time</artifactId><version>0.15.2</version><scope>test</scope></dependency>
   </dependencies></project>")

(defn- stub
  "A fetch over canned responses, recording the URLs it was asked for."
  [seen]
  (fn [url]
    (swap! seen conj url)
    (let [ok (fn [v] {:status 200 :body (if (string? v) v (json/write-str v))})]
      (cond
        (str/includes? url "q=bad") {:status 400 :body (json/write-str {"error" "Invalid search syntax"})}
        (str/includes? url "/search?") (ok {"total-hits" 1 "results-per-page" 24
                                           "results" [{"group_name" "ring" "jar_name" "ring-core" "version" "1.15.6"
                                                       "description" "Ring core libraries."}]})
        (str/ends-with? url "/api/artifacts/ring/ring-core") (ok ring-core)
        (str/ends-with? url "/api/artifacts/leiningen/leiningen")
        (ok {"group_name" "leiningen" "jar_name" "leiningen" "latest_release" "2.13.0" "recent_versions" []})
        (str/ends-with? url "/ring/ring-core/1.12.0/ring-core-1.12.0.pom") (ok pom)
        (str/ends-with? url "/ring/ring-core/1.15.6/ring-core-1.15.6.pom") (ok pom)
        (str/ends-with? url "/api/groups/ring") (ok [(assoc ring-core "downloads" 5 "jar_name" "ring")
                                                     ring-core])
        (str/ends-with? url "/api/users/weavejester") (ok {"groups" ["ring" "compojure"]})
        (str/includes? url "/api/release-feed?from=")
        (ok {"next_from" "2026-11-04T00:00:00Z"
             "releases" [{"group_id" "ring" "artifact_id" "ring-core" "version" "1.15.6"
                          "released_at" "2026-11-03T00:00:00Z"}]})
        :else {:status 404 :body ""}))))

(defn- with-client [f]
  (let [seen (atom [])
        c (client/connect (client/in-process (cj/clojars-server {:fetch (stub seen)})))]
    (try (f c seen) (finally (client/close c)))))

(defn- sc [c tool args] (get (client/call-tool c tool args) "structuredContent"))

(deftest tools
  (with-client
    (fn [c seen]
      (is (= ["search" "artifact" "versions" "dependencies" "group" "user" "recent_releases"]
             (map #(get % "name") (client/list-tools c))))
      (testing "search"
        (is (= {"total" 1 "page" 1 "per_page" 24
                "results" [{"name" "ring/ring-core" "version" "1.15.6" "description" "Ring core libraries."}]}
               (sc c "search" {"query" "ring core"})))
        (is (str/includes? (last @seen) "q=ring+core")))
      (testing "artifact"
        (let [a (sc c "artifact" {"name" "ring/ring-core"})]
          (is (= "ring/ring-core" (get a "name")))
          (is (= ["The MIT License"] (get a "licenses")))
          (is (= "https://github.com/ring-clojure/ring" (get a "scm")))
          (is (= [{"name" "org.clojure/clojure" "version" "1.9.0" "scope" "compile"}] (get a "dependencies")))
          (is (= {"deps.edn" "ring/ring-core {:mvn/version \"1.15.6\"}" "leiningen" "[ring/ring-core \"1.15.6\"]"}
                 (get a "usage"))))
        (testing "a name without a group is its own group"
          (is (= {"deps.edn" "leiningen/leiningen {:mvn/version \"2.13.0\"}" "leiningen" "[leiningen \"2.13.0\"]"}
                 (get (sc c "artifact" {"name" "leiningen"}) "usage")))))
      (testing "versions"
        (is (= ["1.15.6" "1.14.2"] (map #(get % "version") (get (sc c "versions" {"name" "ring/ring-core" "releases_only" true})
                                                                 "versions"))))
        (is (= 1 (count (get (sc c "versions" {"name" "ring/ring-core" "limit" 1}) "versions")))))
      (testing "dependencies of a version, from its POM"
        (is (= [{"name" "org.clojure/clojure" "scope" "compile" "version" "1.9.0"}
                {"name" "commons-io" "scope" "compile" "version" "2.15.1" "optional" true}
                {"name" "clj-time" "scope" "test" "version" "0.15.2"}]
               (get (sc c "dependencies" {"name" "ring/ring-core" "version" "1.12.0"}) "dependencies")))
        (is (= "1.15.6" (get (sc c "dependencies" {"name" "ring/ring-core"}) "version")) "the latest by default"))
      (testing "group, user and the release feed"
        (is (= ["ring/ring-core" "ring"] (map #(get % "name") (get (sc c "group" {"group" "ring"}) "artifacts")))
            "by downloads")
        (is (= ["ring" "compojure"] (get (sc c "user" {"user" "weavejester"}) "groups")))
        (is (= [{"name" "ring/ring-core" "version" "1.15.6" "released_at" "2026-11-03T00:00:00Z"}]
               (get (sc c "recent_releases" {"since" "2026-11-03T00:00:00Z"}) "releases"))))
      (testing "the artifact resource"
        (is (= "ring" (get (json/read-str (get-in (client/read-resource c "clojars://artifact/ring/ring-core") [0 "text"]))
                           "group_name")))))))

(deftest errors-are-tool-errors
  (with-client
    (fn [c _]
      (doseq [[tool args message] [["artifact" {"name" "no/such"} "Clojars has no no/such"]
                                   ["artifact" {"name" "bad name!"} "Not an artifact name"]
                                   ["search" {"query" "bad"} "Invalid search: Invalid search syntax"]
                                   ["dependencies" {"name" "ring/ring-core" "version" "0.0.0"} "Clojars has no POM"]
                                   ["group" {"group" "nope"} "Clojars has no group nope"]
                                   ["user" {"user" "nope"} "Clojars has no user nope"]]]
        (let [r (client/call-tool c tool args)]
          (is (true? (get r "isError")) tool)
          (is (str/starts-with? (client/text r) message) tool))))))

(deftest responses-are-cached
  (let [seen (atom [])
        c (client/connect (client/in-process (cj/clojars-server {:fetch (stub seen) :cache-ms 60000})))]
    (try
      (client/call-tool c "artifact" {"name" "ring/ring-core"})
      (client/call-tool c "versions" {"name" "ring/ring-core"})
      (is (= 1 (count (filter #(str/ends-with? % "/api/artifacts/ring/ring-core") @seen))))
      (client/call-tool c "artifact" {"name" "no/such"})
      (client/call-tool c "artifact" {"name" "no/such"})
      (is (= 2 (count (filter #(str/ends-with? % "/no/such") @seen))) "a 404 is not kept")
      (finally (client/close c)))))
