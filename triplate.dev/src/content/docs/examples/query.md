---
title: SPARQL Query
description: Template SPARQL queries for backend applications and AI agents.
---

Backend services and AI agents rarely run a fixed query — they run the *same*
query with different values. Triplate replaces string concatenation with a
declared, typed interface: the template states what it needs, the engine
validates and escapes every value, and a malformed input **throws** instead of
reaching your endpoint. For the formal rules see the
[Specification](../../specification/); for the host API see the
[API reference](../../reference/api/).

## Query Parameters

The frontmatter is the query's signature. A backend passes request data, an AI
agent fills the declared parameters — either way, the caller cannot inject
syntax, only values.

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

Rendered without `limit` (the `{% if %}` line disappears entirely):

```sparql
SELECT ?s WHERE {
  SERVICE <http://dbpedia.org/sparql> {
    ?s a <http://xmlns.com/foaf/0.1/Person> .
  }
}
```

## `VALUES` List

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

## `FILTER … IN` List

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

## Property Path Alternatives

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

## Optional Parameters

`{% if %}` conditions are type-directed: an `optional` parameter tests
presence, so filters appear only when a value was supplied.

```sparql
---
params { type: iri, nameFilter: string optional, limit: int optional }
---
SELECT ?s ?name WHERE {
  ?s a ${type} ; rdfs:label ?name .
{% if nameFilter %}
  FILTER(CONTAINS(?name, ${nameFilter}))
{% endif %}
}
{% if limit %}LIMIT ${limit}{% else %}LIMIT 100{% endif %}
```

## Runnable Examples

An `example` block (in the frontmatter, alongside `params`) declares a named,
validated set of sample values — so an IDE, a CLI or an AI agent can preview
and execute the query without hand-built input. They are development fixtures,
never production defaults; see
[Example blocks](../../language/substitutions/#example-blocks).

```sparql
---
params {
  service: iri
  classes: iri[] min 1
  limit:   int optional
}
example demo "DBpedia people & orgs" {
  service: <http://dbpedia.org/sparql>
  classes: [ foaf:Person, foaf:Organization ]
  limit:   10
}
---
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?s WHERE {
  SERVICE ${service} {
{% for c in classes join "UNION" %}
    { ?s a ${c} }
{% endfor %}
  }
}
{% if limit %}LIMIT ${limit}{% endif %}
```
