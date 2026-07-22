---
title: Minting
description: Build escaped string literals and minted IRIs from variables.
---

Plain `"…"` strings and `<…>` IRIs are inert. To build them from variables, use
`$"…"` and `$<…>`.

## Strings — `$"…"`

```sparql
$"Hello ${name}"                 →  "Hello World"
$"Result #${index}: ${label}"    →  "Result #2: Acme"
$"localized"@en                  →  "localized"@en
$"Hello ${name}"@${lang}         →  "Hello World"@de
$"42"^^xsd:int                   →  "42"^^xsd:int
```

Holes are `${ … }`, string-escaped (a `raw`-typed value is verbatim). Suffixes:
static `@en`, dynamic `@${lang}`, or static `^^<iri>` / `^^prefix:name`.

## IRIs — `$<…>`

```sparql
$<http://example.org/person/${id}>   →  <http://example.org/person/42>
```

Holes are percent-encoded to the unreserved set, so each is one opaque
component (`a/b` → `a%2Fb`); a `raw` value is verbatim. The assembled IRI is
validated as absolute, so even `raw` cannot break out of `<…>`.

Because `$"` and `$<` begin with `$`, both keep the fail-fast guarantee that
plain `"…"`/`<…>` cannot provide.

## Inert regions

Comments (`#`), complete `<…>` IRIs, and string literals (`"…"`, `'…'`,
triple-quoted) pass through verbatim — a `$` or `{` inside them is literal text.
A `#` inside a string or IRI is not a comment. Frontmatter comments are never
emitted, but the parser retains them as positioned symbols so tools like
formatters can preserve and re-indent them; a comment in the body is emitted.
