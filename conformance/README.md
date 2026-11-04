# Conformance

presa is measured with the official MCP conformance runner,
`@modelcontextprotocol/conformance` pinned at `0.2.0-alpha.12`, against the
frozen 2026-07-28 requirement set: 37 required server scenarios.

- `presa/conformance/fixture.clj` is the server the scenarios call: the
  tool, resource and prompt names in section 16 of
  [docs/wire-2026-07-28.md](../docs/wire-2026-07-28.md), served over
  Streamable HTTP on 127.0.0.1.
- `presa/conformance/client.clj` is the client the runner's client leg runs.
- `run.clj` starts the fixture under Babashka, runs the runner's server leg
  against it (or the client leg against the harness), and prints each
  scenario's checks. Raw results land in
  `tmp/conformance/<timestamp>/`.

```sh
(cd conformance && npm ci --ignore-scripts)   # or set PRESA_CONFORMANCE_RUNNER
bb conformance                                # 2026-07-28, against its baseline
bb conformance 2025-11-25                     # another lane
bb conformance 2026-07-28 tools-list          # one scenario, no baseline check
bb conformance 2026-07-28 --update-baseline   # record a new baseline
```

## Baselines

`baselines/<revision>.json` records the status of every check of every
scenario in a full run (`checkInventory`, as snodo's
`expected-failures.json` does). A full run is compared with it and fails on
any difference: a new failure, a check that went missing, and also a check
that started passing. A baseline therefore changes only through
`--update-baseline` and a reviewed diff.

## CI

`.github/workflows/conformance.yml` runs the three lanes on every pull
request and push to main, each against its baseline, and keeps the runner's
raw results as an artifact. `canary.yml` runs the same lanes weekly against
the runner's `alpha` dist-tag (`PRESA_CONFORMANCE_UNPINNED=1`) without
gating anything, to show what a newer runner would change.

Without `conformance/node_modules`, `run.clj` uses the runner in a snodo
checkout next to this one, or `PRESA_CONFORMANCE_RUNNER` (the path to the
runner's `dist/index.js`).

## Baseline, 2026-11-03

| | Scenarios | Checks |
|---|---|---|
| Required (37) | 37 with no FAILURE, WARNING or SKIPPED check | `server-stateless`: 30 of 30, including the `subscriptions/listen` checks |
| Pending, unscored (3) | `http-header-validation`, `http-custom-header-server-validation`, `json-schema-2020-12` pass | |
| Tasks extension, unscored (10) | 9 pass, 1 skipped | the skipped one, `tasks-status-notifications`, is a runner stub that always skips |

Totals over all 50 scenarios run: 196 SUCCESS, 0 FAILURE, 1 SKIPPED (the
Tasks notification stub), 1 INFO (`server-sse-multiple-streams`: every
response was JSON).

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

### Client lanes

`bb conformance client 2026-07-28` and `bb conformance client 2025-11-25`
run the runner's client leg: for each scenario the runner starts its own
server and runs `presa.conformance.client` against it, with the server URL
as the last argument. The harness drives `presa.client` as an application
would; see its namespace docstring.

| Lane | Result |
|---|---|
| client 2026-07-28 (39 scenarios) | 35 pass: every scored one; `http-standard-headers` has 2 SKIPPED checks, its `initialize` checks, which a 2026-07-28 client never reaches |
| client 2025-11-25 (25 scenarios) | 21 pass: every scored one |

With `presa.auth.client` the scored `auth/*` scenarios pass too: all 25 at
2026-07-28 and all 14 at 2025-11-25, plus the two client credentials
extension scenarios. The DPoP, enterprise-managed authorization and
workload identity extension scenarios fail, as presa implements none of
them; the baselines record that.

## Wire schema

`schema/2026-07-28/` vendors the official 2026-07-28 JSON schema with its
license and provenance (modelcontextprotocol commit `5f5440bb`, sha256 in
`provenance.json`). `test-jvm/presa/wire_schema_test.clj` drives the
fixture through `presa.client` over a recording transport and validates
every message, both directions, against the definition for its method
(`ListToolsResultResponse`, `CallToolRequest`, `ProgressNotification` and
so on), with negative controls that must fail. It runs with `bb jvm-test`,
so CI runs it on every pull request. Tasks results are checked only as
generic results, since the core schema does not define them.
