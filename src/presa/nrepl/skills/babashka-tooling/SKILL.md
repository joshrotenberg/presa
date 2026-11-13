---
name: babashka-tooling
description: How to use a Babashka REPL to build general purpose tooling for any project, not only Clojure ones. Covers files, processes, HTTP and JSON, turning REPL work into scripts and bb tasks, adding dependencies, and the limits of the runtime. Read when the REPL is Babashka and the job is automation, glue code, data wrangling or a command line tool.
---

# Babashka as a tooling language

Babashka (bb) is a Clojure runtime that starts in milliseconds and bundles
the libraries scripting needs. With a live bb REPL you can build a tool the
way you would explore a shell, one working form at a time, and then save the
forms as a script.

Check that the REPL is Babashka:

```clojure
(System/getProperty "babashka.version")   ; nil means JVM Clojure
```

## What is built in

No dependency needed for any of these:

| Namespace | For |
|---|---|
| `babashka.fs` | files, directories, globbing, temp files |
| `babashka.process` | running programs |
| `babashka.http-client` | HTTP requests |
| `cheshire.core`, `clojure.data.json` | JSON |
| `clojure.edn`, `clj-yaml.core`, `clojure.data.csv`, `clojure.data.xml` | other formats |
| `babashka.cli`, `clojure.tools.cli` | command line arguments |
| `org.httpkit.server` | an HTTP server |
| `selmer.parser`, `hiccup2.core` | templates and HTML |
| `clojure.core.async` | channels |
| `java.time`, `java.nio.file` and other common Java classes | through interop |

## Files

```clojure
(require '[babashka.fs :as fs])

(map str (fs/glob "." "**.md"))        ; recursive; "**/*.md" skips the top directory
(fs/exists? "config.edn")
(fs/create-dirs "out/reports")
(fs/copy "a.txt" "out/a.txt" {:replace-existing true})
(fs/read-all-lines "notes.txt")
(fs/delete-tree "out")            ; recursive; be sure of the path
```

`slurp` and `spit` read and write whole files as strings.

## Running programs

```clojure
(require '[babashka.process :refer [shell]])

(-> (shell {:out :string} "git" "status" "--short") :out)
(shell {:dir "subproject"} "npm" "test")                  ; output goes to the REPL's stdout
(:exit (shell {:continue true :out :string :err :string} "grep" "-q" "x" "f.txt"))
```

`shell` throws on a non-zero exit unless `:continue true`. Pass arguments as
separate strings; do not build one command string from untrusted input.

## HTTP and JSON

```clojure
(require '[babashka.http-client :as http]
         '[cheshire.core :as json])

(-> (http/get "https://api.github.com/repos/babashka/babashka"
              {:headers {"Accept" "application/json"}})
    :body
    (json/parse-string true)
    (select-keys [:stargazers_count :open_issues_count]))

(http/post url {:headers {"Content-Type" "application/json"}
                :body (json/generate-string {:name "x"})})
```

A non-2xx status throws unless the request has `:throw false`.

## From REPL work to a script

Once the forms work, put them in a file. Keep iterating with `load_file`.

```clojure
#!/usr/bin/env bb
(ns report
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]))

(defn summarize [dir]
  {:files (count (fs/glob dir "**.md"))})

(defn -main
  "Returns the exit code, so it can be called from the REPL."
  [& args]
  (println (json/generate-string (summarize (or (first args) "."))))
  0)

;; run -main as a script, not when loaded into a REPL
(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
```

Have `-main` return the exit code and call `System/exit` only inside the
guard: then calling `-main` from the REPL does not end the REPL.

Run it as `bb report.clj docs`. To run it as `./report.clj`, make it
executable: `chmod +x report.clj` in a shell, or from the REPL
`(fs/set-posix-file-permissions "report.clj" "rwxr-xr-x")`.

## Tasks

Commands a project runs often belong in `bb.edn`, next to the code:

```clojure
{:paths ["scripts"]
 :tasks
 {:requires ([babashka.fs :as fs] [report])
  clean {:doc "Remove build output"
         :task (fs/delete-tree "target")}
  report {:doc "Summarize the docs"
          :task (apply report/-main *command-line-args*)}}}
```

`bb tasks` lists them, `bb clean` runs one, and `bb report docs` passes `docs`
as an argument. This works in a project of any
language: the tasks are the project's command line.

## Dependencies

```clojure
(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {medley/medley {:mvn/version "1.4.0"}}})
```

Only libraries that are plain Clojure and use classes Babashka includes will
load. Keep a dependency by adding it to `bb.edn` under `:deps`.

## Limits

- The set of Java classes is fixed. Libraries that need others do not load.
- Code is interpreted, so heavy computation is much slower than on the JVM.
  Shell out to a faster tool, or use a JVM REPL for that part.
- Do not call `System/exit` in the REPL: it ends the process. In a script,
  call it only where the script runs as a script, as in the guard above.

## Working this way

Probe first: fetch one page, list one directory, parse one record, and look
at the value. Build the transformation on that real value. Wrap what works in
a function, then in a script or task. See `skill://repl-workflow/SKILL.md`
for the loop and `skill://repl-long-running/SKILL.md` for jobs that take
time.
