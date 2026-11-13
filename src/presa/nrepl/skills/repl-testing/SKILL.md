---
name: repl-testing
description: How to run clojure.test tests from the REPL. Covers running a namespace or a single test, reloading code under test first, and stale tests. Read when writing or running tests during REPL work.
---

# Running tests from the REPL

Running tests in the REPL avoids starting a new process for each run, and
leaves failing values available to inspect.

## Reload, then run

Tests run against what is loaded. After editing, load the source file and
the test file:

- `load_file` the changed source file
- `load_file` the test file

```clojure
(require '[clojure.test :as t])

(t/run-tests 'my.app.core-test)
;; prints failures, returns {:test 12 :pass 40 :fail 1 :error 0 :type :summary}
```

Failures are printed, so they are in the result. The returned map is the
summary: check `:fail` and `:error`.

## One test

```clojure
(t/test-vars [#'my.app.core-test/parses-dates])
```

`test-vars` applies the namespace's fixtures. It prints failures and returns
nil, so no output means the test passed.

## Several namespaces

```clojure
(t/run-tests 'my.app.core-test 'my.app.util-test)
(t/run-all-tests #"my\.app\..*-test")      ; every loaded namespace that matches
```

`run-all-tests` only sees loaded namespaces. Require the test namespaces
first.

## Stale tests

A `deftest` deleted from the file still runs until it is removed from the
process:

```clojure
(ns-unmap 'my.app.core-test 'old-test)
;; or start the namespace over
(remove-ns 'my.app.core-test)
(require 'my.app.core-test :reload)
```

## Long suites

A suite that outlasts the call's timeout keeps running and the result says
`[running]`. Use `await` for the rest, or pass a larger `timeout_ms`. See
`skill://repl-long-running/SKILL.md`.

## The project's own runner

The REPL run is for fast feedback. Before reporting work as done, run the
project's real test command as well: it starts from a clean process, so it
catches code that only worked because of leftover REPL state.
