(ns presa.skill
  "Agent Skills served over MCP: the Skills extension
  (io.modelcontextprotocol/skills, SEP-2640).

  A skill is a directory of files with a SKILL.md at its root, whose YAML
  front matter names and describes it (https://agentskills.io/specification).
  Over MCP each file is a resource, skill://<path>/<file>, and the server
  answers skills/list and skills/get with an entry per skill: its SKILL.md
  URI, its front matter, and the digest and size of every file.

  Build skills here and hand them to presa.server/server as :skills, or to a
  part as its :skills:

      (presa.server/server {:name \"s\" :version \"1\"
                            :skills [(skill/from-dir \"skills/code-review\")]})

  `verify` and `check-frontmatter` are the checks a host makes on what it
  reads; presa.client/read-skill applies them."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.security MessageDigest)
           (java.util Base64)))

(def extension
  "The Skills extension's id in capabilities."
  "io.modelcontextprotocol/skills")

(def max-files
  "The most files a skill may have and still load in every conforming host."
  512)

(def max-bytes
  "The largest total size of a skill's files, likewise."
  16777216)

(def directory-mime-type "inode/directory")

;; Front matter: the subset of YAML that skill front matter uses

(defn- unsupported [what]
  (throw (ex-info (str "Unsupported YAML in skill front matter: " (str/trim (str what))
                       "; pass the skill's :frontmatter as a map")
                  {:kind :invalid-input :yaml what})))

(defn- scalar [s]
  (let [s (str/trim s)]
    (cond
      (re-matches #"\"(?:[^\"\\]|\\.)*\"" s)
      (str/replace (subs s 1 (dec (count s))) #"\\(.)"
                   (fn [[_ c]] (case c "n" "\n" "t" "\t" ("\"" "\\" "/") c (unsupported s))))

      (re-matches #"'(?:[^']|'')*'" s)
      (str/replace (subs s 1 (dec (count s))) "''" "'")

      (re-find #"^[\[\]{}&*!|>%@`\"',?]" s) (unsupported s)

      :else
      (let [s (str/trim (str/replace s #"\s#.*$" ""))]
        (cond
          (or (re-find #":\s" s) (str/ends-with? s ":")) (unsupported s)
          (#{"true" "True" "TRUE"} s) true
          (#{"false" "False" "FALSE"} s) false
          (#{"null" "Null" "NULL" "~" ""} s) nil
          (re-matches #"[-+]?(?:0|[1-9]\d{0,17})" s) (parse-long s)
          (re-matches #"[-+]?(?:0|[1-9]\d*)\.\d+" s) (parse-double s)
          ;; a version such as 2.1.0 is a string to every YAML reader
          (re-matches #"\d+(?:\.\d+){2,}" s) s
          ;; what YAML readers disagree on, by version or by library: yes and
          ;; no, exponents, octal and hex, underscores, .inf, 1:30, dates
          (or (re-matches #"(?i)y|n|yes|no|on|off|[-+]?\.(?:inf|nan)" s)
              (re-matches #"[-+]?\.?\d[\dA-Fa-f_.:xXoOeE+-]*" s)
              (re-find #"^\d{4}-\d{2}-\d{2}" s))
          (unsupported (str s " (quote it to make it a string)"))
          :else s)))))

(defn- indent-of [line] (count (re-find #"^ *" line)))

(defn- skip? [line] (or (str/blank? line) (str/starts-with? (str/triml line) "#")))

(defn- block-scalar
  "The text of a | or > block scalar made of lines."
  [style lines]
  (let [lines (reverse (drop-while str/blank? (reverse lines)))
        indent (apply min (map indent-of (remove str/blank? lines)))
        lines (map #(if (str/blank? %) "" (subs % indent)) lines)
        text (if (str/starts-with? style "|")
               (str/join "\n" lines)
               ;; folded: a line break is a space, a blank line a line break
               (->> (partition-by str/blank? lines)
                    (map #(if (str/blank? (first %)) (apply str (repeat (count %) "\n")) (str/join " " %)))
                    (apply str)))]
    (if (str/ends-with? style "-") text (str text "\n"))))

(declare parse-map)

(defn- parse-list [lines indent]
  (mapv (fn [line]
          (let [[_ item] (re-matches #"-\s+(.*)" (subs line indent))]
            (when (or (nil? item) (not= indent (indent-of line)) (re-find #"^[^\"'].*:(\s|$)" item))
              (unsupported line))
            (scalar item)))
        (remove skip? lines)))

(defn- parse-map
  "The map that lines, each indented by at least indent, spell."
  [lines indent]
  (loop [lines (drop-while skip? lines) m {}]
    (if-let [line (first lines)]
      (let [[_ k v] (when (= indent (indent-of line))
                      (re-matches #"([A-Za-z0-9_][^:#]*?):(?:\s+(.*))?" (subs line indent)))
            _ (when (or (nil? k) (str/includes? line "\t")) (unsupported line))
            ;; a key is a scalar too: YAML 1.1 reads the key on as true
            _ (when (or (re-matches #"(?i)y|n|yes|no|on|off|true|false|null" k)
                        (re-matches #"[-+]?\.?\d[\dA-Fa-f_.:xXoOeE+-]*" k))
                (unsupported (str line " (a key YAML readers read differently)")))
            v (some-> v str/trim not-empty)
            [children more] (split-with #(or (str/blank? %) (> (indent-of %) indent)) (rest lines))
            nested (remove skip? children)]
        (recur (drop-while skip? more)
               (assoc m k
                      (cond
                        (and v (re-matches #"[|>][-+]?" v))
                        (if (seq nested) (block-scalar v children) "")

                        v (if (seq nested) (unsupported (first nested)) (scalar v))
                        (empty? nested) nil
                        (str/starts-with? (str/triml (first nested)) "- ") (parse-list nested (indent-of (first nested)))
                        :else (parse-map nested (indent-of (first nested)))))))
      m)))

(defn frontmatter
  "The YAML front matter that text, a SKILL.md, begins with, as a map with
  string keys. It reads what skill front matter is made of: scalars, quoted
  strings, | and > blocks, nested maps and lists of scalars. Anything else
  throws, and so does a value that YAML readers read differently from one
  another (yes, 1e3, 012, a date): a host compares the front matter it
  reads with the server's, and refuses the skill when they differ. Quote
  such a value, or give the skill its :frontmatter as a map."
  [text]
  (let [[_ block] (re-find #"(?s)\A---[ \t]*\n(.*?)\n---[ \t]*(?:\n|\z)" (str/replace (str text) "\r\n" "\n"))]
    (when-not block
      (throw (ex-info "A SKILL.md begins with YAML front matter between --- lines" {:kind :invalid-input})))
    (parse-map (str/split-lines block) 0)))

;; Bytes

(defn- utf8 ^bytes [^String s] (.getBytes s StandardCharsets/UTF_8))

(defn- raw
  "The bytes of a file's value: a string's UTF-8, or the byte array."
  ^bytes [v]
  (cond (string? v) (utf8 v)
        (bytes? v) v
        :else (throw (ex-info "A skill file is a string, a byte array or a function returning one"
                              {:kind :invalid-input :value v}))))

(defn- text-or-bytes
  "bytes as a string when they are valid UTF-8, else as they are."
  [^bytes b]
  (try (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                       (.onMalformedInput CodingErrorAction/REPORT)
                       (.onUnmappableCharacter CodingErrorAction/REPORT))
                     (ByteBuffer/wrap b)))
       (catch CharacterCodingException _ b)))

(defn digest
  "The digest of data (a string or bytes) as the extension writes it:
  sha256: and 64 lowercase hexadecimal characters."
  [data]
  (str "sha256:"
       (apply str (map #(format "%02x" (bit-and 0xff (long %)))
                       (.digest (MessageDigest/getInstance "SHA-256") (raw data))))))

;; Skills

(def ^:private name-pattern #"[a-z0-9]+(?:-[a-z0-9]+)*")

(defn- invalid [message data]
  (throw (ex-info message (assoc data :kind :invalid-input))))

(defn skill
  "A skill from its path and files.

  path locates the skill among the server's skills: one or more segments
  separated by /, the last being the skill's name, as in \"code-review\" or
  \"acme/billing/refunds\". files maps each file's path within the skill
  (\"SKILL.md\", \"references/FORMS.md\") to its content: a string, a byte
  array, or a function of no arguments returning one, for content made on
  each read. A skill with such a file is listed as dynamic, with no digests.

  opts: :frontmatter, the SKILL.md front matter as a map with string keys,
  for front matter `frontmatter` does not read. It must say what the file
  says.

  Throws when there is no SKILL.md, the front matter lacks name or
  description, the name is not the path's last segment, or the skill is
  larger than `max-files` or `max-bytes`."
  ([path files] (skill path files {}))
  ([path files opts]
   (let [segments (str/split (str path) #"/" -1)
         _ (when (or (str/blank? path) (some str/blank? segments))
             (invalid (str "A skill path is segments separated by /: " (pr-str path)) {:path path}))
         _ (doseq [f (keys files)]
             (when (or (not (string? f)) (str/blank? f) (str/starts-with? f "/")
                       (some #{"" "." ".."} (str/split f #"/" -1)))
               (invalid (str "A skill file path is relative to the skill: " (pr-str f)) {:path path :file f})))
         main (get files "SKILL.md")
         _ (when (nil? main) (invalid (str "The skill " path " has no SKILL.md") {:path path}))
         dynamic? (boolean (some fn? (vals files)))
         fm (or (:frontmatter opts)
                (frontmatter (let [v (if (fn? main) (main) main)]
                               (if (string? v) v (String. (raw v) StandardCharsets/UTF_8)))))
         skill-name (get fm "name")]
     (when-not (and (string? skill-name) (re-matches name-pattern skill-name) (<= (count skill-name) 64))
       (invalid (str "The skill " path " needs a name of lowercase letters, digits and single hyphens, at most 64 characters")
                {:path path :name skill-name}))
     (when-not (= skill-name (last segments))
       (invalid (str "The skill at " path " is named " skill-name "; the last path segment must be its name")
                {:path path :name skill-name}))
     (when-not (and (string? (get fm "description")) (not (str/blank? (get fm "description"))))
       (invalid (str "The skill " path " needs a description") {:path path}))
     (when (> (count files) (long max-files))
       (invalid (str "The skill " path " has more than " max-files " files") {:path path}))
     (when-not dynamic?
       (when (> (long (reduce + (map #(alength (raw %)) (vals files)))) (long max-bytes))
         (invalid (str "The skill " path " is larger than " max-bytes " bytes") {:path path})))
     {:path path :name skill-name :frontmatter fm :files files :dynamic? dynamic?})))

(defn- walk
  "Each file under dir as [relative-path content]."
  [^java.io.File dir]
  (let [root (.toPath dir)]
    (for [^java.io.File f (sort (file-seq dir))
          :when (.isFile f)]
      [(str/join "/" (map str (.relativize root (.toPath f))))
       (text-or-bytes (java.nio.file.Files/readAllBytes (.toPath f)))])))

(defn from-dir
  "The skill in the directory dir, every file under it included. Its path is
  the directory's name, or path when given, for a skill under a prefix."
  ([dir] (from-dir dir nil))
  ([dir path]
   (let [d (.getCanonicalFile (io/file dir))]
     (when-not (.isDirectory d)
       (invalid (str "No skill directory " dir) {:dir (str dir)}))
     (skill (or path (.getName d)) (into {} (walk d))))))

(defn from-classpath
  "The skill whose files are the classpath resources root/file, for each of
  files (\"SKILL.md\" when none are named): a classpath, unlike a
  directory, cannot be listed. Its path is root's last segment, or path."
  ([root] (from-classpath root ["SKILL.md"]))
  ([root files] (from-classpath root files nil))
  ([root files path]
   (skill (or path (last (str/split root #"/")))
          (into {} (map (fn [f]
                          (let [r (or (io/resource (str root "/" f))
                                      (invalid (str "No resource " root "/" f " on the classpath") {:resource (str root "/" f)}))]
                            [f (with-open [in (io/input-stream r)]
                                 (text-or-bytes (.readAllBytes in)))])))
                files))))

(defn root-uri
  "The URI of the skill's directory."
  [skill]
  (str "skill://" (:path skill)))

(defn file-uri
  "The URI of a file of the skill."
  [skill file]
  (str (root-uri skill) "/" file))

(defn uri
  "The URI of the skill's SKILL.md, which identifies the skill."
  [skill]
  (file-uri skill "SKILL.md"))

(defn- ordered-files [skill]
  (cons "SKILL.md" (sort (remove #{"SKILL.md"} (keys (:files skill))))))

(defn entry
  "The skill's entry in skills/list and skills/get: its URI, its front
  matter, and the digest and size of each file, or \"dynamic\" when its
  content is made on each read."
  [skill]
  {"uri" (uri skill)
   "frontmatter" (:frontmatter skill)
   "resources" (if (:dynamic? skill)
                 "dynamic"
                 (mapv (fn [f]
                         (let [b (raw (get (:files skill) f))]
                           {"uri" (file-uri skill f) "digest" (digest b) "size" (alength b)}))
                       (ordered-files skill)))})

(def ^:private mime-types
  {"md" "text/markdown" "txt" "text/plain" "json" "application/json" "yaml" "application/yaml"
   "yml" "application/yaml" "edn" "application/edn" "clj" "text/x-clojure" "py" "text/x-python"
   "sh" "application/x-sh" "js" "text/javascript" "html" "text/html" "css" "text/css"
   "csv" "text/csv" "xml" "application/xml" "png" "image/png" "jpg" "image/jpeg" "pdf" "application/pdf"})

(defn- mime-type [file content]
  (or (mime-types (some-> (re-find #"\.([A-Za-z0-9]+)$" file) second str/lower-case))
      (if (bytes? content) "application/octet-stream" "text/plain")))

(defn resources
  "The skill's files as resources for presa.server/server. The SKILL.md
  carries the skill's name and description; another file is named for its
  path within the skill."
  [skill]
  (mapv (fn [f]
          (let [v (get (:files skill) f)
                main? (= "SKILL.md" f)]
            (cond-> {:uri (file-uri skill f)
                     :name (if main? (:name skill) f)
                     :mime-type (if main? "text/markdown" (mime-type f (when-not (fn? v) v)))
                     :handler (if (fn? v) (fn [_] (v)) (fn [_] v))}
              main? (assoc :description (get (:frontmatter skill) "description"))
              (not (fn? v)) (assoc :size (alength (raw v))))))
        (ordered-files skill)))

(defn directories
  "The skill's directories, each URI to its direct children as resources on
  the wire: files as resources/list shows them, directories with the
  mimeType inode/directory."
  [skill]
  (let [files (resources skill)
        root (root-uri skill)
        wire (fn [{:keys [uri mime-type size description]}]
               (cond-> {"uri" uri "name" (last (str/split uri #"/")) "mimeType" mime-type}
                 size (assoc "size" size)
                 description (assoc "description" description)))
        dirs (->> (keys (:files skill))
                  (mapcat (fn [f] (let [parts (pop (str/split f #"/"))]
                                    (map #(str/join "/" (cons root (take % parts))) (range (inc (count parts)))))))
                  (into (sorted-set root)))]
    (into {}
          (map (fn [dir]
                 [dir (vec (concat
                            (for [f files :when (= dir (str/replace (:uri f) #"/[^/]+$" ""))] (wire f))
                            (for [d dirs :when (and (not= d dir) (= dir (str/replace d #"/[^/]+$" "")))]
                              {"uri" d "name" (last (str/split d #"/")) "mimeType" directory-mime-type})))]))
          dirs)))

;; What a host checks

(defn- content-bytes ^bytes [content]
  (cond (string? content) (utf8 content)
        (bytes? content) content
        (contains? content "text") (utf8 (get content "text"))
        (contains? content "blob") (.decode (Base64/getDecoder) ^String (get content "blob"))
        :else (throw (ex-info "Resource contents have neither text nor blob" {:kind :skill-verification}))))

(defn verify
  "Check content read from file-uri against a skill's entry: the entry lists
  the file, and the content has the size and digest the entry gives.
  content is a string, a byte array, or a resources/read contents map with
  \"text\" or \"blob\". Returns the content; a mismatch throws ex-info with
  :kind :skill-verification. An entry whose resources are \"dynamic\" has
  nothing to check against, and passes."
  [entry file-uri content]
  (let [listed (get entry "resources")]
    (cond
      (= "dynamic" listed) content

      (not (sequential? listed))
      (throw (ex-info "The skill entry has neither a resources array nor \"dynamic\""
                      {:kind :skill-verification :uri (get entry "uri")}))

      :else
      (let [{:strs [size] expected "digest"} (or (some #(when (= file-uri (get % "uri")) %) listed)
                                                 (throw (ex-info (str file-uri " is not a file of the skill")
                                                                 {:kind :skill-verification :uri file-uri})))
            b (content-bytes content)]
        (when-not (= size (alength b))
          (throw (ex-info (str file-uri " is " (alength b) " bytes; the skill's entry says " size)
                          {:kind :skill-verification :uri file-uri})))
        (when-not (= expected (digest b))
          (throw (ex-info (str file-uri " does not match the digest in the skill's entry")
                          {:kind :skill-verification :uri file-uri})))
        content))))

(defn check-frontmatter
  "Check that the SKILL.md text carries the front matter the entry lists.
  Returns the text; a difference throws ex-info with :kind
  :skill-verification."
  [entry text]
  (when-not (= (get entry "frontmatter") (frontmatter text))
    (throw (ex-info (str "The front matter of " (get entry "uri") " differs from the skill's entry")
                    {:kind :skill-verification :uri (get entry "uri")})))
  text)
