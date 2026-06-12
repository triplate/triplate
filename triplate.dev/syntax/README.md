# Triplate syntax highlighting

[`triplate.injection.tmLanguage.json`](triplate.injection.tmLanguage.json) is a
TextMate **injection grammar**. It layers Triplate's constructs — `${ … }`
values, `$"…"` strings, `$<…>` IRI templates, and `{% … %}` directives
(`params`, `examples`, `for`/`endfor`, `if`/`elif`/`else`/`endif`) — *on top of*
an existing host grammar, so a template gets normal SPARQL highlighting **plus**
Triplate highlighting.

It injects into `source.sparql` and, matching the language, stays out of `#`
comments and string literals. A bare `$name` (a SPARQL variable) is left alone.
This format is understood by VS Code, Sublime Text, and Shiki — so it also
drives the code blocks on [triplate.dev](https://triplate.dev).

## Key scopes

| Construct | Scope |
|-----------|-------|
| `${`, `$"`, `$<` openers | `punctuation.definition.template.triplate` |
| `${ path }` variable | `variable.other.triplate` |
| `{% for/if/… %}`, `params`/`example` | `keyword.control.triplate` |
| `in` `join` `explicit` `not` `optional` `min` `max` | `keyword.other.triplate` |
| type names (`iri`, `string`, …) | `support.type.triplate` |
| `$"…"` literal | `string.quoted.double.interpolated.triplate` |
| `$<…>` template | `string.other.iri.triplate` |

## VS Code

Ship it from an extension that depends on a SPARQL grammar:

```json
{
  "contributes": {
    "grammars": [
      { "scopeName": "triplate.injection", "path": "./triplate.injection.tmLanguage.json", "injectTo": ["source.sparql"] }
    ]
  }
}
```

## Shiki / Astro / Starlight

Shiki keys injection off `injectTo` (the file's `injectionSelector` is what
VS Code / Sublime read), so register the grammar with `injectTo: ['source.sparql']`.
This is how [triplate.dev](https://triplate.dev) wires it — see
`triplate.dev/astro.config.mjs`.

## Testing

```bash
npm run test:syntax --workspace triplate.dev
```

`test/grammar.test.mjs` tokenizes Triplate snippets with `vscode-textmate` +
`vscode-oniguruma` (the engine VS Code and Shiki use), injecting over a minimal
`source.sparql` stub, and asserts the expected scopes — including that comments,
strings, and bare `$name` variables are left untouched. See
[`sample.triplate`](sample.triplate) for a representative template.
