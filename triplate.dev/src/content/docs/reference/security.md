---
title: Security model
description: How Triplate prevents injection.
---

## Typed, header-declared serialization

Every value is declared with an RDF type in the `---` frontmatter and validated
+ escaped when rendered. A value that does not satisfy its type **throws**
instead of being emitted:

- `iri` rejects anything that is not a syntactically valid absolute IRI, so a
  value like `http://x/> . } DROP GRAPH <g` throws instead of breaking out of
  `<…>`.
- `string` and `$"…"` content escape `\`, `"`, newlines, CR, tab.
- Numeric/`bool`/date types accept only matching host values and emit canonical
  forms; `"10"` is not an `int`.
- `pname` enforces a conservative prefixed-name subset and never injects
  `PREFIX` declarations.
- `$<…>` percent-encodes each hole and validates the assembled IRI as absolute.

## The `raw` type

`raw` inserts a value verbatim, unescaped. It is the single, **auditable**
unsafe path — declared in the frontmatter, so a reviewer greps one place. Never
feed user input into a `raw` parameter.

## Up-front validation & fail-fast

The render context is validated against the header before any output is
produced. And the `${ }` / `$"…"` / `$<…>` / `{% … %}` syntax and the leading
`---` are invalid in SPARQL/Turtle/N-Triples, so a template that reaches a
parser unrendered fails loudly. The conformance suite checks both directions
with a real SPARQL parser.
