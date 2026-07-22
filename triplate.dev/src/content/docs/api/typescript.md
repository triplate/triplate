---
title: TypeScript API
description: The triplate npm package — compile, render, CompiledTemplate, custom types and errors.
---

The [`triplate`](https://www.npmjs.com/package/triplate) package ships as ES
modules with bundled type definitions and has no dependencies. See
[Installation](../../../installation/) to add it, and the
[API overview](../) for the cross-language model.

```ts
import { compile, render, isTemplate, registerType } from 'triplate';
```

## Functions

### `compile(template)`

```ts
function compile(template: string): CompiledTemplate
```

Parses `template` once. Throws `TriplateSyntaxError` if the template is malformed
(including any reference to an undeclared parameter). Returns a reusable
[`CompiledTemplate`](#compiledtemplate).

### `render(template, context?)`

```ts
function render(template: string, context?: Context): string
```

One-shot convenience equivalent to `compile(template).render(context)`. `context`
defaults to `{}`.

### `isTemplate(text)`

```ts
function isTemplate(text: string): boolean
```

Returns `true` if `text` opens with a `---` frontmatter header. A cheap,
non-throwing detector — unlike `compile`, it never parses or raises.

## `CompiledTemplate`

```ts
class CompiledTemplate {
  get schema(): Schema;
  get examples(): ExampleSet[];
  render(context?: Context): string;
  previewExample(id: string): string;
  contextFromStrings(inputs: Record<string, string | undefined>): Context;
  frontmatterPrefixes(): Set<string>;
}
```

| Member | Description |
|---|---|
| `schema` | The declared parameter schema (`schema.params`, `schema.byName`). |
| `examples` | The named `example` blocks from the frontmatter; each `ExampleSet` carries its source `line`/`column`. |
| `render(context?)` | Renders against `context` (default `{}`). Throws a render-time error on a missing, unknown or mistyped value. Returns the rendered string. |
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

```ts
const tmpl = compile('---\nparams { ids: int[], active: bool }\n---\n…');
tmpl.contextFromStrings({ ids: '1, 2, 3', active: 'true' });
// → { ids: [1, 2, 3], active: true }
```

### `frontmatterPrefixes()`

Returns the `Set<string>` of namespace prefixes referenced in the frontmatter —
in `example` binding values (`prefix:local`), in literal datatypes on those
values (`"x"^^p:t`), and in `literal(p:t)` parameter types. Because tools blank
the frontmatter before tokenizing the body, these usages are invisible to a body
token stream; a linter can use this set to avoid flagging a body `PREFIX`
declaration as unused. A full `<iri>` contributes no prefix; the empty string
denotes the default prefix.

```ts
const tmpl = compile('---\nparams { t: iri }\nexample d "" { t: schema:Person }\n---\n${t}');
tmpl.frontmatterPrefixes();   // → Set { 'schema' }
```

```ts
const tmpl = compile(templateString);
tmpl.schema.params;                  // declared inputs
tmpl.render({ service: 'http://dbpedia.org/sparql', classes: ['http://example.org/Person'], limit: 10 });
tmpl.previewExample('demo');         // render a named example block
```

## Accepted values

The `Context` is `Record<string, TriplateValue>`. Each declared header type
accepts:

| Header type | TypeScript value |
|---|---|
| `iri`, `pname`, `string`, `raw`, `literal(<dt>)` | `string` |
| `int` | `number` (safe integer) or `bigint` |
| `decimal`, `double` | `number` |
| `bool` | `boolean` |
| `date`, `dateTime`, `time` | `Date` or an ISO `string` |
| `term` | an RDF/JS-style `Term` (`{ termType, value, language?, datatype? }`) |
| `T[]` | an array of the element type |
| `{ … }` record | a plain object |

```ts
interface Term {
  termType: string;
  value: string;
  language?: string;
  datatype?: { value: string };
}
```

## Custom types

```ts
function registerType(name: string, serializer: CustomSerializer): void
type CustomSerializer = (value: unknown, pos: Pos) => string
```

Register a serializer, then use `name` as a header type (`params { id: name }`).
The serializer must validate `value` and return the exact text to emit — it is
fully responsible for injection safety. `pos` carries `line`/`column` for error
reporting.

```ts
import { registerType } from 'triplate';

registerType('uuidref', (value, pos) => {
  if (!/^[0-9a-f-]{36}$/.test(String(value))) {
    throw new TriplateTypeError('not a UUID', pos.line, pos.column);
  }
  return `<urn:uuid:${value}>`;
});
```

## Errors

```ts
class TriplateError extends Error {
  readonly line?: number;
  readonly column?: number;
}
class TriplateSyntaxError      extends TriplateError {}  // compile time
class TriplateBindingError     extends TriplateError {}  // render: missing/unknown/absent
class TriplateTypeError        extends TriplateError {}  // render: failed validation
class TriplateCardinalityError extends TriplateError {}  // render: min/max violated
```

## Exported types

`Schema`, `ParamDecl`, `TypeExpr`, `ExampleSet`, `Term`, `TriplateValue`,
`Context`, `CustomSerializer` are all exported for typing introspection code.
