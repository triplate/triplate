---
title: Installation
description: Install Triplate for TypeScript/JavaScript, Python or Java.
---

Triplate ships as one package per language. Pick the one for your stack — they
implement the **same language** and produce **byte-identical output**, verified
by a shared conformance suite, so a template authored against one runs unchanged
against the others. Every package has **zero required runtime dependencies**.

## TypeScript / JavaScript

Published to npm as [`triplate`](https://www.npmjs.com/package/triplate). Ships
as ES modules with bundled TypeScript types; requires Node 18+ (or any modern
bundler / runtime with ESM).

```bash
npm install triplate
```

```ts
import { compile, render } from 'triplate';

const tmpl = compile('---\nparams { c: iri }\n---\n?s a ${c}');
tmpl.render({ c: 'http://example.org/Person' });
// → "?s a <http://example.org/Person>"
```

## Python

Published to PyPI as [`triplate`](https://pypi.org/project/triplate/); requires
Python 3.10+. The core is dependency-free. Install the optional `rdflib` extra
only if you want to pass `rdflib` terms to the `term` type:

```bash
pip install triplate              # core, no dependencies
pip install "triplate[rdflib]"    # adds rdflib for the term type
```

```python
from triplate import compile, render

tmpl = compile("---\nparams { c: iri }\n---\n?s a ${c}")
tmpl.render(c="http://example.org/Person")
# → "?s a <http://example.org/Person>"
```

## Java

Published to Maven Central under `dev.triplate`; requires Java 17+ (the library
uses records and sealed types). No runtime dependencies.

```xml
<dependency>
  <groupId>dev.triplate</groupId>
  <artifactId>triplate</artifactId>
  <version>0.3.0</version>
</dependency>
```

```java
import dev.triplate.Triplate;
import java.util.Map;

String sparql = Triplate.render("---\nparams { c: iri }\n---\n?s a ${c}",
    Map.of("c", "http://example.org/Person"));
// → "?s a <http://example.org/Person>"
```

## Next steps

- Browse the [Examples](../language/comments/) for real-world, use-case-based
  recipes covering every syntax element.
- See the per-language [API reference](../reference/api/) for the full
  `compile` / `render` surface, accepted value types, custom types and errors.
