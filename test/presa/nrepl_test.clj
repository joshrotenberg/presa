(ns presa.nrepl-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [presa.client :as client]
            [presa.nrepl :as nrepl]
            [presa.nrepl.bencode :as bencode]
            [presa.part :as part]
            [presa.skill :as skill])
  (:import (java.io ByteArrayInputStream)
           (java.net ServerSocket Socket)))

(defn- round-trip [x] (bencode/read! (ByteArrayInputStream. (bencode/encode x))))

(deftest bencode
  (is (= "d4:code7:(+ 1 2)2:op4:evale" (String. (bencode/encode {"op" "eval" "code" "(+ 1 2)"}) "UTF-8")))
  (is (= {"op" "eval" "n" -12 "status" ["done" "error"] "nested" {"a" []}}
         (round-trip {:op "eval" "n" -12 "status" ["done" "error"] "nested" {"a" []}})))
  (testing "string lengths are in bytes"
    (is (= "5:héé" (String. (bencode/encode "héé") "UTF-8")))
    (is (= "héé λ" (round-trip "héé λ"))))
  (testing "the end of the stream"
    (is (nil? (bencode/read! (ByteArrayInputStream. (byte-array 0)))))
    (is (thrown? java.io.EOFException (bencode/read! (ByteArrayInputStream. (.getBytes "5:ab")))))))

;; A Babashka nREPL server for the tests, under bb and on the JVM alike

(def ^:private ^:dynamic *port* nil)

(defn- free-port [] (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- listening? [port]
  (try (.close (Socket. "127.0.0.1" (int port))) true (catch Exception _ false)))

(defn- start-repl [port]
  (let [p (.start (doto (ProcessBuilder. ["bb" "nrepl-server" (str port)])
                    (.redirectErrorStream true)
                    (.redirectOutput (java.lang.ProcessBuilder$Redirect/to (io/file "/dev/null")))))]
    (loop [n 0]
      (cond (listening? port) p
            (< n 200) (do (Thread/sleep 50) (recur (inc n)))
            :else (do (.destroy p) (throw (ex-info "bb nrepl-server did not start" {})))))))

(defn- stop-repl [^Process p] (.destroy p) (.waitFor p))

(use-fixtures :once (fn [f]
                      (let [port (free-port)
                            p (start-repl port)]
                        (try (binding [*port* port] (f))
                             (finally (stop-repl p))))))

(defn- with-repl
  "Call f with a function that calls a tool of the part and returns its
  result map."
  ([f] (with-repl {} {} f))
  ([part-opts connect-opts f]
   (part/with-client {:parts [(nrepl/part (merge {:port *port* :structured? true} part-opts))]} connect-opts
     (fn [c] (f (fn call
                  ([tool arguments] (call tool arguments {}))
                  ([tool arguments opts] (client/call-tool c tool arguments opts))))))))

(defn- structured [r] (get r "structuredContent"))

(deftest evaluating
  (with-repl
    (fn [call]
      (testing "each form's value, what was printed, and the namespace"
        (let [r (call "eval" {"code" "(println :hi) (def nrepl-test-x 41) (inc nrepl-test-x)"})]
          (is (= {"status" "done" "values" ["nil" "#'user/nrepl-test-x" "42"] "out" ":hi\n" "ns" "user"}
                 (structured r)))
          (is (= ":hi\n=> nil\n=> #'user/nrepl-test-x\n=> 42" (client/text r)))
          (is (false? (get r "isError")))))
      (testing "state persists between calls"
        (is (= ["[41 42]"] (get (structured (call "eval" {"code" "[nrepl-test-x *1]"})) "values"))))
      (testing "an exception is an error result, and *e holds it"
        (let [r (call "eval" {"code" "(/ 1 0)"})]
          (is (true? (get r "isError")))
          (is (= "error" (get (structured r) "status")))
          (is (str/includes? (get (structured r) "ex") "ArithmeticException")))
        (is (= ["\"Divide by zero\""] (get (structured (call "eval" {"code" "(ex-message *e)"})) "values"))))
      (testing "another namespace"
        (is (= ["\"1,2\""] (get (structured (call "eval" {"code" "(join \",\" [1 2])" "ns" "clojure.string"})) "values")))))))

(deftest output-is-progress
  (with-repl
    (fn [call]
      (let [seen (atom [])]
        (call "eval" {"code" "(dotimes [i 3] (println i) (Thread/sleep 30))"}
              {:on-progress #(swap! seen conj (get % "message"))})
        (is (= "0\n1\n2\n" (apply str @seen)))))))

(deftest long-output-keeps-both-ends
  (testing "by characters"
    (with-repl {:max-output-chars 100} {}
      (fn [call]
        (let [{:strs [out values]} (structured (call "eval" {"code" "(dotimes [i 200] (println i)) (vec (range 200))"}))]
          (is (str/starts-with? out "0\n1\n"))
          (is (str/ends-with? out "198\n199\n"))
          (is (str/includes? out "characters dropped"))
          (is (str/includes? (last values) "more characters"))))))
  (testing "by lines"
    (with-repl {:max-output-lines 6} {}
      (fn [call]
        (is (= "0\n1\n2\n... 65 characters dropped ...\n27\n28\n29\n"
               (get (structured (call "eval" {"code" "(dotimes [i 30] (println i))"})) "out")))
        (testing "short output is left alone"
          (is (= "a\nb\n" (get (structured (call "eval" {"code" "(println \"a\") (println \"b\")"})) "out"))))))))

(deftest timeouts-detach
  (with-repl
    (fn [call]
      (testing "a call that times out leaves its evaluation running"
        (let [r (call "eval" {"code" "(println :started) (Thread/sleep 600) (println :finishing) :slept" "timeout_ms" 150})]
          (is (false? (get r "isError")))
          (is (= "running" (get (structured r) "status")))
          (is (= ":started\n" (get (structured r) "out")))
          (is (str/includes? (get (structured r) "note") "await")))
        (testing "another evaluation is refused meanwhile"
          (let [r (call "eval" {"code" "1"})]
            (is (true? (get r "isError")))
            (is (str/includes? (client/text r) "still running"))))
        (testing "await waits for it and reports what came since"
          (is (= "running" (get (structured (call "await" {"timeout_ms" 50})) "status")))
          (is (= {"status" "done" "values" ["nil" "nil" ":slept"] "out" ":finishing\n" "ns" "user"}
                 (structured (call "await" {}))))
          (is (true? (get (call "await" {}) "isError")))))
      (testing "an evaluation that finishes unwatched keeps its result for await"
        (call "eval" {"code" "(Thread/sleep 150) :first" "timeout_ms" 50})
        (Thread/sleep 400)
        (let [m (structured (call "eval" {"code" ":second"}))]
          (is (= [":second"] (get m "values")))
          (is (str/includes? (get m "note") "await returns its result")))
        (is (= ["nil" ":first"] (get (structured (call "await" {})) "values"))))
      (testing "interrupt stops a running evaluation and keeps the session"
        (call "eval" {"code" "(def nrepl-test-kept 1)"})
        (is (= "running" (get (structured (call "eval" {"code" "(Thread/sleep 60000)" "timeout_ms" 100})) "status")))
        (let [r (call "interrupt" {})]
          (is (true? (get r "isError")))
          (is (= "interrupted" (get (structured r) "status"))))
        (is (= ["1"] (get (structured (call "eval" {"code" "nrepl-test-kept"})) "values")))
        (is (true? (get (call "interrupt" {}) "isError")))))))

(deftest cancellation-and-turns
  (with-repl
    (fn [call]
      (testing "a cancelled call interrupts its evaluation"
        (let [started (System/currentTimeMillis)
              running (future (call "eval" {"code" "(Thread/sleep 60000)" "timeout_ms" 60000}))]
          (Thread/sleep 300)
          (future-cancel running)
          (is (= [":next"] (get (structured (call "eval" {"code" ":next"})) "values")))
          (is (< (- (System/currentTimeMillis) started) 10000))))
      (testing "concurrent calls take turns"
        (is (= ["0" "1" "2"]
               (->> (range 3)
                    (mapv #(future (call "eval" {"code" (str "(Thread/sleep 150) " %) "timeout_ms" 5000})))
                    (mapv #(last (get (structured @%) "values"))))))))))

(deftest reading-input
  (testing "a client with an elicitation handler is asked for the line"
    (with-repl {} {:handlers {"elicitation/create" (fn [_] {"action" "accept" "content" {"line" "typed"}})}}
      (fn [call]
        (is (= ["\"got typed\""] (get (structured (call "eval" {"code" "(str \"got \" (read-line))"})) "values"))))))
  (testing "a client that declines gives the code end of input, and the result says so"
    (with-repl {} {:handlers {"elicitation/create" (fn [_] {"action" "decline"})}}
      (fn [call]
        (let [m (structured (call "eval" {"code" "(pr-str (read-line))"}))]
          (is (= ["\"nil\""] (get m "values")))
          (is (str/includes? (get m "note") "gave no line"))))))
  (testing "another client's code reads end of input"
    (with-repl
      (fn [call]
        (let [m (structured (call "eval" {"code" "(pr-str (read-line))"}))]
          (is (= ["\"nil\""] (get m "values")))
          (is (str/includes? (get m "note") "end of input")))))))

(deftest loading-and-lookup
  (with-repl
    (fn [call]
      (let [f (doto (java.io.File/createTempFile "presa_nrepl" ".clj") (.deleteOnExit))]
        (spit f "(ns presa-nrepl-loaded)\n(defn f \"Doc of f.\" [a] a)\n(f :loaded)\n")
        (let [r (call "load_file" {"path" (.getPath f)})]
          (is (= [":loaded"] (get (structured r) "values")))
          (testing "the result names the file and its namespace"
            (is (= (str (.getPath f) " (presa-nrepl-loaded)") (get (structured r) "loaded")))
            (is (str/ends-with? (client/text r) (str "Loaded " (.getPath f) " (presa-nrepl-loaded)")))))
        (testing "definitions point at the file"
          (is (= [(pr-str {:file (.getPath f) :line 2})]
                 (get (structured (call "eval" {"code" "(select-keys (meta #'presa-nrepl-loaded/f) [:file :line])"}))
                      "values"))))
        (is (true? (get (call "load_file" {"path" "/no/such/file.clj"}) "isError")))
        (let [r (call "lookup" {"sym" "presa-nrepl-loaded/f"})]
          (is (= "Doc of f." (get (structured r) "doc")))
          (is (str/includes? (client/text r) "([a])")))
        (is (true? (get (call "lookup" {"sym" "no-such-ns/no-such-var"}) "isError")))))))

(deftest connection
  (testing "no server on the port is a tool error"
    (part/with-client {:parts [(nrepl/part {:port (free-port)})]}
      (fn [c]
        (let [r (client/call-tool c "eval" {"code" "1"})]
          (is (true? (get r "isError")))
          (is (str/includes? (client/text r) "Cannot reach the nREPL server"))))))
  (testing "the port comes from .nrepl-port, and a restarted REPL gets a new session"
    (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "presa-nrepl-" (System/nanoTime))) .mkdirs)
          port-file (io/file dir ".nrepl-port")
          port (free-port)
          p (start-repl port)]
      (try
        (spit port-file (str port "\n"))
        (part/with-client {:parts [(nrepl/part {:dir (.getPath dir) :structured? true})]}
          (fn [c]
            (let [values #(get-in (client/call-tool c "eval" {"code" %}) ["structuredContent" "values"])]
              (is (= ["#'user/nrepl-test-gone"] (values "(def nrepl-test-gone 1)")))
              (stop-repl p)
              (is (true? (get (client/call-tool c "eval" {"code" "1"}) "isError")))
              (let [port (free-port)
                    p (start-repl port)]
                (try
                  (spit port-file (str port))
                  (let [r (client/call-tool c "eval" {"code" "(resolve 'nrepl-test-gone)"})]
                    (is (= ["nil"] (get-in r ["structuredContent" "values"])))
                    (is (str/includes? (get-in r ["structuredContent" "note"]) "new REPL session")))
                  (finally (stop-repl p)))))))
        (finally (stop-repl p) (.delete port-file) (.delete dir))))))

(deftest skills
  (testing "the part carries its guides as skills, each listed in the instructions"
    (let [{:keys [skills instructions]} (nrepl/part {:port 1})]
      (is (= nrepl/skill-names (map :name skills)))
      (doseq [s skills
              :let [text (get (:files s) "SKILL.md")]]
        (is (str/includes? instructions (skill/uri s)))
        (testing "links to other skills name ones that exist"
          (doseq [[_ linked] (re-seq #"skill://([a-z-]+)/SKILL\.md" text)]
            (is (some #{linked} nrepl/skill-names) (str (:name s) " links " linked)))))))
  (testing "a client lists and loads them"
    (part/with-client {:parts [(nrepl/part {:port 1})]}
      (fn [c]
        (is (client/skills? c))
        (is (= (map #(str "skill://" % "/SKILL.md") nrepl/skill-names) (map #(get % "uri") (client/list-skills c))))
        (is (str/includes? (:text (client/read-skill c "skill://repl-workflow/SKILL.md")) "# Working in a live REPL")))))
  (testing "the guide tool reads one, for a host without resources"
    (part/with-client {:parts [(nrepl/part {:port 1})]}
      (fn [c]
        (is (str/includes? (client/text (client/call-tool c "guide" {"name" "repl-long-running"})) "# Long-running code"))
        (is (true? (get (client/call-tool c "guide" {"name" "nope"}) "isError"))))))
  (testing "they can be left out"
    (let [p (nrepl/part {:port 1 :skills? false})]
      (is (empty? (:skills p)))
      (is (not (some #{"guide"} (map :name (:tools p)))))
      (is (not (str/includes? (:instructions p) "skill://")))
      (part/with-client {:parts [p]} (fn [c] (is (not (client/skills? c))))))))

(deftest counting-forms
  (let [form-count #'nrepl/form-count]
    (is (= 3 (form-count "(println :hi) (def x 41) ; a comment\n(inc x)")))
    (is (= 1 (form-count "(do 1 2 3)")))
    (is (= 0 (form-count "  ; nothing\n")))
    (is (= 2 (form-count "#js {:a 1} #inst \"2026-01-01\"")))
    (testing "what cannot be counted is not guessed"
      (is (nil? (form-count "(unbalanced")))
      (is (nil? (form-count "#?(:cljs 1) 2"))))
    (testing "reading runs nothing"
      (is (nil? (form-count "#=(System/exit 1)"))))))
