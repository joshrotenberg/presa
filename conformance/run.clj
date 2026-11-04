;; Run the official MCP conformance runner's server leg against presa's
;; fixture and summarize the result.
;;
;;   bb conformance/run.clj [revision] [scenario]
;;
;; revision is 2026-07-28 (the default), 2025-11-25 or 2025-06-18. The
;; first two run the runner's frozen requirement set for that revision; for
;; 2025-06-18, which has none, the runner's active suite runs unscored. The
;; initialize-era revisions serve the fixture with every revision enabled.
;;
;; PRESA_CONFORMANCE_RUNNER is the runner's dist/index.js
;; (@modelcontextprotocol/conformance@0.2.0-alpha.12); by default it is
;; taken from conformance/node_modules, then from a snodo checkout next to
;; this one. Results land in tmp/conformance/<timestamp>/.
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def root (str (fs/parent (fs/parent (fs/absolutize *file*)))))
(def pinned "0.2.0-alpha.12")

(def runner
  (or (System/getenv "PRESA_CONFORMANCE_RUNNER")
      (some #(when (fs/exists? %) (str %))
            [(fs/path root "conformance/node_modules/@modelcontextprotocol/conformance/dist/index.js")
             (fs/path root "../snodo/conformance/node_modules/@modelcontextprotocol/conformance/dist/index.js")])
      (throw (ex-info "No conformance runner: set PRESA_CONFORMANCE_RUNNER or npm ci in conformance/" {}))))

(let [pkg (json/parse-string (slurp (str (fs/path (fs/parent (fs/parent runner)) "package.json"))))]
  (when-not (= pinned (get pkg "version"))
    (throw (ex-info (str "The runner is " (get pkg "version") ", not " pinned) {}))))

(def revision (or (some #(re-matches #"\d{4}-\d{2}-\d{2}" %) *command-line-args*) "2026-07-28"))
(def scenario (first (remove #(re-matches #"\d{4}-\d{2}-\d{2}" %) *command-line-args*)))
(def frozen? (contains? #{"2026-07-28" "2025-11-25"} revision))
(def out-dir (str (fs/path root "tmp/conformance"
                           (str/replace (str (java.time.Instant/now)) #"[:.]" "-"))))
(fs/create-dirs out-dir)

(def fixture
  (p/process {:dir root
              :extra-env {"MCP_PORT" "0" "MCP_FIXTURE_PROFILE" (if (= "2026-07-28" revision) "latest" "legacy")}
              :err :inherit}
             "bb" "-cp" "src:conformance" "-m" "presa.conformance.fixture"))

(def url
  (let [r (io/reader (:out fixture))
        line (deref (future (.readLine ^java.io.BufferedReader r)) 30000 nil)
        ready (when line (json/parse-string line))]
    (when-not (get ready "conformanceReady")
      (p/destroy fixture)
      (throw (ex-info (str "The fixture did not start: " line) {})))
    (get ready "url")))

(println "fixture at" url "for" revision)

(let [args (cond-> ["node" runner "server" "--url" url "--output-dir" out-dir]
             scenario (into ["--scenario" scenario "--spec-version" revision])
             (and (not scenario) frozen?) (into ["--requirements" revision])
             (and (not scenario) (not frozen?)) (into ["--spec-version" revision "--suite" "active"]))
      result @(apply p/process {:dir root :out :string :err :string} args)]
  (spit (str (fs/path out-dir "runner.log")) (str (:out result) "\n" (:err result)))
  (.close ^java.io.OutputStream (:in fixture))
  (deref fixture 5000 nil)
  (p/destroy fixture))

(def checks
  (for [f (fs/glob out-dir "**/checks.json")
        :let [dir (str (fs/file-name (fs/parent f)))
              name (second (re-matches #"server-(.+)-\d{4}-\d{2}-\d{2}T.*" dir))]
        check (json/parse-string (slurp (str f)))]
    (assoc check "scenario" name)))

(def by-scenario (group-by #(get % "scenario") checks))

(defn- status-counts [cs] (frequencies (map #(get % "status") cs)))

(doseq [[s cs] (sort-by key by-scenario)
        :let [counts (status-counts cs)
              verdict (cond (pos? (get counts "FAILURE" 0)) "FAIL"
                            (pos? (get counts "WARNING" 0)) "WARN"
                            :else "ok")]]
  (println (format "%-55s %-4s %s" s verdict (pr-str counts)))
  (doseq [c cs :when (#{"FAILURE" "WARNING"} (get c "status"))]
    (println "    " (get c "status") (get c "id") "-" (or (get c "errorMessage") (get c "description")))))

(let [scenarios (vals by-scenario)
      failed (count (filter #(some (fn [c] (= "FAILURE" (get c "status"))) %) scenarios))]
  (println)
  (println (format "%d scenarios, %d with failures; %s" (count scenarios) failed (pr-str (status-counts checks))))
  (println "results in" out-dir))
