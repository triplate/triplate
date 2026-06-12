# Triplate (Java)

A templating engine for RDF query & data languages (SPARQL, Turtle, …) with a
typed `---` frontmatter header, injection-safe values, loops and conditionals.
Java reference implementation of the [Triplate language](https://triplate.dev).

```java
import dev.triplate.Triplate;
import java.util.List;
import java.util.Map;

String template = """
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
    """;

String sparql = Triplate.render(template, Map.of(
    "classes", List.of("http://example.org/Person"),
    "limit", 10));
```

Compile once, render many:

```java
import dev.triplate.CompiledTemplate;

CompiledTemplate tmpl = Triplate.compile(template);
String sparql = tmpl.render(Map.of("classes", List.of("http://example.org/Person")));
```

Every input is declared in the mandatory `---` frontmatter header with its RDF
type; the context is validated and each value escaped accordingly, so rendered
output is injection-safe and an unprocessed template fails fast.

## Context values

| Declared type | Java value |
|---------------|------------|
| `iri`, `pname`, `string`, `raw`, `literal(<dt>)` | `String` |
| `int` | `Long` / `Integer` / `BigInteger` |
| `decimal`, `double` | `Double` / `BigDecimal` (or an integral number) |
| `bool` | `Boolean` |
| `date`, `dateTime`, `time` | a `java.time` value or an ISO `String` |
| `term` | a `dev.triplate.Term` |
| `T[]` | `java.util.List` |
| `{ … }` | `java.util.Map<String, Object>` |

The `term` type accepts any implementation of the dependency-free
[`Term`](src/main/java/dev/triplate/Term.java) interface, so you can adapt
Apache Jena or Eclipse RDF4J terms without the core depending on either.

Errors: `TriplateError` → `TriplateSyntaxError` (compile),
`TriplateBindingError`, `TriplateTypeError`, `TriplateCardinalityError` (render).

## Build & test

```bash
mvn test          # unit tests + the shared conformance suite
```

The conformance tests run the same `triplate.dev/spec/conformance/*.json`
fixtures as the TypeScript and Python ports and assert byte-identical output.

See [triplate.dev](https://triplate.dev) for the full guide and specification.
