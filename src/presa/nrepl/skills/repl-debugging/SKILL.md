---
name: repl-debugging
description: How to debug from the REPL. Covers reading the last exception, capturing intermediate values, narrowing a failure to one function call, and telling stale code from a real bug. Read when an evaluation throws or returns something unexpected.
---

# Debugging from the REPL

## After an exception

An error result names the exception class. The exception itself is in `*e`:

```clojure
(ex-message *e)
(ex-data *e)                 ; the map of an ex-info, else nil
(ex-cause *e)                ; the wrapped exception, if any

(require 'clojure.repl)      ; not loaded in a fresh session
(clojure.repl/pst *e 12)     ; message and 12 stack frames
```

For a nested exception, the useful message is often the innermost one:

```clojure
(->> (iterate ex-cause *e) (take-while some?) (map ex-message))
```

To see only your own frames:

```clojure
(->> (.getStackTrace *e)
     (map str)
     (filter #(re-find #"my\.app" %))
     (take 10))
```

`*e` is replaced by the next exception, so read it before evaluating
something else that might throw.

## Narrow it down

Call the pieces directly. Take the arguments of the failing call and
evaluate the inner functions one by one, from the inside out, until one of
them gives the wrong value. This is usually faster than reading the code.

## Capture values

To see a value inside a function, capture it and look afterwards.

A temporary `def`, removed when you are done:

```clojure
(defn handler [request]
  (def dbg-request request)     ; temporary
  ...)
```

Load the file, trigger the call, then evaluate `dbg-request`.

`tap>`, which leaves no global behind:

```clojure
(defonce taps (atom []))
(add-tap #(swap! taps conj %))

(tap> {:where :handler :request request})   ; in the code
@taps                                       ; afterwards
(reset! taps [])
```

Inside a threading form, `(doto prn)` prints a value and passes it on:

```clojure
(-> request parse (doto prn) validate)
```

## Is the process stale

When the code looks right and behaves wrong, check what is loaded:

```clojure
(meta #'my.app.core/handler)            ; :line of the loaded definition
(require 'clojure.repl)
(clojure.repl/source my.app.core/handler)
```

Then `load_file` the file and try again. Definitions removed from a file
stay defined until `ns-unmap`. See `skill://repl-workflow/SKILL.md`.

## A call that hangs

A call that reaches its timeout returns `[running]`. Use `interrupt` to stop
it. On the JVM, to see where threads are stuck:

```clojure
(doseq [[t frames] (Thread/getAllStackTraces)
        :when (re-find #"nREPL-session" (.getName t))]
  (println (.getName t))
  (run! #(println "  " %) (take 8 frames)))
```

## Timing

```clojure
(time (my.app.core/slow-thing data))    ; prints elapsed time, returns the value
```
