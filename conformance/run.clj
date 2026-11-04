;; Run the official MCP conformance runner's server leg against presa's
;; fixture and summarize the result.
;;
;;   bb conformance/run.clj [revision] [scenario] [--update-baseline]
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
;;
;; A full run (no scenario) is compared with conformance/baselines/<revision>.json,
;; which records the status of every check of every scenario. Any difference
;; fails the run, including a check that starts passing, so a baseline only
;; changes in review: --update-baseline rewrites it from the run.
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

(def runner-version
  (get (json/parse-string (slurp (str (fs/path (fs/parent (fs/parent runner)) "package.json")))) "version"))

;; PRESA_CONFORMANCE_UNPINNED=1 runs another runner build (the canary)
(let [pkg {"version" runner-version}]
  (when-not (or (= pinned (get pkg "version")) (System/getenv "PRESA_CONFORMANCE_UNPINNED"))
    (throw (ex-info (str "The runner is " (get pkg "version") ", not " pinned) {}))))

(def flags (set (filter #(str/starts-with? % "--") *command-line-args*)))
(def positional (remove #(str/starts-with? % "--") *command-line-args*))
(def revision (or (some #(re-matches #"\d{4}-\d{2}-\d{2}" %) positional) "2026-07-28"))
(def scenario (first (remove #(re-matches #"\d{4}-\d{2}-\d{2}" %) positional)))
(def frozen? (contains? #{"2026-07-28" "2025-11-25"} revision))
(def out-dir (str (fs/path root "tmp/conformance" revision
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

(println "fixture at" url "for" revision "with runner" runner-version)

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

;; Baseline

(def inventory
  "Every check's statuses, by scenario and check id; statuses sorted, since a
  check id can repeat within a scenario."
  (into (sorted-map)
        (map (fn [[s cs]]
               [s (into (sorted-map)
                        (map (fn [[id xs]] [id (vec (sort (map #(get % "status") xs)))]))
                        (group-by #(get % "id") cs))]))
        by-scenario))

(def baseline-file (str (fs/path root "conformance/baselines" (str revision ".json"))))

(defn- differences [expected actual]
  (for [s (sort (set (concat (keys expected) (keys actual))))
        id (sort (set (concat (keys (get expected s)) (keys (get actual s)))))
        :let [e (get-in expected [s id]) a (get-in actual [s id])]
        :when (not= e a)]
    [s id e a]))

(cond
  scenario nil

  (empty? inventory)
  (do (println "The runner produced no results; see" (str (fs/path out-dir "runner.log")))
      (System/exit 1))

  (contains? flags "--update-baseline")
  (do (fs/create-dirs (fs/parent baseline-file))
      (spit baseline-file (str (json/generate-string {"revision" revision "runner" pinned "checkInventory" inventory}
                                                     {:pretty true})
                               "\n"))
      (println "wrote" baseline-file))

  (not (fs/exists? baseline-file))
  (do (println "No baseline at" baseline-file "; run with --update-baseline to record one")
      (System/exit 1))

  :else
  (let [expected (get (json/parse-string (slurp baseline-file)) "checkInventory")
        diffs (differences expected inventory)]
    (if (empty? diffs)
      (println "matches" baseline-file)
      (do (println)
          (println (count diffs) "differences from" baseline-file ":")
          (doseq [[s id e a] diffs]
            (println (format "  %s / %s: expected %s, got %s" s id (pr-str e) (pr-str a))))
          (println "If the change is intended, run with --update-baseline and review the diff.")
          (System/exit 1)))))
