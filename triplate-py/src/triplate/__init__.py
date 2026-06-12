"""Triplate — a templating engine for RDF query & data languages.

Templates declare their inputs in a ``---`` frontmatter header and use ``${ }``
substitutions, ``$"…"`` / ``$<…>`` constructs, and ``{% for %}`` / ``{% if %}``
directives. Values are validated and escaped per their declared RDF type, so
rendered queries are injection-safe by construction. See https://triplate.dev.

    from triplate import compile, render

    tmpl = compile(template_string)            # parse once
    sparql = tmpl.render(classes=[...])        # render many
    render(template_string, classes=[...])     # one-shot convenience
"""

from ._examples import example_set_to_context, extract_prefixes
from ._parser import parse as _parse
from ._registry import register_type
from ._renderer import render as _render
from .errors import (
    TriplateBindingError,
    TriplateCardinalityError,
    TriplateError,
    TriplateSyntaxError,
    TriplateTypeError,
)

__all__ = [
    "CompiledTemplate",
    "compile",
    "render",
    "register_type",
    "TriplateError",
    "TriplateSyntaxError",
    "TriplateBindingError",
    "TriplateTypeError",
    "TriplateCardinalityError",
]

__version__ = "0.3.0"


class CompiledTemplate:
    """A parsed template that can be rendered many times."""

    def __init__(self, data, source):
        self._data = data
        self._source = source

    @property
    def schema(self):
        return self._data.schema

    @property
    def examples(self):
        return self._data.examples

    def render(self, context=None, **kwargs):
        ctx = dict(context) if context else {}
        ctx.update(kwargs)
        return _render(self._data, ctx)

    def preview_example(self, example_id):
        for e in self._data.examples:
            if e.id == example_id:
                ctx = example_set_to_context(e, self._data.schema, extract_prefixes(self._source))
                return _render(self._data, ctx)
        raise TriplateError(f"no example set with id: {example_id}")


def compile(template):
    """Parses a template once; the result can be rendered many times."""
    return CompiledTemplate(_parse(template), template)


def render(template, context=None, **kwargs):
    """One-shot convenience: compile and render in a single call."""
    return compile(template).render(context, **kwargs)
