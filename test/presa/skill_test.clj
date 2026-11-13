(ns presa.skill-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [presa.client :as client]
            [presa.part :as part]
            [presa.protocol :as protocol]
            [presa.server :as server]
            [presa.skill :as skill]))

(defn- md [name description & body]
  (str "---\nname: " name "\ndescription: " description "\n---\n" (apply str body)))

(deftest front-matter
  (testing "scalars, quoted strings, numbers, booleans and comments"
    (is (= {"name" "pdf-processing" "description" "Extract text, fill forms" "license" "Apache-2.0"
            "quoted" "a: b # not a comment" "single" "it's" "count" 3 "ratio" 1.5 "enabled" true "none" nil}
           (skill/frontmatter (str "---\n# a comment\nname: pdf-processing\ndescription: Extract text, fill forms\n"
                                   "license: Apache-2.0   # trailing\nquoted: \"a: b # not a comment\"\n"
                                   "single: 'it''s'\ncount: 3\nratio: 1.5\nenabled: true\nnone:\n---\nbody\n")))))
  (testing "nested maps, lists and block scalars"
    (is (= {"name" "x" "metadata" {"version" "2.1.0" "author" "team" "deep" {"k" "v"}}
            "tags" ["a" "b c"]
            "description" "One line folded into the next.\n"
            "literal" "kept\n  as written"}
           (skill/frontmatter (str "---\nname: x\nmetadata:\n  version: \"2.1.0\"\n  author: team\n  deep:\n    k: v\n"
                                   "tags:\n  - a\n  - b c\ndescription: >\n  One line folded\n  into the next.\n"
                                   "literal: |-\n  kept\n    as written\n---\n")))))
  (testing "Windows line endings"
    (is (= {"name" "x" "description" "d"} (skill/frontmatter "---\r\nname: x\r\ndescription: d\r\n---\r\nbody"))))
  (testing "what it does not read throws, naming :frontmatter"
    (doseq [bad ["---\nname: x\ntags: [a, b]\n---\n" "---\nname: x\ndescription: a: b\n---\n"
                 "---\nname: x\ndescription: one\n  two\n---\n" "---\nname: &a x\n---\n" "no front matter"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"front matter" (skill/frontmatter bad)) bad))))

(deftest ambiguous-values-are-refused
  (doseq [v ["yes" "No" "on" "off" "1e3" "012" "0x1F" "1_000" ".inf" "1:30" "2026-01-02" "2026-01-02T10:00:00Z"
             "12345678901234567890" "1." "+.5"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"front matter"
                          (skill/frontmatter (str "---\nname: x\nv: " v "\n---\n")))
        v))
  (testing "as keys too"
    (doseq [k ["on" "off" "yes" "no" "true" "null" "1" "1.5"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"front matter"
                            (skill/frontmatter (str "---\nname: x\n" k ": v\n---\n")))
          k)))
  (testing "quoted, they are strings"
    (is (= {"name" "x" "v" "yes" "w" "012"} (skill/frontmatter "---\nname: x\nv: \"yes\"\nw: '012'\n---\n")))))

(deftest agrees-with-a-yaml-library
  ;; Babashka bundles clj-yaml (SnakeYAML); the JVM classpath has no YAML reader
  (when (System/getProperty "babashka.version")
    (let [parse (requiring-resolve 'clj-yaml.core/parse-string)
          plain (fn plain [v] (cond (map? v) (into {} (map (fn [[k x]] [k (plain x)])) v)
                                    (sequential? v) (mapv plain v)
                                    :else v))
          blocks (concat
                  ["name: pdf-processing\ndescription: Extract text, fill forms\nlicense: Apache-2.0   # trailing"
                   "name: x\nquoted: \"a: b # not a comment\"\nsingle: 'it''s'\nescaped: \"tab\\there \\\"q\\\" back\\\\slash\""
                   "name: x\ncount: 3\nneg: -7\nratio: 1.5\nenabled: true\ndisabled: false\nnone:\ntilde: ~\nversion: 2.1.0"
                   "name: x\nmetadata:\n  version: \"2.1.0\"\n  author: team\n  deep:\n    k: v\ntags:\n  - a\n  - b c"
                   "name: x\ndescription: >\n  One line folded\n  into the next.\n\n  A new paragraph.\nliteral: |-\n  kept\n    as written\nclip: |\n  one\n  two"
                   "# comment\nname: x\n\ndescription: Has a # hash, a colon:here and a dash - inside\nallowed-tools: Bash(git:*) Read"]
                  [(second (re-find #"(?s)\A---\n(.*?)\n---\n"
                                    (slurp (io/resource "presa/skill_fixture/git-workflow/SKILL.md"))))])]
      (doseq [block blocks]
        (is (= (plain (parse (str block "\n") :keywords false))
               (skill/frontmatter (str "---\n" block "\n---\n")))
            block)))))

(deftest building
  (let [s (skill/skill "acme/billing/refunds" {"SKILL.md" (md "refunds" "Process refunds") "examples/email.md" "Dear"})]
    (is (= "refunds" (:name s)))
    (is (= "skill://acme/billing/refunds/SKILL.md" (skill/uri s)))
    (is (= "skill://acme/billing/refunds" (skill/root-uri s)))
    (testing "the entry lists every file with its digest and size, SKILL.md first"
      (is (= {"uri" "skill://acme/billing/refunds/SKILL.md"
              "frontmatter" {"name" "refunds" "description" "Process refunds"}
              "resources" [{"uri" "skill://acme/billing/refunds/SKILL.md"
                            "digest" (skill/digest (md "refunds" "Process refunds"))
                            "size" (count (md "refunds" "Process refunds"))}
                           {"uri" "skill://acme/billing/refunds/examples/email.md"
                            "digest" "sha256:1651f0348c7d0060bcc4f6ec3ad7ea82a73d7ac8fbd0c3cdd4bc2ff8b9c1d382"
                            "size" 4}]}
             (update-in (skill/entry s) ["resources" 1 "digest"]
                        (fn [d] (if (re-matches #"sha256:[0-9a-f]{64}" d)
                                  "sha256:1651f0348c7d0060bcc4f6ec3ad7ea82a73d7ac8fbd0c3cdd4bc2ff8b9c1d382"
                                  d)))))
      (is (= "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad" (skill/digest "abc")))
      (testing "sizes are in bytes"
        (is (= 3 (get-in (skill/entry (skill/skill "x" {"SKILL.md" (md "x" "d") "a.txt" "hé"})) ["resources" 1 "size"])))))
    (testing "its files as resources"
      (is (= [{:uri "skill://acme/billing/refunds/SKILL.md" :name "refunds" :mime-type "text/markdown"
               :description "Process refunds"}
              {:uri "skill://acme/billing/refunds/examples/email.md" :name "examples/email.md" :mime-type "text/markdown"}]
             (map #(dissoc % :handler :size) (skill/resources s))))))
  (testing "a file made on each read makes the skill dynamic"
    (is (= "dynamic" (get (skill/entry (skill/skill "x" {"SKILL.md" (md "x" "d") "now.txt" (fn [] "t")})) "resources"))))
  (testing "front matter given as a map"
    (is (= {"name" "x" "description" "d" "tags" ["a"]}
           (get (skill/entry (skill/skill "x" {"SKILL.md" "---\nname: x\ndescription: d\ntags: [a]\n---\n"}
                                          {:frontmatter {"name" "x" "description" "d" "tags" ["a"]}}))
                "frontmatter"))))
  (testing "what is not a skill"
    (doseq [[path files] [["x" {"README.md" "hi"}]
                          ["y" {"SKILL.md" (md "x" "d")}]
                          ["Bad_Name" {"SKILL.md" (md "Bad_Name" "d")}]
                          ["x" {"SKILL.md" "---\nname: x\n---\n"}]
                          ["x" {"SKILL.md" (md "x" "d") "../escape" "no"}]
                          ["a//x" {"SKILL.md" (md "x" "d")}]]]
      (is (thrown? clojure.lang.ExceptionInfo (skill/skill path files)) (pr-str path (keys files))))))

(deftest from-files
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "presa-skill-" (System/nanoTime)) "git-workflow")]
    (try
      (io/make-parents (io/file dir "templates" "regional" "x"))
      (spit (io/file dir "SKILL.md") (md "git-workflow" "Branching and commits"))
      (spit (io/file dir "templates" "pr.md") "## Summary")
      (spit (io/file dir "templates" "regional" "eu.md") "EU")
      (with-open [out (io/output-stream (io/file dir "logo.bin"))] (.write out (byte-array [0 -1 -2 3])))
      (let [s (skill/from-dir dir)]
        (is (= #{"SKILL.md" "templates/pr.md" "templates/regional/eu.md" "logo.bin"} (set (keys (:files s)))))
        (is (string? (get (:files s) "templates/pr.md")))
        (is (bytes? (get (:files s) "logo.bin")))
        (is (= "skill://team/git-workflow/SKILL.md" (skill/uri (skill/from-dir dir "team/git-workflow"))))
        (testing "directories and their direct children"
          (is (= {"skill://git-workflow" ["SKILL.md" "logo.bin" "templates"]
                  "skill://git-workflow/templates" ["pr.md" "regional"]
                  "skill://git-workflow/templates/regional" ["eu.md"]}
                 (update-vals (skill/directories s) #(mapv (fn [r] (get r "name")) %))))
          (is (= {"uri" "skill://git-workflow/templates" "name" "templates" "mimeType" "inode/directory"}
                 (last (get (skill/directories s) "skill://git-workflow"))))))
      (finally (run! #(.delete ^java.io.File %) (reverse (file-seq (.getParentFile dir)))))))
  (testing "from the classpath"
    (let [s (skill/from-classpath "presa/skill_fixture/git-workflow" ["SKILL.md" "references/branches.md"])]
      (is (= "git-workflow" (:name s)))
      (is (= {"version" "1.0.0" "owner" "presa tests"} (get (:frontmatter s) "metadata")))
      (is (= ["skill://git-workflow/SKILL.md" "skill://git-workflow/references/branches.md"]
             (map #(get % "uri") (get (skill/entry s) "resources"))))
      (is (= ["SKILL.md"] (keys (:files (skill/from-classpath "presa/skill_fixture/git-workflow")))))
      (is (= "skill://team/git-workflow/SKILL.md"
             (skill/uri (skill/from-classpath "presa/skill_fixture/git-workflow" ["SKILL.md"] "team/git-workflow"))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (skill/from-classpath "presa/skill_fixture/git-workflow" ["SKILL.md" "nope.md"]))))))

(def ^:private review
  (skill/skill "code-review" {"SKILL.md" (md "code-review" "Review a change" "# Review\n")
                              "references/checklist.md" "- tests"
                              "scripts/lint.sh" "#!/bin/sh\n"}))

(def ^:private refunds
  (skill/skill "acme/refunds" {"SKILL.md" (md "refunds" "Process refunds")}))

(defn- with-skills [opts f]
  (let [srv (server/server (merge {:name "s" :version "1" :skills [review refunds]} opts))
        c (client/connect (client/in-process srv))]
    (try (f c) (finally (client/close c)))))

(defn- error-code [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (:code (ex-data e)))))

(deftest serving
  (with-skills {}
    (fn [c]
      (testing "the extension is declared, with resources"
        (let [caps (:server-capabilities (client/info c))]
          (is (= {"directoryRead" true} (get-in caps ["extensions" skill/extension])))
          (is (contains? caps "resources"))
          (is (client/skills? c))))
      (testing "skills/list"
        (is (= [(skill/entry review) (skill/entry refunds)] (client/list-skills c)))
        (let [r (client/request c "skills/list" {})]
          (is (contains? r "ttlMs"))
          (is (contains? r "cacheScope"))))
      (testing "skills/get, for a skill and for what is not one"
        (is (= (skill/entry refunds) (client/get-skill c "skill://acme/refunds/SKILL.md")))
        (is (= -32602 (error-code #(client/get-skill c "skill://nope/SKILL.md"))))
        (is (= -32602 (error-code #(client/get-skill c "skill://code-review/references/checklist.md"))))
        (is (= -32602 (error-code #(client/request c "skills/get" {})))))
      (testing "each file is a resource"
        (is (= ["skill://code-review/SKILL.md" "skill://code-review/references/checklist.md"
                "skill://code-review/scripts/lint.sh" "skill://acme/refunds/SKILL.md"]
               (map #(get % "uri") (client/list-resources c))))
        (is (= {"uri" "skill://code-review/SKILL.md" "name" "code-review" "description" "Review a change"
                "mimeType" "text/markdown" "size" (count (md "code-review" "Review a change" "# Review\n"))}
               (first (client/list-resources c))))
        (is (= "- tests" (get (first (client/read-resource c "skill://code-review/references/checklist.md")) "text"))))
      (testing "resources/directory/read"
        (is (= [["SKILL.md" "text/markdown"] ["references" "inode/directory"] ["scripts" "inode/directory"]]
               (map (juxt #(get % "name") #(get % "mimeType")) (client/read-directory c "skill://code-review"))))
        (is (= ["skill://code-review/references/checklist.md"]
               (map #(get % "uri") (client/read-directory c "skill://code-review/references"))))
        (is (= -32602 (error-code #(client/read-directory c "skill://code-review/SKILL.md"))))
        (is (= -32602 (error-code #(client/read-directory c "skill://nope")))))
      (testing "loading a skill checks it"
        (let [{:keys [entry text]} (client/read-skill c "skill://code-review/SKILL.md")]
          (is (str/ends-with? text "# Review\n"))
          (is (= "- tests" (client/read-skill-file c entry "skill://code-review/references/checklist.md")))
          (testing "a file the entry does not list"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a file of the skill"
                                  (client/read-skill-file c entry "skill://acme/refunds/SKILL.md"))))
          (testing "content that changed since the entry"
            (let [stale (assoc-in entry ["resources" 0 "digest"] (skill/digest "other"))]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"digest"
                                    (client/read-skill-file c stale "skill://code-review/SKILL.md"))))
            (let [stale (assoc-in entry ["resources" 0 "size"] 1)]
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bytes"
                                    (client/read-skill-file c stale "skill://code-review/SKILL.md")))))
          (testing "front matter that differs from the entry"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"front matter"
                                  (skill/check-frontmatter (assoc-in entry ["frontmatter" "description"] "Other") text)))))))))

(deftest pagination-and-eras
  (testing "skills/list pages; an entry is never split"
    (with-skills {:page-size 1}
      (fn [c]
        (let [first-page (client/request c "skills/list" {})]
          (is (= [(skill/entry review)] (get first-page "skills")))
          (is (string? (get first-page "nextCursor"))))
        (is (= 2 (count (client/list-skills c))))
        (is (= 3 (count (client/read-directory c "skill://code-review")))))))
  (testing "an initialize-era client is served too"
    (let [srv (server/server {:name "s" :version "1" :skills [review] :versions protocol/versions})
          c (client/connect (client/in-process srv) {:versions ["2025-11-25"]})]
      (try
        (is (= "2025-11-25" (:version (client/info c))))
        (is (= [(skill/entry review)] (client/list-skills c)))
        (finally (client/close c))))))

(deftest without-skills
  (testing "a server without :skills does not serve the methods"
    (let [c (client/connect (client/in-process (server/server {:name "s" :version "1"})))]
      (try
        (is (not (client/skills? c)))
        (is (= -32601 (error-code #(client/list-skills c))))
        (finally (client/close c)))))
  (testing "an empty vector declares the extension with no skills"
    (with-skills {:skills []}
      (fn [c]
        (is (client/skills? c))
        (is (= [] (client/list-skills c)))))))

(deftest scopes
  (let [srv (server/server {:name "s" :version "1" :skills [review refunds]
                            :required-scopes {[:resource "skill://acme/refunds/SKILL.md"] ["billing"]}})
        c (client/connect (client/in-process srv))]
    (try
      (testing "a skill the caller may not read is not listed or given"
        (is (= ["skill://code-review/SKILL.md"] (map #(get % "uri") (client/list-skills c))))
        (is (= -32003 (error-code #(client/get-skill c "skill://acme/refunds/SKILL.md")))))
      (finally (client/close c)))))

(deftest in-parts
  (testing "parts carry skills, and a duplicate path is a duplicate"
    (is (= ["skill://code-review/SKILL.md" "skill://acme/refunds/SKILL.md"]
           (part/with-client {:parts [{:skills [review]} {:skills [refunds]}]}
             (fn [c] (mapv #(get % "uri") (client/list-skills c))))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Duplicate skill"
                          (part/combine [{:skills [review]} {:skills [review]}])))
    (is (= [["skill" "skill://code-review/SKILL.md"]]
           (:dropped (part/combine [{:skills [review]} {:skills [review]}] {:duplicates :first})))))
  (testing "parts without skills make a server without the extension"
    (part/with-client {:parts [{:tools [{:name "t" :handler (fn [_ _] "x")}]}]}
      (fn [c] (is (not (client/skills? c)))))))
