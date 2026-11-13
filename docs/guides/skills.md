# Skills

A skill is a directory of instructions for a model: a `SKILL.md` whose YAML
front matter names and describes it, and any supporting files
([Agent Skills](https://agentskills.io/specification)). The Skills extension
(`io.modelcontextprotocol/skills`, SEP-2640) serves skills over MCP. Each
file is a resource, `skill://<path>/<file>`, and the server answers
`skills/list` and `skills/get` with an entry per skill: its `SKILL.md` URI,
its front matter, and the digest and size of every file.

`presa.skill` builds skills, `presa.server/server` serves them, and
`presa.client` lists and loads them.

## A skill

`skill` takes the skill's path and its files. The path's last segment is
the skill's name, and must be the `name` in the front matter.

```clojure
(require '[presa.skill :as skill]
         '[presa.server :as server]
         '[presa.client :as client])

(def review
  (skill/skill "code-review"
               {"SKILL.md" (str "---\n"
                                "name: code-review\n"
                                "description: Review a change for correctness and style\n"
                                "---\n"
                                "# Reviewing a change\n\nStart with references/checklist.md.\n")
                "references/checklist.md" "- tests cover the change\n"}))

(skill/uri review)
;; => "skill://code-review/SKILL.md"
```

A path may have a prefix, as in `"acme/billing/refunds"`, a skill named
`refunds` at `skill://acme/billing/refunds/SKILL.md`.

Skills usually live in files. `from-dir` reads a directory and everything
under it; `from-classpath` reads named resources, since a classpath cannot be
listed:

<!-- no-run -->
```clojure
(skill/from-dir "skills/code-review")
(skill/from-classpath "my/app/skills/code-review" ["SKILL.md" "references/checklist.md"])
```

`skill` throws when there is no `SKILL.md`, the front matter lacks `name` or
`description`, the name is not the path's last segment, or the skill is
larger than every host must accept (512 files, 16 MiB).

## Serving

A server with `:skills` declares the extension and the `resources`
capability. Each file is an ordinary resource as well, so a client that does
not know the extension still reads a skill by its URI.

```clojure
(def srv (server/server {:name "team" :version "1" :skills [review]}))
(def c (client/connect (client/in-process srv)))

(client/skills? c)
;; => true

(map #(get % "uri") (client/list-skills c))
;; => ("skill://code-review/SKILL.md")

(get (client/get-skill c "skill://code-review/SKILL.md") "frontmatter")
;; => {"name" "code-review", "description" "Review a change for correctness and style"}

(map #(get % "uri") (client/list-resources c))
;; => ("skill://code-review/SKILL.md" "skill://code-review/references/checklist.md")
```

An entry's `"resources"` lists every file with a `sha256:` digest and its
size in bytes. `skills/list` pages with the server's `:page-size` and
carries the caching hints of 2026-07-28; `skills/get` answers for one skill,
and with `-32602` for a URI that is not a skill's `SKILL.md`.

`:required-scopes` on a `SKILL.md`'s resource URI keeps the skill out of
`skills/list` and `skills/get` for a caller without the scopes, as it does
for the resource.

## Pointing a model at a skill

A host that supports the extension builds its skill list from
`skills/list`. For other hosts, name the skill in the server's
`:instructions`: the URI is then in the model's context, and readable with
`resources/read`.

<!-- no-run -->
```clojure
(server/server {:name "team" :version "1" :skills [review]
                :instructions (str "Before reviewing a change, read " (skill/uri review) ".")})
```

## Loading a skill from a client

The extension asks a host to check what it reads: content against the
entry's size and digest, and the `SKILL.md` front matter against the
entry's. `read-skill` does this for the `SKILL.md`, and `read-skill-file`
for another file of the same entry. A mismatch, or a file the entry does not
list, throws `ex-info` with `:kind :skill-verification`.

```clojure
(def loaded (client/read-skill c "skill://code-review/SKILL.md"))

(clojure.string/includes? (:text loaded) "# Reviewing a change")
;; => true

(client/read-skill-file c (:entry loaded) "skill://code-review/references/checklist.md")
;; => "- tests cover the change\n"
```

Digests come from the same server as the content. A match shows the two are
consistent, not that either can be trusted: skill content is untrusted
input to a model, like any other text a server sends.

## Directories

A skill's instructions may name a directory. `read-directory` lists a
directory's direct children: files as `resources/list` gives them, and
subdirectories with the mimeType `inode/directory`.

```clojure
(map (juxt #(get % "name") #(get % "mimeType")) (client/read-directory c "skill://code-review"))
;; => (["SKILL.md" "text/markdown"] ["references" "inode/directory"])

(client/close c)
```

## Front matter

presa takes no YAML dependency, so `skill/frontmatter` reads the part of
YAML that skill front matter is written in: plain and quoted scalars, `|`
and `>` blocks, nested maps and lists of scalars.

```clojure
(skill/frontmatter "---\nname: pdf\ndescription: Fill forms\nmetadata:\n  version: \"2.1\"\n---\n")
;; => {"name" "pdf", "description" "Fill forms", "metadata" {"version" "2.1"}}
```

Anything else throws: flow collections (`[a, b]`), anchors, multi-line plain
values. So does a key or value that YAML readers read differently from one
another, such as `yes`, `on`, `1e3`, `012` or a date. A host compares the
front matter it reads with the entry's and refuses the skill when they
differ, so presa refuses to guess. Quote such a value to make it a string,
or give the skill its front matter as a map:

<!-- no-run -->
```clojure
(skill/skill "pdf" files {:frontmatter {"name" "pdf" "description" "Fill forms" "tags" ["forms" "pdf"]}})
```

The map must say what the file says.

## Generated content

A file may be a function of no arguments, called on each read. A skill with
such a file is listed with `"resources" "dynamic"` in place of digests: its
content cannot be verified, and a host may decline to load it.

```clojure
(get (skill/entry (skill/skill "status"
                               {"SKILL.md" "---\nname: status\ndescription: Report the build status\n---\n"
                                "now.txt" (fn [] (str "checked at " (System/currentTimeMillis)))}))
     "resources")
;; => "dynamic"
```

## In parts

A part may carry `:skills` (see [Parts](parts.md)). `presa.part/combine`
treats a repeated skill URI as a duplicate, and a server built from parts
declares the extension only when one of them has skills.

## What is not here

- `presa.proxy` does not forward the skills methods.
- Skills are fixed when the server is built; there is no adding or removing
  one while it runs.
