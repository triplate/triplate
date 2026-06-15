---
title: Examples
description: Real-world recipes for every syntax element, plus example blocks, inert regions and template detection.
---

A cookbook of realistic templates. Each recipe shows a scenario, the Triplate
template, and the exact rendered output. For the formal rules behind each
construct see the [Specification](../specification/); for the host API see the
[API reference](../reference/api/).

## Inject a `VALUES` list from an array

The `${...x}` **spread** operator expands an array of a scalar type, serialized
per its declared type and space-separated by default — ideal for `VALUES`.

```sparql
---
params { graphs: iri[] }
---
SELECT * WHERE {
  GRAPH ?g { ?s ?p ?o }
  VALUES ?g { ${...graphs} }
}
```

```sparql
SELECT * WHERE {
  GRAPH ?g { ?s ?p ?o }
  VALUES ?g { <http://ex.org/g1> <http://ex.org/g2> }
}
```

When you need a per-row layout or tuples, use `{% for %}` instead:

```sparql
---
params { classes: iri[] }
---
SELECT ?s WHERE {
  ?s a ?type .
  VALUES ?type {
{% for c in classes %}
    ${c}
{% endfor %}
  }
}
```

```sparql
SELECT ?s WHERE {
  ?s a ?type .
  VALUES ?type {
    <http://ex.org/Person>
    <http://ex.org/Org>
  }
}
```

## Build a `FILTER … IN` list

A spread with `join` sets the separator (padded with spaces by default; add
`explicit` for a verbatim `","`).

```sparql
---
params { ids: int[] }
---
SELECT * WHERE {
  ?s ex:code ?c .
  FILTER(?c IN (${...ids join ","}))
}
```

```sparql
SELECT * WHERE {
  ?s ex:code ?c .
  FILTER(?c IN (10 , 20 , 30))
}
```

## Property-path alternatives

Spread a list of predicates into an alternative path with `join "|"`.

```sparql
---
params { predicates: pname[] }
---
SELECT ?o WHERE {
  ?s ${...predicates join "|"} ?o .
}
```

```sparql
SELECT ?o WHERE {
  ?s rdfs:label | skos:prefLabel | foaf:name ?o .
}
```

## Federated query with an optional limit

`${endpoint}` injects a `SERVICE` IRI; the `{% if %}` block disappears entirely
when the optional `limit` is absent (the directive's line is trimmed).

```sparql
---
params { endpoint: iri, type: iri, limit: int optional }
---
SELECT ?s WHERE {
  SERVICE ${endpoint} {
    ?s a ${type} .
  }
}
{% if limit %}LIMIT ${limit}{% endif %}
```

Rendered without `limit`:

```sparql
SELECT ?s WHERE {
  SERVICE <http://dbpedia.org/sparql> {
    ?s a <http://xmlns.com/foaf/0.1/Person> .
  }
}
```

## Mint IRIs from identifiers

`$<…>` percent-encodes each hole as one opaque path component, so untrusted ids
can never break out of the IRI (note the encoded space in `bob 2`).

```sparql
---
params { ids: string[] }
---
SELECT ?p WHERE {
  VALUES ?p {
{% for id in ids %}
    $<http://example.org/person/${id}>
{% endfor %}
  }
}
```

```sparql
SELECT ?p WHERE {
  VALUES ?p {
    <http://example.org/person/alice>
    <http://example.org/person/bob%202>
  }
}
```

## Language-tagged and typed literals

`$"…"` builds an escaped string literal; add a `@${lang}` tag (static or dynamic)
or a `^^` datatype. A `date`-typed value serializes to a canonical typed literal.

```sparql
---
params { label: string, lang: string, born: date }
---
INSERT DATA {
  ex:x rdfs:label $"${label}"@${lang} ;
       ex:born ${born} .
}
```

```sparql
INSERT DATA {
  ex:x rdfs:label "Köln"@de ;
       ex:born "1989-11-09"^^<http://www.w3.org/2001/XMLSchema#date> .
}
```

## Generate RDF data, not just queries

Triplate is host-agnostic — the same constructs emit Turtle/TriG. Loop over
records to build a dataset.

```turtle
---
params { people: { id: iri, name: string, age: int }[] }
---
@prefix ex: <http://example.org/> .
{% for p in people %}
${p.id} a ex:Person ;
  ex:name $"${p.name}" ;
  ex:age ${p.age} .
{% endfor %}
```

```turtle
@prefix ex: <http://example.org/> .
<http://example.org/alice> a ex:Person ;
  ex:name "Alice" ;
  ex:age 30 .
<http://example.org/bob> a ex:Person ;
  ex:name "Bob" ;
  ex:age 25 .
```

## Custom types

Need a domain type? Register a serializer once and use it as a header type. See
[Extensibility](../reference/api/#custom-types) for each language's signature.

```ts
import { registerType } from 'triplate';

registerType('uuidref', (value, pos) => {
  if (!/^[0-9a-f-]{36}$/.test(String(value))) {
    throw new Error('not a UUID');
  }
  return `<urn:uuid:${value}>`;
});
// then:  params { id: uuidref }
```

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
