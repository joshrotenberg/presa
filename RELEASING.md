# Releasing

Each release is a git tag and a Clojars artifact,
`io.github.joshrotenberg/presa`. Clojure CLI and Babashka projects can depend
on either (`:mvn/version`, or `:git/tag` with its `:git/sha`).

## How a release happens

1. Merged pull requests land on `main` with conventional-commit subjects.
2. `.github/workflows/release-please.yml` keeps a release pull request
   open, titled `chore(main): release X.Y.Z`, with the next version in
   `version.txt`, the version in `README.md`'s coordinate block (other docs
   show `0.1.0` as an example and are not updated), the version the client
   reports in `presa.client`, and the changelog in
   `CHANGELOG.md`. Before 1.0, `feat` commits bump the minor version and
   `fix` commits the patch version.
3. Merging that pull request tags `vX.Y.Z` on `main` and creates the GitHub
   release.
4. The workflow's `clojars` job then checks out the tag and runs
   `clojure -T:build deploy` (`build.clj`), which builds the jar and its pom
   and uploads them to Clojars. CI builds the same jar on every pull
   request.

## Repository settings it needs

- Settings, Actions, General: "Allow GitHub Actions to create and approve
  pull requests", or release-please cannot open its pull request.
- Optionally a `RELEASE_PLEASE_TOKEN` secret: a fine-grained token with
  read and write Contents, Pull requests and Issues on this repository. A
  pull request opened with the workflow token does not start other
  workflows, so without the secret the release pull request's CI does not
  run until it is pushed to again.

- A `CLOJARS_TOKEN` secret: a Clojars deploy token. The group
  `io.github.joshrotenberg` is verified on Clojars by signing in with the
  GitHub account. A `CLOJARS_USERNAME` repository variable names the Clojars
  user the token belongs to (default `joshrotenberg`).

## Depending on a release

```clojure
;; deps.edn or bb.edn
{:deps {io.github.joshrotenberg/presa {:mvn/version "0.1.0"}}}
;; or
{:deps {io.github.joshrotenberg/presa {:git/tag "v0.1.0" :git/sha "<the tag's commit, 7 or more characters>"}}}
```

`git ls-remote https://github.com/joshrotenberg/presa.git refs/tags/v0.1.0`
prints the sha.

## Building the jar locally

```sh
clojure -T:build jar     # target/presa-<version>.jar and target/classes/META-INF/maven/.../pom.xml
```

## Pins outside the release line

`agent-stack/v1` is a branch agent-stack pins by sha. It is not moved by
releases; agent-stack moves its pin.
