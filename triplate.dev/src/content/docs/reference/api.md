---
title: API (TypeScript, Python & Java)
description: compile / render, schema and example introspection, and the error hierarchy.
---

```
compile(template)                 -> CompiledTemplate     (parse once)
CompiledTemplate.render(context)  -> string               (render many)
CompiledTemplate.schema, .examples
CompiledTemplate.previewExample(id) / .preview_example(id)
render(template, context)         -> string               (one-shot)
```

## TypeScript

```ts
import { compile } from 'triplate';

const tmpl = compile(templateString);
tmpl.render({ service: 'http://dbpedia.org/sparql', classes: ['http://example.org/Person'], limit: 10 });
tmpl.previewExample('demo');           // render a named example block
tmpl.schema.params;                    // declared inputs
```

Accepted values: `string`, `number`, `bigint`, `boolean`, `Date`, RDF/JS `Term`
(for `term`), arrays, and nested objects.

## Python

```python
from triplate import compile, render

tmpl = compile(template_string)
tmpl.render(service="…", classes=[...], limit=10)   # kwargs or a mapping
tmpl.preview_example("demo")
```

Accepted values: `str`, `int`, `float`, `bool`, `datetime`/`date`/`time`,
rdflib terms (for `term`), lists, dicts.

## Java

```java
import dev.triplate.Triplate;
import dev.triplate.CompiledTemplate;

CompiledTemplate tmpl = Triplate.compile(templateString);
tmpl.render(Map.of("service", "http://dbpedia.org/sparql",
                   "classes", List.of("http://example.org/Person"), "limit", 10));
tmpl.previewExample("demo");           // render a named example block
tmpl.schema().params();                // declared inputs
```

Accepted values: `String`, `Long`/`Integer`/`BigInteger`, `Double`/`BigDecimal`,
`Boolean`, `java.time` values or ISO strings, a `dev.triplate.Term` (for `term`),
`List`, and `Map<String, Object>`.

## Binding semantics

- **The frontmatter is the contract.** Every reference must resolve to a
  declared param or a loop variable (undeclared → compile error). The context is
  validated against `params` up front; unknown keys are rejected.
- **Strict typing**; mismatches (incl. list-vs-scalar, cardinality) throw.
- **Optional params** are absent-ok and consumed with `{% if %}`.

## Errors

```
TriplateError
├── TriplateSyntaxError        (compile time)
├── TriplateBindingError       (render: missing / unknown / absent value)
├── TriplateTypeError          (render: value fails validation)
└── TriplateCardinalityError   (render: min/max violated)
```

## Extensibility

```ts
import { registerType, TriplateTypeError } from 'triplate';
registerType('uuidref', (value, pos) => { /* validate, return SPARQL text */ });
// usable as a header type:  params { id: uuidref }
```

```java
import dev.triplate.TypeRegistry;
TypeRegistry.registerType("uuidref", (value, line, column) -> { /* validate, return SPARQL text */ });
// usable as a header type:  params { id: uuidref }
```
