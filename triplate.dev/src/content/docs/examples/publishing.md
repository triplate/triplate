---
title: Publishing
description: Generate Markdown or HTML documents from RDF data.
---

Triplate's only special tokens are `${`, `$"`, `$<` and `{%` — everything else
passes through verbatim. So the body of a template doesn't have to be an RDF
language at all: the same header, loops and conditionals generate **Markdown or
HTML**, turning query results or graph data into human-readable pages.

Publishing templates emit prose, not RDF terms — a `string` parameter would
render as a quoted, escaped literal (`"Alice"`), which is wrong in the middle
of a sentence. The answer is the same one Triplate gives for queries: **the
type owns the escaping**. The examples below assume the host application has
[registered](/reference/api/typescript/#custom-types) two small custom types:

- `text` — accepts plain strings and inserts them as-is, for Markdown output;
- `htmltext` — accepts plain strings and escapes `&`, `<`, `>`, so a value can
  never inject markup into HTML output.

The type name in the frontmatter stays the single, auditable place that says
how a value is validated and escaped — no untyped escape hatches.

## A Markdown catalog page

Loop over records — for example, one per `SELECT` result row — to render a
document. Directives alone on their line are trimmed, so the output stays
clean.

```
---
params {
  title:    text
  datasets: { name: text, description: text, homepage: iri }[]
}
---
# ${title}

{% for d in datasets %}
## ${d.name}

${d.description}

[Homepage](${d.homepage})

{% endfor %}
```

```markdown
# Dataset catalog

## People

Curated FOAF descriptions of project members.

[Homepage](<https://example.org/data/people>)

## Organizations

Legal entities referenced by the people dataset.

[Homepage](<https://example.org/data/orgs>)

```

Note the link target: `homepage` is a plain `iri` parameter. A standalone
`${…}` of type `iri` serializes as `<…>` — validated as an absolute IRI — and
Markdown accepts exactly that angle-bracketed form as a link destination, so
URLs get a real RDF type instead of passing through as text.

## A W3C-style ontology documentation page

The classic publishing task for RDF: render an ontology as a specification
page in the style of W3C vocabulary documentation — a header with title,
version and authors, an abstract, and one documented entry per class, property
and individual, each with its IRI, prose description and cross-linked
relations. In a real pipeline the context comes from a handful of
[query templates](../query/) run against the ontology graph (one for the
header, one per section); here it is passed in directly.

```
---
params {
  ontology: {
    title:    htmltext
    iri:      htmltext
    version:  htmltext
    modified: htmltext
    authors:  htmltext[] min 1
    abstract: htmltext
  }
  classes:     { id: htmltext, label: htmltext, iri: htmltext,
                 comment: htmltext, subClassOf: htmltext optional }[]
  properties:  { id: htmltext, label: htmltext, iri: htmltext,
                 comment: htmltext, domain: htmltext optional,
                 range: htmltext optional }[]
  individuals: { id: htmltext, label: htmltext, iri: htmltext,
                 comment: htmltext, type: htmltext }[] optional
}
---
<article>
<header>
  <h1>${ontology.title}</h1>
  <dl>
    <dt>IRI</dt>
    <dd><code>${ontology.iri}</code></dd>
    <dt>Version</dt>
    <dd>${ontology.version}, ${ontology.modified}</dd>
    <dt>Authors</dt>
    <dd>${...ontology.authors join ", " explicit}</dd>
  </dl>
</header>

<section id="abstract">
  <h2>Abstract</h2>
  <p>${ontology.abstract}</p>
</section>

<section id="classes">
  <h2>Classes</h2>
{% for c in classes %}
  <section id=$"${c.id}">
    <h3>${c.label}</h3>
    <p><code>${c.iri}</code></p>
    <p>${c.comment}</p>
{% if c.subClassOf %}
    <p>Subclass of <a href=$"#${c.subClassOf}">${c.subClassOf}</a>.</p>
{% endif %}
  </section>
{% endfor %}
</section>

<section id="properties">
  <h2>Properties</h2>
{% for p in properties %}
  <section id=$"${p.id}">
    <h3>${p.label}</h3>
    <p><code>${p.iri}</code></p>
    <p>${p.comment}</p>
{% if p.domain %}
    <p>Domain: <a href=$"#${p.domain}">${p.domain}</a></p>
{% endif %}
{% if p.range %}
    <p>Range: <a href=$"#${p.range}">${p.range}</a></p>
{% endif %}
  </section>
{% endfor %}
</section>

{% if individuals %}
<section id="individuals">
  <h2>Individuals</h2>
{% for i in individuals %}
  <section id=$"${i.id}">
    <h3>${i.label}</h3>
    <p><code>${i.iri}</code></p>
    <p>Type: <a href=$"#${i.type}">${i.type}</a></p>
    <p>${i.comment}</p>
  </section>
{% endfor %}
</section>
{% endif %}
</article>
```

Rendered with a small people & organizations vocabulary:

```html
<article>
<header>
  <h1>The People &amp; Organizations Ontology</h1>
  <dl>
    <dt>IRI</dt>
    <dd><code>http://example.org/ns/people#</code></dd>
    <dt>Version</dt>
    <dd>0.9.0, 2026-07-22</dd>
    <dt>Authors</dt>
    <dd>Ada Lovelace, Alan Turing</dd>
  </dl>
</header>

<section id="abstract">
  <h2>Abstract</h2>
  <p>This vocabulary describes people, the organizations they belong to, and
  the agents common to both. It is deliberately small and follows the
  documentation conventions of W3C vocabularies.</p>
</section>

<section id="classes">
  <h2>Classes</h2>
  <section id="Agent">
    <h3>Agent</h3>
    <p><code>http://example.org/ns/people#Agent</code></p>
    <p>Anything that can act: a person or an organization.</p>
  </section>
  <section id="Person">
    <h3>Person</h3>
    <p><code>http://example.org/ns/people#Person</code></p>
    <p>A human being, described by name rather than role.</p>
    <p>Subclass of <a href="#Agent">Agent</a>.</p>
  </section>
  <section id="Organization">
    <h3>Organization</h3>
    <p><code>http://example.org/ns/people#Organization</code></p>
    <p>A legal or social body such as a company or a club.</p>
    <p>Subclass of <a href="#Agent">Agent</a>.</p>
  </section>
</section>

<section id="properties">
  <h2>Properties</h2>
  <section id="memberOf">
    <h3>member of</h3>
    <p><code>http://example.org/ns/people#memberOf</code></p>
    <p>Relates a person to an organization they belong to.</p>
    <p>Domain: <a href="#Person">Person</a></p>
    <p>Range: <a href="#Organization">Organization</a></p>
  </section>
</section>

<section id="individuals">
  <h2>Individuals</h2>
  <section id="acme">
    <h3>ACME Inc.</h3>
    <p><code>http://example.org/ns/people#acme</code></p>
    <p>Type: <a href="#Organization">Organization</a></p>
    <p>A fictitious organization used across the examples.</p>
  </section>
</section>
</article>
```

Three details worth noting:

- **Static vs constructed attributes.** Plain `"…"` strings are
  [inert](/language/minting/#inert-regions), so a static attribute like
  `id="classes"` passes through untouched — but that also means
  `id="${c.id}"` would emit the `${…}` literally. A *constructed* attribute
  value is exactly what [`$"…"`](/language/minting/) is for: `id=$"${c.id}"`
  renders `id="Person"`, quotes included and content escaped — including a
  `#` inside a string (`href=$"#${c.id}"`).
- **Escaping shows up where it matters.** The `&` in the title arrives as
  `&amp;` — `htmltext` values can never inject tags, the injection-safety
  story transposed to HTML.
- **Whole sections are conditional.** `individuals` is an optional array, so
  `{% if individuals %}` drops the entire section when the vocabulary declares
  none — the type-directed condition tests presence and non-emptiness.

## A query + publishing pipeline

The two use cases compose: render a [query template](../query/), run it, then
feed the result rows into a publishing template.

```ts
import { compile } from 'triplate';

const query = compile(await fs.readFile('classes.sparql.tpl', 'utf8'));
const page = compile(await fs.readFile('ontology-doc.html.tpl', 'utf8'));

const classes = await runSelect(endpoint, query.render({ graph: ontologyIri }));
await fs.writeFile('index.html', page.render({ ontology, classes, properties, individuals }));
```

Because both templates declare their inputs, each stage validates its context
up front — a missing binding fails the build instead of publishing a broken
page.
