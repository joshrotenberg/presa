# Conformance

presa is measured with the official MCP conformance runner,
`@modelcontextprotocol/conformance` pinned at `0.2.0-alpha.12`, against the
frozen 2026-07-28 requirement set: 37 required server scenarios.

- `presa/conformance/fixture.clj` is the server the scenarios call: the
  tool, resource and prompt names in section 16 of
  [docs/wire-2026-07-28.md](../docs/wire-2026-07-28.md), served over
  Streamable HTTP on 127.0.0.1.
- `run.clj` starts the fixture under Babashka, runs the runner's server leg
  against it, and prints each scenario's checks. Raw results land in
  `tmp/conformance/<timestamp>/`.

```sh
(cd conformance && npm ci --ignore-scripts)   # or set PRESA_CONFORMANCE_RUNNER
bb conformance                                # every scenario
bb conformance tools-list                     # one scenario
```

Without `conformance/node_modules`, `run.clj` uses the runner in a snodo
checkout next to this one, or `PRESA_CONFORMANCE_RUNNER` (the path to the
runner's `dist/index.js`).

## Baseline, 2026-11-03

| | Scenarios | Checks |
|---|---|---|
| Required (37) | 37 with no FAILURE, WARNING or SKIPPED check | `server-stateless`: 30 of 30, including the `subscriptions/listen` checks |
| Pending, unscored (3) | `http-header-validation`, `http-custom-header-server-validation`, `json-schema-2020-12` pass | |
| Tasks extension, unscored (10) | 9 fail, 1 skipped | presa does not implement `io.modelcontextprotocol/tasks` |

Totals over all 50 scenarios run: 164 SUCCESS, 30 FAILURE (all Tasks), 1
SKIPPED (Tasks), 1 INFO (`server-sse-multiple-streams`: every response was
JSON).

### Initialize-era lanes

`bb conformance 2025-11-25` runs the frozen 2025-11-25 requirement set, and
`bb conformance 2025-06-18` the runner's active suite for that revision
(it has no frozen set), against the fixture with every revision enabled.

| Lane | Result |
|---|---|
| 2025-11-25 (33 scenarios) | all pass: 80 SUCCESS, 4 INFO |
| 2025-06-18 (27 scenarios) | all pass: 56 SUCCESS |

These include elicitation, sampling and logging during a tool call, which
presa serves to these clients as requests from server to client, and
`resources/subscribe` and `unsubscribe`.

Not measured yet: the client leg.
