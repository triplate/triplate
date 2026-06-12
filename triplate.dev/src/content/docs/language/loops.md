---
title: Loops & conditionals
description: "{% for %} iterates declared arrays; {% if %} consumes optional params."
---

## `{% for %}`

```sparql
{% for c in classes join "UNION" %}
  { ?s a ${c} }
{% endfor %}
```

`{% for <item> in <source> [join "<text>" [explicit]] %}` … `{% endfor %}`.

- `<source>` is a declared array parameter, or a path into a loop variable
  (`g.members`) for nested loops. Cardinality lives in the header.
- **Join** is emitted between iterations only, with boundary whitespace merged.
  Padded by default (`join "UNION"` → `… } UNION { …`); `explicit` is verbatim
  (`join ", " explicit` → `"a", "b"`).
- A directive alone on its line is removed (block trimming); an inline tag
  renders in place: `VALUES { {% for c in classes %}${c} {% endfor %} }`.

## `{% if %}`

```sparql
{% if nameFilter %}
  FILTER(CONTAINS(?name, ${nameFilter}))
{% endif %}
{% if limit %}LIMIT ${limit}{% else %}LIMIT 100{% endif %}
```

Conditions are **type-directed**: a `bool` tests its value; anything `optional`
tests presence; an array tests non-empty; a required scalar is a compile error
(always true). `not` negates; there are no comparison operators. `{% if %}` is
what makes `optional` parameters consumable.
