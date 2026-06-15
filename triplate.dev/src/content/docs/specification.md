---
title: Specification (v0.3)
description: The Triplate language specification — host-agnostic templating for SPARQL, Turtle, TriG and N-Triples.
---

:::note[Document status]
- **Version:** 0.3
- **Status:** Draft — the language surface may still change before 1.0.
- **Date:** 2026-06-15
- **Authors:** Sebastian Faubel; Claude Code
:::

Triplate is a templating language for RDF query and data languages. A template
declares its inputs in a mandatory `---` frontmatter header and uses `${ }`
substitutions, `$"…"` / `$<…>` constructs, and `{% for %}` / `{% if %}`
directives in the body. Every value is validated and escaped according to its declared RDF
type, so rendered output is injection-safe by construction. The template syntax
is **not valid** SPARQL/Turtle/N-Triples, so an unprocessed template fails to
parse if it reaches an endpoint by mistake (fail-fast).

This page is the human-readable specification. The formal grammar is in
[`spec/grammar.ebnf`](https://github.com/triplate/triplate/blob/main/triplate.dev/spec/grammar.ebnf);
the executable conformance suite is in
[`spec/conformance/`](https://github.com/triplate/triplate/tree/main/triplate.dev/spec/conformance).
Every conforming implementation must produce **byte-identical** output for the
fixtures and raise the named error for every must-throw case.

## 1. Host-agnostic by design

Triplate's only special tokens are `${`, `$"`, `$<`, and `{%`. None is a valid
token in SPARQL, Turtle, TriG, or N-Triples, so fail-fast holds in all of them.
A bare `$name` (a SPARQL variable) and anything `@…` (language tags, Turtle
`@prefix`/`@base`) pass through untouched. Three regions are **inert** — a `$`
or `{` inside them is literal text:

- **Comments** — `#` to end of line.
- **IRI references** — a complete `<…>` (protects percent-encodings like
  `%C3%A9`). Build IRIs from variables with `$<…>` (§5).
- **String literals** — `"…"`, `'…'`, `"""…"""`, `'''…'''`. Build strings from
  variables with `$"…"` (§4).

> **Term serialization profiles.** SPARQL, Turtle, and TriG share term syntax
> (bare `42`/`true`, prefixed names), so the default serializers target them.
> N-Triples/N-Quads require typed literals and forbid prefixed names; that is a
> per-dialect *term profile* (planned), not a syntax difference.

Keywords and type names fold ASCII case (`{% FOR %}`, `iri`/`IRI`); variable
names, IRIs, string content, and language tags are case-sensitive.

## 2. The header — `---` frontmatter (mandatory)

A template begins with a `---`-delimited frontmatter block. The whole block,
through the closing `---` and its trailing newline, is **consumed and never
emitted** — so nothing in the header (comments, blank lines) leaks into the
output. Sections are **brace-delimited**, which keeps parameter names
unrestricted.

```
---
params {
  service:  iri
  endpoint: iri optional
  classes:  iri[] min 1
  tags:     string[] optional max 5
  people:   { id: iri, name: string optional }[] min 1
  limit:    int
}

# a comment inside the frontmatter is metadata (never emitted)
example dbpedia "DBpedia — people" {
  service: <http://dbpedia.org/sparql>
  classes: [ foaf:Person, foaf:Organization ]
  limit:   10
}
---
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?s WHERE { … }
```

- The frontmatter has a mandatory `params { … }` section and zero or more
  `example … { … }` sections (§8). `#` comments and whitespace inside `---`
  are ignored; both declarations and bindings use `name: …`.
- **Types**: `iri`, `pname`, `string`, `int`, `decimal`, `double`,
  `bool`, `date`, `dateTime`, `time`, `literal(<dt>)`, `term`, `raw`.
- **Modifiers** (fixed order): `<type> ['[]'] ['optional'] ['min' N] ['max' N]`.
  `[]` marks an array; `min`/`max` bound its length (valid only after `[]`);
  `optional` marks that a value may be absent.
- **Records**: `{ field: type, … }`; fields may be optional, arrays, or nested
  records.
- **`raw`** inserts a value verbatim everywhere it is used (no validation or
  escaping) — the single, auditable unsafe escape hatch.
- At render the engine validates the whole context up front — missing required
  parameter, unknown key, wrong type, out-of-range cardinality — before
  producing any output.
- `params` and `example` are keywords only at the frontmatter top level; every
  parameter name lives inside a brace block, so any name is allowed.

The leading `---` is also a positive **"this is a Triplate template"** marker
for tooling.

## 3. Values — `${ … }`

A reference is `${ path }`, where `path` is `Ident('.'Ident)*`. Its type comes
from the declaration; there are no inline types. The same reference serializes
differently by **construct**:

| Construct | `${x}` becomes |
|-----------|----------------|
| standalone `${x}` | serialized per its declared type (`iri`→`<…>`, `string`→`"…"`, `int`→`42`, …) |
| inside `$"…"` | the value's lexical content, **string-escaped** |
| inside `$<…>` | the value's lexical content, **percent-encoded**; the assembled IRI is validated absolute |

`raw` values are inserted verbatim in all three.

### Spread — `${ ... path }`

```
VALUES ?g { ${...graphs} }                 →  VALUES ?g { <…a> <…b> <…c> }
FILTER(?o IN (${...ids join ","}))         →  FILTER(?o IN (1 , 2 , 3))
FILTER(?o IN (${...ids join "," explicit}))→  FILTER(?o IN (1,2,3))
```

`${ ...path }` expands an **array** of a serializable scalar, serializing each
element exactly as a standalone `${element}` would, then joining them. `path`
must resolve to an array (a non-array is a type error) of a scalar — a record
array is a type error; loop over it with `{% for %}` instead. An empty array
emits nothing.

The optional `join "<text>" [explicit]` clause is identical to the loop's (§6):
the text is padded with one space each side unless `explicit`. Unlike the loop,
the **default separator (no `join`) is a single space**, since adjacent terms
need a delimiter to be valid (`<a> <b>`, not `<a><b>`). `join`/`explicit` are
only valid after `...`.

## 4. Strings — `$"…"`

```
$"Hello ${name}"                 →  "Hello World"
$"Result #${index}: ${label}"    →  "Result #2: Acme"
$"localized"@en                  →  "localized"@en
$"Hello ${name}"@${lang}         →  "Hello World"@de   (dynamic tag)
$"42"^^xsd:int                   →  "42"^^xsd:int
```

Holes are `${ … }`. Author escapes `\\`, `\"`, `\n`, `\r`, `\t` are recognized;
output is re-escaped. A `${ }` whose value is `raw` is inserted verbatim
(unsafe). The suffix is a static `@lang`, a dynamic `@${lang}`, or a static
`^^<iri>` / `^^prefix:name`.

## 5. IRIs — `$<…>`

```
$<http://example.org/person/${id}>   →  <http://example.org/person/42>
$<http://example.org/${ns}/item>     →  <http://example.org/core/item>   (ns: raw)
```

Holes are percent-encoded to the unreserved set `A–Z a–z 0–9 - . _ ~`
(everything else, including `/ ? # :` and non-ASCII, → UTF-8 `%XX`), so each
hole is one opaque component. A `raw` value is inserted verbatim. The assembled
string is validated as an absolute IRI — so even `raw` cannot break out.

## 6. Loops — `{% for %}`

```
{% for c in classes join "UNION" %}
  { ?s a ${c} }
{% endfor %}
```

`{% for <item> in <source> [join "<text>" [explicit]] %}` … `{% endfor %}`.

- `<source>` is a declared array parameter (`classes`) or a path into a loop
  variable (`g.members`) for nesting.
- The element type comes from the source; cardinality is declared in the
  header (no `+` at the loop).
- **Join**: the separator is emitted between iterations only; boundary
  whitespace is merged. By default the join text is padded with one space each
  side (`join "UNION"` → `… } UNION { …`); `explicit` inserts it verbatim.
- **Block trimming**: a directive **alone on its line** has its line (and
  newline) removed; an **inline** tag renders in place.

## 7. Conditionals — `{% if %}`

```
{% if nameFilter %}FILTER(CONTAINS(?n, ${nameFilter})){% endif %}
{% if limit %}LIMIT ${limit}{% else %}LIMIT 100{% endif %}
```

`{% if <cond> %} [{% elif <cond> %}] [{% else %}] {% endif %}`. Conditions are
**type-directed** — well-defined because everything is declared:

| Declared as | `{% if x %}` tests |
|---|---|
| `bool` | its value |
| anything `optional` | whether it is present |
| array | whether it is non-empty |
| required scalar | compile error (always true) |

`not` negates. There are no comparison operators. `{% if %}` is what makes
`optional` parameters consumable.

## 8. Examples — `example` (optional, in the frontmatter)

```
example dbpedia "DBpedia — people" {
  service: <http://dbpedia.org/sparql>
  classes: [ foaf:Person, foaf:Organization ]
  limit:   10
}
```

`example <id> ["<description>"] { … }` — `<id>` is a unique slug; the
description is optional. Bindings use `name: value`, where values are RDF term
syntax (`<…>`, `prefix:local`, `"…"`/`@lang`/`^^dt`, numbers, bools, `[ … ]`,
`{ … }`) and are validated against `params`. Example sets are
**development/preview fixtures, not production defaults**: `render(context)`
still requires real values, while `previewExample(id)` renders with a set.
Prefixed names in examples are resolved against the template's `PREFIX`
declarations for preview.

## 9. API

```
compile(template)                 -> CompiledTemplate     (parse once)
CompiledTemplate.render(context)  -> string               (render many)
CompiledTemplate.schema, .examples, .previewExample(id)
render(template, context)         -> string               (one-shot)
```

Errors: `TriplateError` → `TriplateSyntaxError` (compile),
`TriplateBindingError`, `TriplateTypeError`, `TriplateCardinalityError`
(render), with line/column where applicable.

## 10. Deferred

Production defaults for optional params (`?=`), `{% elif %}` is supported but
comparison conditions are not, value filters, `{% include %}`, the N-Triples
term profile, and host-language type generation from the header.
