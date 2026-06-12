---
title: The header & types
description: The mandatory --- frontmatter declares every input once.
---

Every template begins with a `---` frontmatter header declaring its inputs. The
whole block is consumed (nothing leaks into the output); the body then uses bare
`${ name }` references — no inline types.

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
---
```

- **Sections are brace-delimited.** `params { … }` is mandatory; `example … { … }`
  blocks are optional (see [Examples & tooling](/language/comments/)). `params`
  and `example` are keywords only here, so any parameter name is allowed.
- **Types**: `iri`, `pname`, `string`, `int`, `decimal`, `double`, `bool`,
  `date`, `dateTime`, `time`, `literal(<dt>)`, `term`, `raw`.
- **Modifiers** (in order): `<type> ['[]'] ['optional'] ['min' N] ['max' N]`.
  `[]` marks an array; `min`/`max` bound its length; `optional` marks that a
  value may be omitted.
- **`raw`** inserts verbatim with no escaping — the single auditable unsafe
  hatch, visible in the header.

The engine validates the whole context against the header up front (missing
required, unknown key, wrong type, out-of-range cardinality). Typing is strict:
`"10"` is not an `int`, a list is not a scalar.

## Values

`${ name }` / `${ p.id }` reference declared params or loop variables. The same
reference serializes differently by **construct**: standalone → per its declared
type; inside `$"…"` → string-escaped; inside `$<…>` → percent-encoded.

Keywords and type names are case-insensitive; variable names, IRIs, and string
content are case-sensitive.
