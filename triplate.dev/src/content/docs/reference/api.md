---
title: API overview
description: The compile / render model, binding semantics and error hierarchy shared by all implementations.
---

Every implementation exposes the same small surface: parse a template once into
a `CompiledTemplate`, then render it many times against a context. The
per-language pages give exact signatures, accepted value types, custom-type
registration and error classes:

- [TypeScript](./typescript/)
- [Python](./python/)
- [Java](./java/)

For task-oriented, copy-pasteable templates see the [Examples](../../language/comments/).

## The model

```
compile(template)                 → CompiledTemplate     parse once
CompiledTemplate.render(context)  → string               render many
render(template, context)         → string               one-shot convenience
isTemplate(text)                  → boolean              cheap, non-throwing detection
```

A `CompiledTemplate` also exposes the parsed `schema` (declared parameters) and
`examples` (named `example` blocks, each with its source `line`/`column`), can
render a named example set for development previews (`previewExample` /
`preview_example`), and can build a validated context from raw string inputs
(`contextFromStrings` / `context_from_strings`).

| | TypeScript | Python | Java |
|---|---|---|---|
| Compile | `compile(str)` | `compile(str)` | `Triplate.compile(str)` |
| Detect template | `isTemplate(str)` | `is_template(str)` | `Triplate.isTemplate(str)` |
| One-shot render | `render(str, ctx?)` | `render(str, ctx?, **kw)` | `Triplate.render(str, map?)` |
| Render compiled | `tmpl.render(ctx?)` | `tmpl.render(ctx?, **kw)` | `tmpl.render(map?)` |
| Schema / examples | `tmpl.schema` / `.examples` | `tmpl.schema` / `.examples` | `tmpl.schema()` / `.examples()` |
| Preview example | `tmpl.previewExample(id)` | `tmpl.preview_example(id)` | `tmpl.previewExample(id)` |
| Context from strings | `tmpl.contextFromStrings(map)` | `tmpl.context_from_strings(map)` | `tmpl.contextFromStrings(map)` |
| Frontmatter prefixes | `tmpl.frontmatterPrefixes()` | `tmpl.frontmatter_prefixes()` | `tmpl.frontmatterPrefixes()` |
| Register custom type | `registerType(name, fn)` | `register_type(name, fn)` | `TypeRegistry.registerType(name, fn)` |

`isTemplate` returns `true` when the text opens with a `---` frontmatter header —
the positive detection marker that complements fail-fast (an unprocessed template
is invalid host syntax). `contextFromStrings` coerces raw string inputs (CLI args,
editor prompts) to their declared scalar types and validates the result; see each
language page for its exact rules.

`frontmatterPrefixes` returns the namespace prefixes referenced **in the
frontmatter** — in `example` binding values (`prefix:local`), in literal datatypes
on those values (`"x"^^p:t`), and in `literal(p:t)` parameter types. Because tools
typically blank the frontmatter before tokenizing the body, these usages are
invisible to a body token stream; a linter can use this set to avoid flagging a
body `PREFIX` declaration as unused. Full `<iri>` values contribute no prefix, and
the empty string denotes the default prefix (`:local`).

## Binding semantics

- **The frontmatter is the contract.** Every reference must resolve to a declared
  param or a loop variable — an undeclared reference is a compile-time error. The
  context is validated against `params` up front, and unknown keys are rejected.
- **Strict typing.** Values are validated against their declared RDF type;
  mismatches — including list-vs-scalar and `min`/`max` cardinality — throw.
- **Optional params** may be absent and are consumed with `{% if %}`.
- **Determinism.** Given the same template and context, all implementations
  produce byte-identical output (enforced by the shared conformance suite).

## Errors

All implementations share one hierarchy; each error carries an optional 1-based
`line`/`column`. `TriplateSyntaxError` is raised while compiling; the rest while
rendering.

```
TriplateError
├── TriplateSyntaxError        compile time — malformed template
├── TriplateBindingError       render — missing / unknown / absent value
├── TriplateTypeError          render — value fails its declared type
└── TriplateCardinalityError   render — array min/max violated
```

The base type is `Error` (TypeScript), `Exception` (Python) and an unchecked
`RuntimeException` (Java).
