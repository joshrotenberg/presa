---
name: repl-exploring
description: How to find out what a running Clojure or Babashka process contains. Covers namespaces, vars, documentation, source, the shape of data, Java classes and the classpath. Read when working in an unfamiliar codebase or with an unfamiliar library.
---

# Exploring a running process

Ask the process. It knows what is loaded, which is what will run.

## Which runtime

```clojure
(System/getProperty "babashka.version")   ; nil on the JVM
(clojure-version)
(System/getProperty "java.version")
```

## Vars and documentation

The `lookup` tool gives the doc string, argument lists and source location of
one symbol. It only knows namespaces that are loaded: require the namespace
first.

```clojure
(require '[clojure.repl :refer [doc source dir apropos find-doc]])

(dir clojure.string)          ; the public names of a namespace
(apropos "partition")         ; names containing a string, in loaded namespaces
(find-doc "lazy sequence")    ; search doc strings; can print a lot
(source clojure.string/join)  ; the source, when the file is on the classpath
```

## Namespaces

```clojure
(->> (all-ns) (map ns-name) (filter #(re-find #"^my\.app" (str %))) sort)
(keys (ns-publics 'my.app.core))
(ns-aliases 'my.app.core)     ; what its requires are called
(meta #'my.app.core/handler)  ; :file :line :arglists :doc
```

A namespace that is on the classpath but not loaded does not appear in
`all-ns`. Require it, or read its file.

## The shape of data

Look before you print: a value may be huge.

```clojure
(type x)
(count x)
(keys x)                          ; a map
(first x)                         ; a sequence
(map type (take 5 x))
(update-vals x type)              ; each key's kind of value
(into {} (map (juxt key (comp type val))) x)
```

## Java classes

```clojure
(->> (.getMethods (class x)) (map #(.getName %)) distinct sort)

(require '[clojure.reflect :as r])
(->> (r/reflect java.time.Duration) :members (map :name) distinct sort)
```

Babashka has a fixed set of Java classes built in. A class missing from it
fails to resolve; that is a limit of the runtime, not a typo.

## Classpath and resources

```clojure
(System/getProperty "java.class.path")     ; JVM
(babashka.classpath/get-classpath)         ; Babashka
(clojure.java.io/resource "config.edn")    ; nil when not on the classpath
```

## Trying a library

On Babashka, add a dependency to the running process:

```clojure
(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {medley/medley {:mvn/version "1.4.0"}}})
(require '[medley.core :as m])
```

On JVM Clojure 1.12, `(clojure.repl.deps/add-lib 'medley/medley)` does the
same when the REPL was started by the Clojure CLI. If it is refused, add the
dependency to `deps.edn` and have the REPL restarted.

Either way, record the dependency in `deps.edn` or `bb.edn` once you decide
to keep it.
