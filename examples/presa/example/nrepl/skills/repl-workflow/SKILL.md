---
name: repl-workflow
description: How to develop against a live REPL with eval, load_file and lookup. Covers working in small steps, keeping files as the source of truth, reloading code, and what a restart loses. Read before any multi-step work in the REPL.
---

# Working in a live REPL

The REPL is the running program. Evaluate to find out what is true instead of
guessing from the source: call the function, look at the value.

## Which runtime

The REPL may be JVM Clojure, Babashka or another dialect, and they differ in
what is available. Find out once:

```clojure
{:babashka (System/getProperty "babashka.version")   ; nil on the JVM
 :clojure (clojure-version)
 :dir (System/getProperty "user.dir")}               ; the REPL's working directory
```

Relative paths in evaluated code resolve against the REPL's working
directory, which may not be yours. Absolute paths avoid the question.

## The loop

1. Read or edit a source file with your file tools. Without file tools,
   `(spit path text)` writes one from the REPL: the whole file each time,
   with every `"` in the source escaped.
2. `load_file` it, so the running program has the new definitions.
3. `eval` a call with real arguments and read the result.
4. Repeat. Keep each evaluation small: one require, one call, one value.

## Files are the source of truth

Anything defined only through `eval` exists only in the process and is gone
when the REPL restarts. Use `eval` to explore, call and inspect. Put every
definition you want to keep in a file, then `load_file` it.

A result that says "This is a new REPL session" means `*ns*`, `*1` and `*e`
were reset, and if the REPL itself restarted, every definition is gone:
require or load what you need again.

## Namespaces

```clojure
(require '[my.app.core :as core] :reload)      ; pick up a changed file
(require '[my.app.core :as core] :reload-all)  ; and the namespaces it requires
(in-ns 'my.app.core)                           ; switch; the session remembers
```

The `ns` argument of `eval` evaluates in a namespace without switching the
session to it. The namespace must already be loaded.

## Stale definitions

Reloading adds and replaces definitions; it never removes them.

- A var you deleted from the file is still defined: `(ns-unmap 'my.app.core 'old-fn)`.
- `defonce` keeps its value across reloads, which is what you want for state
  such as a running server or a cache.
- `defmulti` is not redefined on reload. Remove it first:
  `(ns-unmap 'my.app.core 'my-multi)`, then load the file.
- Reloading a `defrecord` or `defprotocol` makes instances created before the
  reload stop matching. Recreate them.

When behavior does not match the file, assume the process is stale and load
the file again before looking for another cause.

## Reading results

Values come back printed as text, and long output keeps only its start and
end. Ask narrower questions instead of printing everything:

```clojure
(count xs)
(take 3 xs)
(keys m)
(select-keys m [:id :status])
(type x)
```

Printing an infinite lazy sequence never finishes. Bound it with `take`, or
set `(set! *print-length* 50)` for the session.

## Do not

- Call `System/exit` or `shutdown-agents`: they end the REPL process.
- Block the session with code that never returns. See
  `skill://repl-long-running/SKILL.md`.

## Related guides

- `skill://repl-exploring/SKILL.md` to find out what code and data are there
- `skill://repl-debugging/SKILL.md` when a call throws or returns the wrong thing
- `skill://repl-testing/SKILL.md` to run tests from the REPL
