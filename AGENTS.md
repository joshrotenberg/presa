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
| `test/` | Tests |
| `.cj/` | cj project layer: the check recipe and config |

## Checks

Run `cj run check` before pushing; it must report `:ok? true`. It runs these
commands from `.cj/recipes/check.clj`, every one even when an earlier one
fails, then checks the conventions below:

- `bb test`
- `bb jvm-test`

CI runs those, `bb lint` (the conventions below, without cj) and the
conformance lanes (`bb conformance <revision>`, each against
`conformance/baselines/<revision>.json`; see `conformance/README.md`).

## Conventions

The check enforces these:

- Commit subjects in `origin/main..HEAD` use conventional-commit prefixes (`feat:`,
  `fix:`, `docs:`, `test:`, `refactor:`, `chore:`).
- Commit messages have no Co-Authored-By, Assisted-By or Generated-By
  trailers and no "Generated with" lines.
- Tracked `*.md` `*.clj` `*.edn` `*.json` files have no em dashes.

## Design rules

These are not checked by `cj run check`; reviews hold changes to them.

- Every namespace loads under Babashka and on the JVM from the same source. Runtime dependencies are limited to what Babashka bundles: `clojure.data.json` in the core, and http-kit for the HTTP transport.
- MCP `2026-07-28` (stateless: `_meta` on each request, `server/discover`, `resultType`) comes first. Initialize-era revisions (`2025-11-25`, `2025-06-18`) are an opt-in layered on top, never the default path.
- Protocol handling is pure functions over request and response maps with string keys; transports (stdio, Streamable HTTP) sit outside the core.
- Reference designs: snodo (Elixir) and tower-mcp (Rust). Conformance is measured with the official MCP conformance runner, as snodo does.
