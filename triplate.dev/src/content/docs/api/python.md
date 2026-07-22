---
title: Python API
description: The triplate PyPI package — compile, render, CompiledTemplate, custom types and errors.
---

The [`triplate`](https://pypi.org/project/triplate/) package requires Python
3.10+ and has no required dependencies. See [Installation](../../../installation/)
to add it (and the optional `rdflib` extra for the `term` type), and the
[API overview](../) for the cross-language model.

```python
from triplate import compile, render, is_template, register_type
```

## Functions

### `compile(template)`

```python
def compile(template: str) -> CompiledTemplate
```

Parses `template` once. Raises `TriplateSyntaxError` if the template is malformed
(including any reference to an undeclared parameter). Returns a reusable
[`CompiledTemplate`](#compiledtemplate).

### `render(template, context=None, **kwargs)`

```python
def render(template: str, context: dict | None = None, **kwargs) -> str
```

One-shot convenience equivalent to `compile(template).render(context, **kwargs)`.
Values may be passed as a mapping, as keyword arguments, or both.

### `is_template(text)`

```python
def is_template(text: str) -> bool
```

Returns `True` if `text` opens with a `---` frontmatter header. A cheap,
non-throwing detector — unlike `compile`, it never parses or raises.

## `CompiledTemplate`

```python
class CompiledTemplate:
    schema: Schema                              # property
    examples: list[ExampleSet]                  # property
    def render(self, context=None, **kwargs) -> str
    def preview_example(self, example_id: str) -> str
    def context_from_strings(self, inputs: dict[str, str | None]) -> dict
    def frontmatter_prefixes(self) -> set[str]
```

| Member | Description |
|---|---|
| `schema` | The declared parameter schema. |
| `examples` | The named `example` blocks from the frontmatter; each `ExampleSet` carries its source `line`/`column`. |
| `render(context=None, **kwargs)` | Renders against a mapping and/or kwargs. Raises a render-time error on a missing, unknown or mistyped value. Returns the rendered string. |
| `preview_example(example_id)` | Renders using the named example set (development/preview). Raises if no such set exists. |
| `context_from_strings(inputs)` | Builds a validated context from raw string inputs. See below. |
| `frontmatter_prefixes()` | The prefixes referenced in the frontmatter. See below. |

### `context_from_strings(inputs)`

Coerces raw string inputs (CLI args, editor prompts) to their declared scalar
types and validates the result against the schema:

- An absent or blank input is **omitted**, so optional params stay absent.
- An array param is **split on commas** (items trimmed, blanks dropped) and
  coerced element-wise.
- A record param **cannot be expressed as a string and is skipped** — supply
  records via an example block instead.
- `int` / `decimal` / `double` / `bool` are parsed strictly; an uncoercible value
  raises `TriplateTypeError`. Structural problems (missing required, cardinality)
  raise the usual binding/cardinality errors.

```python
tmpl = compile("---\nparams { ids: int[], active: bool }\n---\n…")
tmpl.context_from_strings({"ids": "1, 2, 3", "active": "true"})
# → {"ids": [1, 2, 3], "active": True}
```

### `frontmatter_prefixes()`

Returns the `set[str]` of namespace prefixes referenced in the frontmatter — in
`example` binding values (`prefix:local`), in literal datatypes on those values
(`"x"^^p:t`), and in `literal(p:t)` parameter types. Because tools blank the
frontmatter before tokenizing the body, these usages are invisible to a body
token stream; a linter can use this set to avoid flagging a body `PREFIX`
declaration as unused. A full `<iri>` contributes no prefix; the empty string
denotes the default prefix.

```python
tmpl = compile('---\nparams { t: iri }\nexample d "" { t: schema:Person }\n---\n${t}')
tmpl.frontmatter_prefixes()   # → {"schema"}
```

```python
tmpl = compile(template_string)
tmpl.schema.params                              # declared inputs
tmpl.render(service="http://dbpedia.org/sparql",
            classes=["http://example.org/Person"], limit=10)
tmpl.render({"service": "…", "classes": [...]}) # or a mapping
tmpl.preview_example("demo")                    # render a named example block
```

## Accepted values

Each declared header type accepts:

| Header type | Python value |
|---|---|
| `iri`, `pname`, `string`, `raw`, `literal(<dt>)` | `str` |
| `int` | `int` |
| `decimal`, `double` | `float` (or `int`) |
| `bool` | `bool` |
| `date`, `dateTime`, `time` | `datetime.date` / `datetime` / `time`, or an ISO `str` |
| `term` | an `rdflib` term (requires the `rdflib` extra) |
| `T[]` | a `list` of the element type |
| `{ … }` record | a `dict` |

## Custom types

```python
def register_type(name: str, serializer: Callable[[Any, Pos], str]) -> None
```

Register a serializer, then use `name` as a header type (`params { id: name }`).
The serializer is called as `serializer(value, pos)` where `pos` has `.line` and
`.column`; it must validate `value` and return the exact text to emit — it is
fully responsible for injection safety.

```python
from triplate import register_type, TriplateTypeError
import re

def uuidref(value, pos):
    if not re.fullmatch(r"[0-9a-f-]{36}", str(value)):
        raise TriplateTypeError("not a UUID", pos.line, pos.column)
    return f"<urn:uuid:{value}>"

register_type("uuidref", uuidref)
```

## Errors

```python
class TriplateError(Exception):
    line: int | None
    column: int | None

class TriplateSyntaxError(TriplateError): ...       # compile time
class TriplateBindingError(TriplateError): ...      # render: missing/unknown/absent
class TriplateTypeError(TriplateError): ...         # render: failed validation
class TriplateCardinalityError(TriplateError): ...  # render: min/max violated
```

All five names are exported from the top-level `triplate` package.
