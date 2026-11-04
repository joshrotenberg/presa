# Releasing

presa is released as git tags. Clojure CLI and Babashka projects depend on
a tag (`:git/tag` with its `:git/sha`); there is no Clojars artifact.

## How a release happens

1. Merged pull requests land on `main` with conventional-commit subjects.
2. `.github/workflows/release-please.yml` keeps a release pull request
   open, titled `chore(main): release X.Y.Z`, with the next version in
   `version.txt`, the coordinates in `README.md`, and the changelog in
   `CHANGELOG.md`. Before 1.0, `feat` commits bump the minor version and
   `fix` commits the patch version.
3. Merging that pull request tags `vX.Y.Z` on `main` and creates the GitHub
   release.

## Repository settings it needs

- Settings, Actions, General: "Allow GitHub Actions to create and approve
  pull requests", or release-please cannot open its pull request.
- Optionally a `RELEASE_PLEASE_TOKEN` secret: a fine-grained token with
  read and write Contents, Pull requests and Issues on this repository. A
  pull request opened with the workflow token does not start other
  workflows, so without the secret the release pull request's CI does not
  run until it is pushed to again.

## Depending on a release

```clojure
;; deps.edn or bb.edn
{:deps {io.github.joshrotenberg/presa {:git/tag "v0.1.0" :git/sha "<the tag's commit, 7 or more characters>"}}}
```

`git ls-remote https://github.com/joshrotenberg/presa.git refs/tags/v0.1.0`
prints the sha. The repository is private, so the git dependency needs
GitHub credentials.

## Pins outside the release line

`agent-stack/v1` is a branch agent-stack pins by sha. It is not moved by
releases; agent-stack moves its pin.
