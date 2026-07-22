---
title: RDF Data
description: Author RDF artefacts with provenance metadata, or generate synthetic datasets.
---

Triplate is host-agnostic — the same constructs that template SPARQL emit
Turtle, TriG and N-Triples. That makes it a small, safe generator for RDF
artefacts: stamp authorship and provenance onto a dataset, or synthesize test
data from plain records.

## Provenance Metadata

A build pipeline can stamp every published artefact with the same, validated
metadata record. Typed values serialize canonically — a `dateTime` becomes a
typed literal, an invalid IRI throws instead of corrupting the artefact.

```turtle
---
params {
  dataset: iri
  title:   string
  author:  iri
  created: dateTime
  version: string optional
}
---
@prefix dcterms: <http://purl.org/dc/terms/> .
@prefix owl:     <http://www.w3.org/2002/07/owl#> .

${dataset}
  dcterms:title   $"${title}"@en ;
  dcterms:creator ${author} ;
{% if version %}
  owl:versionInfo ${version} ;
{% endif %}
  dcterms:created ${created} .
```

```turtle
@prefix dcterms: <http://purl.org/dc/terms/> .
@prefix owl:     <http://www.w3.org/2002/07/owl#> .

<http://example.org/dataset/people>
  dcterms:title   "People dataset"@en ;
  dcterms:creator <https://example.org/team/data> ;
  owl:versionInfo "1.4.0" ;
  dcterms:created "2026-07-22T09:30:00Z"^^<http://www.w3.org/2001/XMLSchema#dateTime> .
```

## Synthesized Dataset

Loop over an array of records to build a dataset — handy for test fixtures and
demo data. Each field is escaped per its declared type.

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

## Mint IRIs

`$<…>` percent-encodes each hole as one opaque path component, so untrusted
ids can never break out of the IRI (note the encoded space in `bob 2`).

```turtle
---
params { people: { id: string, name: string }[] }
---
@prefix ex: <http://example.org/> .
{% for p in people %}
$<http://example.org/person/${p.id}> a ex:Person ;
  ex:name $"${p.name}" .
{% endfor %}
```

```turtle
@prefix ex: <http://example.org/> .
<http://example.org/person/alice> a ex:Person ;
  ex:name "Alice" .
<http://example.org/person/bob%202> a ex:Person ;
  ex:name "Bob" .
```

## Literals

`$"…"` builds an escaped string literal; add a `@${lang}` tag (static or
dynamic) or a `^^` datatype. A `date`-typed value serializes to a canonical
typed literal.

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

## Custom Types

Need a domain type? Register a serializer once and use it as a header type. See
the per-language API pages for exact signatures, e.g.
[Custom types in TypeScript](/reference/api/typescript/#custom-types).

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
