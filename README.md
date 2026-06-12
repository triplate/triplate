# Triplate

[![CI](https://github.com/triplate/triplate/actions/workflows/ci.yml/badge.svg)](https://github.com/triplate/triplate/actions/workflows/ci.yml)
[![npm](https://img.shields.io/npm/v/triplate)](https://www.npmjs.com/package/triplate)
[![PyPI](https://img.shields.io/pypi/v/triplate)](https://pypi.org/project/triplate/)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

[triplate.dev](https://triplate.dev) — a templating engine for RDF query &
data languages (SPARQL, Turtle, TriG, N-Triples).

A template declares its inputs in a typed `---` frontmatter header and uses
`${ }` substitutions, `$"…"` / `$<…>` constructs, and `{% for %}` / `{% if %}`
directives. Values are validated and escaped per their declared RDF type, so
rendered output is injection-safe by construction — and the syntax is invalid in
the host language, so an unprocessed template fails fast.

```sparql
---
params {
  classes: iri[] min 1
  limit: int optional
}
---
SELECT ?s WHERE {
{% for c in classes join "UNION" %}
  { ?s a ${c} }
{% endfor %}
}
{% if limit %}LIMIT ${limit}{% endif %}
```

```ts
import { compile } from 'triplate';          // npm install triplate
compile(template).render({ classes: [...], limit: 10 });
```

```python
from triplate import compile                  # pip install triplate
compile(template).render(classes=[...], limit=10)
```

```java
import dev.triplate.Triplate;                  // dev.triplate:triplate (Maven)
Triplate.compile(template).render(Map.of("classes", List.of(...), "limit", 10));
```

## Repository layout

| Path | Contents |
|------|----------|
| [`triplate.dev/`](triplate.dev/) | Docs site (Astro/Starlight) → [triplate.dev](https://triplate.dev), and the spec/tooling it publishes |
| [`triplate.dev/spec/`](triplate.dev/spec/) | [EBNF grammar](triplate.dev/spec/grammar.ebnf) + [shared conformance fixtures](triplate.dev/spec/conformance/) both implementations satisfy byte for byte (the prose spec is the site's Specification page) |
| [`triplate.dev/syntax/`](triplate.dev/syntax/) | TextMate **injection grammar** that highlights Triplate on top of SPARQL (VS Code, Sublime, Shiki) |
| [`triplate-ts/`](triplate-ts/) | TypeScript reference implementation → npm `triplate` |
| [`triplate-py/`](triplate-py/) | Python reference implementation → PyPI `triplate` |
| [`triplate-java/`](triplate-java/) | Java reference implementation → Maven `dev.triplate:triplate` |

## Development

```bash
npm install                  # JS workspaces (TS package + docs site)
npm test                     # TypeScript tests + conformance + SPARQL round-trip
npm run test:syntax          # highlighter grammar tokenizer test

cd triplate-py
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/python -m pytest tests -q   # Python tests + conformance

mvn -f triplate-java/pom.xml test     # Java tests + conformance

npm run docs:build           # build the docs site (and verify highlighting)
```

All three implementations run the same conformance suite from
[`triplate.dev/spec/conformance/`](triplate.dev/spec/conformance/), which
guarantees byte-identical output across languages.

## Releasing

Releases are fully automated with
[release-please](https://github.com/googleapis/release-please): write
[conventional commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`,
`docs:`, `chore:` …) and a release PR accumulates the changelog on `main`.
**Merging that PR** bumps the lockstep version in all package manifests, tags
`vX.Y.Z`, and publishes to npm and PyPI. The docs site deploys to GitHub Pages
on every push to `main`.

## License

[MIT](LICENSE)
