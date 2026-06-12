---
title: Installation
description: Install Triplate for TypeScript/JavaScript, Python or Java.
---

## TypeScript / JavaScript

```bash
npm install triplate
```

```ts
import { compile, render } from 'triplate';

const tmpl = compile('---\nparams { c: iri }\n---\n?s a ${c}');
tmpl.render({ c: 'http://example.org/Person' });
```

## Python

```bash
pip install triplate          # add [rdflib] for the term type
```

```python
from triplate import compile, render

tmpl = compile("---\nparams { c: iri }\n---\n?s a ${c}")
tmpl.render(c="http://example.org/Person")
```

## Java

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
```

All three implementations share the language and produce byte-identical output,
verified by a common conformance suite.
