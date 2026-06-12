---
title: Examples & tooling
description: Named example blocks, inert regions, and template detection.
---

## Example blocks

```
example dbpedia "DBpedia — people & orgs" {
  service: <http://dbpedia.org/sparql>
  classes: [ foaf:Person, foaf:Organization ]
  limit:   10
}
```

An `example <id> ["<description>"] { … }` block (in the `---` frontmatter,
alongside `params`) declares a named, validated set of sample values. They are
**development/preview fixtures, not production defaults**: `render(context)`
still requires real values, while `previewExample(id)` / a CLI renders with a
set so a query is runnable while you develop. An IDE lists blocks by id and
shows the description. Prefixed names resolve against the template's `PREFIX`
declarations.

## Inert regions

Comments (`#`), complete `<…>` IRIs, and string literals (`"…"`, `'…'`,
triple-quoted) pass through verbatim — a `$` or `{` inside them is literal text.
A `#` inside a string or IRI is not a comment. Frontmatter comments stay
metadata; a comment in the body is emitted.

## Detection

A leading `---` frontmatter header is a positive "this is a Triplate template"
marker, complementing the fail-fast guarantee (the body is invalid host syntax
until rendered).
