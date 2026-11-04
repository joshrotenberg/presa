(ns presa.example.clojars
  "An MCP server for the Clojars API (a spike): search Clojars, and look up
  artifacts, their versions and dependencies, groups, users and recent
  releases. It reads https://clojars.org/api, the search endpoint and the
  POM files on repo.clojars.org; see
  https://github.com/clojars/clojars-web/wiki/Data. Run it with

      bb --config /path/to/presa/bb.edn -m presa.example.clojars

  Artifact names are \"group/name\", or \"name\" when the group is the same
  (\"leiningen\" is leiningen/leiningen). Clojars computes download counts
  once a day."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.transport.stdio :as stdio])
  (:import (java.net URI URLEncoder)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)))

;; HTTP

(def user-agent "presa-clojars-mcp (https://github.com/joshrotenberg/presa)")

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.connectTimeout (Duration/ofSeconds 10))
             (.build))))

(defn http-fetch
  "GET url: {:status :body}."
  [url]
  (let [resp (.send ^HttpClient @http-client
                    (-> (HttpRequest/newBuilder (URI. url))
                        (.header "user-agent" user-agent)
                        (.header "accept" "application/json")
                        (.timeout (Duration/ofSeconds 20))
                        (.GET)
                        (.build))
                    (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode resp) :body (.body resp)}))

(defn- cached
  "fetch with each 200 response kept for ttl-ms."
  [fetch ttl-ms]
  (let [cache (atom {})]
    (fn [url]
      (let [now (System/currentTimeMillis)
            hit (get @cache url)]
        (if (and hit (< now (:expires hit)))
          (:response hit)
          (let [r (fetch url)]
            (when (= 200 (:status r))
              (swap! cache (fn [m] (assoc (into {} (remove (fn [[_ v]] (< (:expires v) now))) m)
                                          url {:response r :expires (+ now ttl-ms)}))))
            r))))))

(defn- enc [s] (URLEncoder/encode (str s) "UTF-8"))

(def api "https://clojars.org")
(def repo "https://repo.clojars.org")

;; Names

(defn- coordinates
  "[group artifact] of \"group/artifact\" or \"artifact\", or nil."
  [s]
  (let [s (str/trim (str s))]
    (when (re-matches #"[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)?" s)
      (let [[g a] (str/split s #"/" 2)]
        (if a [g a] [g g])))))

(defn- coordinate [g a] (if (= g a) g (str g "/" a)))

(defn- bad-name [s]
  (server/tool-error (str "Not an artifact name: " (pr-str s) ". Use \"group/name\", or \"name\" when the group is the same.")))

;; Shapes

(defn- artifact-summary [{:strs [group_name jar_name latest_version latest_release description downloads user homepage]}]
  (cond-> {"name" (coordinate group_name jar_name)
           "group" group_name
           "artifact" jar_name}
    latest_version (assoc "latest_version" latest_version)
    latest_release (assoc "latest_release" latest_release)
    description (assoc "description" description)
    (number? downloads) (assoc "downloads" downloads)
    user (assoc "user" user)
    homepage (assoc "homepage" homepage)))

(defn- dependency [{:strs [group_name jar_name version scope]}]
  (cond-> {"name" (coordinate group_name jar_name) "version" version}
    scope (assoc "scope" scope)))

(defn- leiningen [g a v] (str "[" (coordinate g a) " \"" v "\"]"))
(defn- deps-edn [g a v] (str (if (= g a) (str g "/" a) (coordinate g a)) " {:mvn/version \"" v "\"}"))

;; POMs: the dependencies of one version

(defn- tag [xml t] (some-> (re-find (re-pattern (str "(?s)<" t ">\\s*(.*?)\\s*</" t ">")) xml) second))

(defn pom-dependencies
  "The dependencies a POM declares, as maps of \"name\" \"version\" and
  \"scope\" (\"compile\" when it names none). Reads the <dependencies>
  block with patterns: POMs on Clojars are written by tools."
  [pom]
  (let [deps (or (some-> (re-find #"(?s)<dependencies>(.*?)</dependencies>" pom) second) "")]
    (vec (for [[_ d] (re-seq #"(?s)<dependency>(.*?)</dependency>" deps)
               :let [g (tag d "groupId") a (tag d "artifactId")]
               :when (and g a)]
           (cond-> {"name" (coordinate g a) "scope" (or (tag d "scope") "compile")}
             (tag d "version") (assoc "version" (tag d "version"))
             (= "true" (tag d "optional")) (assoc "optional" true))))))

;; Tools

(def ^:private name-schema
  {"type" "string" "description" "\"group/name\", or \"name\" when the group is the same, such as \"ring/ring-core\" or \"leiningen\""})

(defn- get-json
  "The JSON at url, or a tool error naming what was not found."
  [fetch url what]
  (let [{:keys [status body]} (fetch url)]
    (case (long status)
      200 (json/read-str body)
      404 (server/tool-error (str "Clojars has no " what))
      (server/tool-error (str "Clojars answered " status " for " what)))))

(defn- error? [v] (and (map? v) (true? (get v "isError"))))

(defn- with-artifact
  "Call f with the artifact JSON of name, or return the tool error."
  [fetch name f]
  (if-let [[g a] (coordinates name)]
    (let [doc (get-json fetch (str api "/api/artifacts/" (enc g) "/" (enc a)) (coordinate g a))]
      (if (error? doc) doc (f g a doc)))
    (bad-name name)))

(defn tools [fetch]
  [{:name "search"
    :title "Search Clojars"
    :description "Search Clojars for libraries by name, group or description. Supports Clojars query syntax (artifact-id:, group-id:, description:, AND, OR). 24 results per page."
    :input-schema {"type" "object"
                   "properties" {"query" {"type" "string"}
                                 "page" {"type" "integer" "minimum" 1 "description" "default 1"}}
                   "required" ["query"]}
    :annotations {"readOnlyHint" true "openWorldHint" true}
    :handler (fn [{:strs [query page]} _]
               (let [page (or page 1)
                     {:keys [status body]} (fetch (str api "/search?format=json&q=" (enc query) "&page=" page))
                     doc (try (json/read-str body) (catch Exception _ {}))]
                 (cond
                   (= 400 status) (server/tool-error (str "Invalid search: " (get doc "error" query)))
                   (not= 200 status) (server/tool-error (str "Clojars search answered " status))
                   :else {"total" (get doc "total-hits")
                          "page" page
                          "per_page" (get doc "results-per-page")
                          "results" (mapv (fn [{:strs [group_name jar_name version description]}]
                                            (cond-> {"name" (coordinate group_name jar_name) "version" version}
                                              description (assoc "description" description)))
                                          (get doc "results"))})))}

   {:name "artifact"
    :title "Artifact details"
    :description "An artifact's description, latest version, total downloads, homepage, source repository, licenses, owner, the dependencies of its latest version, and how to depend on it."
    :input-schema {"type" "object" "properties" {"name" name-schema} "required" ["name"]}
    :annotations {"readOnlyHint" true "openWorldHint" true}
    :handler (fn [{:strs [name]} _]
               (with-artifact fetch name
                 (fn [g a {:strs [latest_release latest_version licenses scm dependencies recent_versions] :as doc}]
                   (let [v (or latest_release latest_version)]
                     (cond-> (artifact-summary doc)
                       (get scm "url") (assoc "scm" (get scm "url"))
                       (seq licenses) (assoc "licenses" (mapv #(get % "name") licenses))
                       true (assoc "versions" (count recent_versions)
                                   "dependencies" (mapv dependency dependencies)
                                   "url" (str api "/" (coordinate g a)))
                       v (assoc "usage" {"deps.edn" (deps-edn g a v) "leiningen" (leiningen g a v)}))))))}

   {:name "versions"
    :title "Artifact versions"
    :description "An artifact's versions, newest first, with the downloads of each."
    :input-schema {"type" "object"
                   "properties" {"name" name-schema
                                 "limit" {"type" "integer" "minimum" 1 "description" "at most this many (default 20)"}
                                 "releases_only" {"type" "boolean" "description" "leave out SNAPSHOT, alpha, beta and RC versions"}}
                   "required" ["name"]}
    :annotations {"readOnlyHint" true "openWorldHint" true}
    :handler (fn [{:strs [name limit releases_only]} _]
               (with-artifact fetch name
                 (fn [g a {:strs [recent_versions]}]
                   (let [pre? #(re-find #"(?i)snapshot|alpha|beta|-rc|\.rc" %)
                         vs (cond->> recent_versions
                              releases_only (remove #(pre? (get % "version"))))]
                     {"name" (coordinate g a)
                      "total" (count vs)
                      "versions" (vec (take (or limit 20) vs))}))))}

   {:name "dependencies"
    :title "Artifact dependencies"
    :description "The dependencies of an artifact's version (default the latest), from its POM."
    :input-schema {"type" "object"
                   "properties" {"name" name-schema
                                 "version" {"type" "string" "description" "default the latest"}}
                   "required" ["name"]}
    :annotations {"readOnlyHint" true "openWorldHint" true}
    :handler (fn [{:strs [name version]} _]
               (if-let [[g a] (coordinates name)]
                 (let [v (or version
                             (let [doc (get-json fetch (str api "/api/artifacts/" (enc g) "/" (enc a)) (coordinate g a))]
                               (if (error? doc) doc (or (get doc "latest_release") (get doc "latest_version")))))]
                   (if (error? v)
                     v
                     (let [{:keys [status body]} (fetch (str repo "/" (str/replace g "." "/") "/" a "/" v "/" a "-" v ".pom"))]
                       (if (= 200 status)
                         {"name" (coordinate g a) "version" v "dependencies" (pom-dependencies body)}
                         (server/tool-error (str "Clojars has no POM for " (coordinate g a) " " v))))))
                 (bad-name name)))}

   {:name "group"
    :title "Group artifacts"
    :description "The artifacts in a Clojars group, with their latest versions and downloads."
    :input-schema {"type" "object" "properties" {"group" {"type" "string"}} "required" ["group"]}
    :annotations {"readOnlyHint" true "openWorldHint" true}
    :handler (fn [{:strs [group]} _]
               (let [doc (get-json fetch (str api "/api/groups/" (enc group)) (str "group " group))]
                 (if (error? doc)
                   doc
                   {"group" group "artifacts" (mapv artifact-summary (sort-by #(- (get % "downloads" 0)) doc))})))}

   {:name "user"
    :title "User groups"
    :description "The groups a Clojars user can deploy to."
    :input-schema {"type" "object" "properties" {"user" {"type" "string"}} "required" ["user"]}
    :annotations {"readOnlyHint" true "openWorldHint" true}
    :handler (fn [{:strs [user]} _]
               (let [doc (get-json fetch (str api "/api/users/" (enc user)) (str "user " user))]
                 (if (error? doc) doc {"user" user "groups" (vec (get doc "groups"))})))}

   {:name "recent_releases"
    :title "Recent releases"
    :description "Releases to Clojars since a time (default the last 24 hours), oldest first."
    :input-schema {"type" "object"
                   "properties" {"since" {"type" "string" "description" "an ISO-8601 instant, such as 2026-11-03T00:00:00Z"}
                                 "limit" {"type" "integer" "minimum" 1 "description" "at most this many (default 50)"}}}
    :annotations {"readOnlyHint" true "openWorldHint" true}
    :handler (fn [{:strs [since limit]} _]
               (let [since (or since (str (.minus (java.time.Instant/now) (Duration/ofDays 1))))
                     doc (get-json fetch (str api "/api/release-feed?from=" (enc since)) "release feed")]
                 (if (error? doc)
                   doc
                   (let [rs (get doc "releases")]
                     {"since" since
                      "next_from" (get doc "next_from")
                      "releases" (vec (take (or limit 50)
                                            (map (fn [{:strs [group_id artifact_id version released_at description]}]
                                                   (cond-> {"name" (coordinate group_id artifact_id)
                                                            "version" version
                                                            "released_at" released_at}
                                                     description (assoc "description" description)))
                                                 rs)))}))))}])

(defn- artifact-template [fetch]
  {:uri-template "clojars://artifact/{group}/{name}"
   :name "artifact"
   :title "A Clojars artifact"
   :description "The Clojars API's JSON for an artifact."
   :mime-type "application/json"
   :handler (fn [{:strs [group name]} _]
              (let [{:keys [status body]} (fetch (str api "/api/artifacts/" (enc group) "/" (enc name)))]
                (when (= 200 status) body)))})

(defn clojars-server
  "The Clojars MCP server. Options: :fetch (fn [url]) returning {:status
  :body} (default HTTP), :cache-ms how long a response is kept (default
  300000)."
  ([] (clojars-server {}))
  ([{:keys [fetch cache-ms] :or {fetch http-fetch cache-ms 300000}}]
   (let [fetch (cached fetch cache-ms)]
     (server/server
      {:name "presa-clojars"
       :version "0.1.0"
       :title "Clojars"
       :instructions "Look up Clojure libraries on Clojars: search, artifact details, versions, dependencies, groups, users and recent releases. Names are \"group/name\", or \"name\" when the group is the same."
       :versions protocol/versions
       :tools (tools fetch)
       :resource-templates [(artifact-template fetch)]}))))

(defn -main [& _]
  (stdio/serve! (clojars-server))
  (shutdown-agents))
