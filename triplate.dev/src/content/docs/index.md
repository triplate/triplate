---
title: Triplate
description: A templating engine for RDF query & data languages — SPARQL, Turtle, TriG and N-Triples.
---

Triplate lets you write **templates for RDF query and data languages** —
SPARQL, Turtle, TriG, N-Triples — with a typed `---` frontmatter header, loops
and conditionals. Values are validated and escaped per their declared RDF type,
so rendered output is injection-safe by construction, and the syntax is invalid
in the host language, so an unprocessed template fails fast.

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

Rendered with the `demo` example set:

```sparql
PREFIX foaf: <http://xmlns.com/foaf/0.1/>
SELECT ?s WHERE {
  SERVICE <http://dbpedia.org/sparql> {
    { ?s a foaf:Person } UNION { ?s a foaf:Organization }
  }
}
LIMIT 10
```

## Highlights

- **One typed frontmatter header.** `---` … `---` declares every input — type,
  cardinality (`min`/`max`), optionality — and is stripped from the output, so
  no whitespace or comments leak. The body stays clean `${ }` references.
- **Injection-safe by construction.** Each value is validated and escaped per
  its declared RDF type. The only unescaped path is the explicit `raw` type.
- **Host-agnostic.** The `${ }`, `$"…"`, `$<…>`, `{% … %}` syntax and the
  leading `---` are invalid in SPARQL/Turtle/TriG/N-Triples, so fail-fast holds.
- **Loops & conditionals.** `{% for %}` with `join`, `{% if %}` with
  type-directed conditions that make `optional` params usable.
- **Runnable examples.** `example` blocks let an IDE preview and execute a query
  without hand-built input.
- **Three reference implementations** — [TypeScript](https://www.npmjs.com/package/triplate),
  [Python](https://pypi.org/project/triplate/) and Java — with byte-identical output.
