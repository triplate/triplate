# Triplate (Python)

A templating engine for RDF query & data languages (SPARQL, Turtle, …) with a
typed `---` frontmatter header, injection-safe values, loops and conditionals.
Python reference implementation of the [Triplate language](https://triplate.dev).

```python
from triplate import compile

template = """\
---
params {
  classes: iri[] min 1
  limit:   int optional
}
---
SELECT ?s WHERE {
{% for c in classes join "UNION" %}
  { ?s a ${c} }
{% endfor %}
}
{% if limit %}LIMIT ${limit}{% endif %}
"""

tmpl = compile(template)
sparql = tmpl.render(classes=["http://example.org/Person"], limit=10)
```

Every input is declared in the mandatory `---` frontmatter header with its RDF
type; the context is validated and each value escaped accordingly, so rendered
output is injection-safe and an unprocessed template fails fast.

See [triplate.dev](https://triplate.dev) for the full guide and specification.
