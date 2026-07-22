---
title: Java API
description: The dev.triplate Maven artifact — Triplate, CompiledTemplate, TypeRegistry and errors.
---

The `dev.triplate:triplate` artifact requires Java 17+ and has no runtime
dependencies. See [Installation](../../../installation/) for Maven coordinates,
and the [API overview](../) for the cross-language model.

```java
import dev.triplate.Triplate;
import dev.triplate.CompiledTemplate;
```

## `Triplate`

The entry point — static factory and one-shot helpers.

```java
public static CompiledTemplate compile(String template)
public static String render(String template, Map<String, Object> context)
public static String render(String template)   // empty context
public static boolean isTemplate(String text)
```

`compile` parses once and throws `TriplateSyntaxError` on a malformed template
(including any reference to an undeclared parameter). The `render` overloads are
one-shot conveniences; the no-context form is valid only when every parameter is
optional. `isTemplate` returns `true` if `text` opens with a `---` frontmatter
header — a cheap, non-throwing detector that never parses or raises.

## `CompiledTemplate`

```java
public final class CompiledTemplate {
  public Schema schema();
  public List<ExampleSet> examples();
  public String render();
  public String render(Map<String, Object> context);
  public String previewExample(String id);
  public Map<String, Object> contextFromStrings(Map<String, String> inputs);
  public Set<String> frontmatterPrefixes();
}
```

| Member | Description |
|---|---|
| `schema()` | The declared parameter schema (`schema().params()`, `schema().byName()`). |
| `examples()` | The named `example` blocks from the frontmatter; each `ExampleSet` carries its source `line()`/`column()`. |
| `render(context)` | Renders against `context`. Throws a render-time error on a missing, unknown or mistyped value. Returns the rendered string. |
| `render()` | Renders with an empty context (all-optional templates only). |
| `previewExample(id)` | Renders using the named example set `id` (development/preview). Throws if no such set exists. |
| `contextFromStrings(inputs)` | Builds a validated context from raw string inputs. See below. |
| `frontmatterPrefixes()` | The prefixes referenced in the frontmatter. See below. |

### `contextFromStrings(inputs)`

Coerces raw string inputs (CLI args, editor prompts) to their declared scalar
types and validates the result against the schema:

- An absent or blank input is **omitted**, so optional params stay absent.
- An array param is **split on commas** (items trimmed, blanks dropped) and
  coerced element-wise.
- A record param **cannot be expressed as a string and is skipped** — supply
  records via an example block instead.
- `int` / `decimal` / `double` / `bool` are parsed strictly; an uncoercible value
  throws `TriplateTypeError`. Structural problems (missing required, cardinality)
  throw the usual binding/cardinality errors.

```java
CompiledTemplate tmpl = Triplate.compile("---\nparams { ids: int[], active: bool }\n---\n…");
tmpl.contextFromStrings(Map.of("ids", "1, 2, 3", "active", "true"));
// → { ids=[1, 2, 3], active=true }
```

### `frontmatterPrefixes()`

Returns the `Set<String>` of namespace prefixes referenced in the frontmatter —
in `example` binding values (`prefix:local`), in literal datatypes on those
values (`"x"^^p:t`), and in `literal(p:t)` parameter types. Because tools blank
the frontmatter before tokenizing the body, these usages are invisible to a body
token stream; a linter can use this set to avoid flagging a body `PREFIX`
declaration as unused. A full `<iri>` contributes no prefix; the empty string
denotes the default prefix.

```java
CompiledTemplate tmpl = Triplate.compile("---\nparams { t: iri }\nexample d \"\" { t: schema:Person }\n---\n${t}");
tmpl.frontmatterPrefixes();   // → [schema]
```

```java
CompiledTemplate tmpl = Triplate.compile(templateString);
tmpl.schema().params();                  // declared inputs
tmpl.render(Map.of(
    "service", "http://dbpedia.org/sparql",
    "classes", List.of("http://example.org/Person"),
    "limit", 10));
tmpl.previewExample("demo");             // render a named example block
```

## Accepted values

The context is a `Map<String, Object>`. Each declared header type accepts:

| Header type | Java value |
|---|---|
| `iri`, `pname`, `string`, `raw`, `literal(<dt>)` | `String` |
| `int` | `Long`, `Integer`, `Short`, `Byte` or `BigInteger` |
| `decimal`, `double` | `Double` or `BigDecimal` |
| `bool` | `Boolean` |
| `date`, `dateTime`, `time` | a `java.time` value (`LocalDate`, `LocalDateTime`, …) or an ISO `String` |
| `term` | a `dev.triplate.Term` |
| `T[]` | a `List` of the element type |
| `{ … }` record | a `Map<String, Object>` |

```java
public interface Term {
  String termType();              // "NamedNode", "BlankNode", or "Literal"
  String value();                 // IRI, blank-node label, or lexical form
  default String language();      // null or language tag (Literal)
  default String datatypeIri();   // null or datatype IRI (Literal)
}
```

## Custom types

```java
public final class TypeRegistry {
  @FunctionalInterface
  public interface CustomSerializer {
    String serialize(Object value, Integer line, Integer column);
  }
  public static void registerType(String name, CustomSerializer serializer);
}
```

Register a serializer, then use `name` as a header type (`params { id: name }`).
The serializer must validate `value` and return the exact text to emit — it is
fully responsible for injection safety.

```java
import dev.triplate.TypeRegistry;
import dev.triplate.TriplateTypeError;

TypeRegistry.registerType("uuidref", (value, line, column) -> {
  if (!value.toString().matches("[0-9a-f-]{36}")) {
    throw new TriplateTypeError("not a UUID", line, column);
  }
  return "<urn:uuid:" + value + ">";
});
```

## Errors

All extend `TriplateError`, an unchecked `RuntimeException` carrying optional
1-based `getLine()` / `getColumn()`.

```
TriplateError
├── TriplateSyntaxError        compile time — malformed template
├── TriplateBindingError       render — missing / unknown / absent value
├── TriplateTypeError          render — value fails its declared type
└── TriplateCardinalityError   render — array min/max violated
```
