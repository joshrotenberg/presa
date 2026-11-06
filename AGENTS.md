# Working on presa

Written by `cj project init` for agents and contributors working here. Keep it
current as the project changes.

## Layout

| Path | Contents |
|---|---|
| `deps.edn` | Clojure dependencies and aliases |
| `bb.edn` | Babashka tasks |
| `.github/` | GitHub workflows and templates |
| `src/` | Source |
| `test/` | Tests, run under Babashka and the JVM (`test/presa/test_runner.clj` lists the namespaces) |
| `test-jvm/` | JVM-only tests: full JSON Schema validation, the wire schema check |
| `examples/` | The hello, notebook and Clojars example servers, the notebook's client and REPL walkthroughs |
| `docs/` | Guides (their Clojure blocks run as tests) and protocol reference |
| `conformance/` | The official conformance runner's harness, fixture, baselines and vendored schema |
| `dev/` | `lint.clj` (`bb lint`) and `host_probe.clj` (what real hosts send) |
| `build.clj` | The jar and Clojars deploy (`clojure -T:build jar`) |
| `.cj/` | cj project layer: the check recipe and config |

## Checks

Run `cj run check` before pushing; it must report `:ok? true`. It runs these
commands from `.cj/recipes/check.clj`, every one even when an earlier one
fails, then checks the conventions below:

- `bb test`
- `bb jvm-test`
- `bb lint`

CI runs those, `bb lint` (clj-kondo and the conventions below, without cj), the jar
build, the server conformance lanes (`bb conformance <revision>`, each against
`conformance/baselines/<revision>.json`) and the client lanes
(`bb conformance client <revision>`, against `client-<revision>.json`); see
`conformance/README.md`.

## Conventions

The check enforces these:

- clj-kondo finds no errors or warnings in `src`, `test`, `test-jvm`,
  `examples`, `dev`, `conformance` and the build files. `bb lint` runs it
  through its Babashka pod, so there is nothing to install. `.clj-kondo/config.edn`
  holds the configuration: `defalias` and `with-server` are linted as `def` and
  `let`, and `:locking-suspicious-lock` is off because every lock is a shared
  object held in a session or state map.
- Commit subjects in `origin/main..HEAD` use conventional-commit prefixes: `feat`,
  `fix`, `docs`, `style`, `refactor`, `perf`, `test`, `build`, `ci`, `chore`,
  `revert`, with an optional scope and `!` (git's own `Revert "..."` subject
  also passes). Merge commits are not checked.
- Commit messages have no Co-Authored-By, Assisted-By or Generated-By
  trailers and no "Generated with" lines. `bb lint` allows a Co-authored-by
  trailer for a GitHub bot account, which GitHub adds when a bot's pull
  request (release-please's) is squash-merged.
- Tracked `*.md` `*.clj` `*.edn` `*.json` files have no em dashes (vendored
  files under `conformance/schema/` aside).

## Design rules

These are not checked by `cj run check`; reviews hold changes to them.

- Every namespace loads under Babashka and on the JVM from the same source, except `presa.schema.networknt`, which is JVM only and needs its own optional dependency. Runtime dependencies are limited to what Babashka bundles: `clojure.data.json` in the core, and http-kit for the HTTP transport.
- MCP `2026-07-28` (stateless: `_meta` on each request, `server/discover`, `resultType`) comes first. Initialize-era revisions (`2025-11-25`, `2025-06-18`) are an opt-in layered on top, never the default path.
- Protocol handling is pure functions over request and response maps with string keys; transports (stdio, Streamable HTTP) sit outside the core.
- Reference designs: snodo (Elixir) and tower-mcp (Rust). Conformance is measured with the official MCP conformance runner, as snodo does.
