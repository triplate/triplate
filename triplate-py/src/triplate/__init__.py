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

import re

from ._ast import ExList, ExPname, ExRecord, ExString
from ._examples import example_set_to_context, extract_prefixes
from ._parser import parse as _parse
from ._registry import register_type
from ._renderer import _validate_context, render as _render
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
    "is_template",
    "register_type",
    "TriplateError",
    "TriplateSyntaxError",
    "TriplateBindingError",
    "TriplateTypeError",
    "TriplateCardinalityError",
]

__version__ = "0.3.0"

_HEADER_RE = re.compile(r"^---[ \t]*(\r?\n|$)")
_INT_RE = re.compile(r"^[+-]?\d+$")


def is_template(text):
    """Return True if `text` opens with a `---` frontmatter header."""
    return _HEADER_RE.match(text) is not None


def _coerce_scalar(kind, raw):
    """Coerce a raw string to a typed value for the given scalar kind."""
    if kind == "int":
        if not _INT_RE.match(raw):
            raise TriplateTypeError(f"invalid int: {raw!r}")
        return int(raw)
    if kind in ("decimal", "double"):
        try:
            n = float(raw)
        except ValueError:
            raise TriplateTypeError(f"invalid {kind}: {raw!r}") from None
        import math
        if not math.isfinite(n):
            raise TriplateTypeError(f"invalid {kind}: {raw!r}")
        return n
    if kind == "bool":
        if raw == "true":
            return True
        if raw == "false":
            return False
        raise TriplateTypeError(f'invalid bool: {raw!r} (expected "true" or "false")')
    # iri, pname, string, literal, term, raw, date, dateTime, time, custom → string
    return raw


def _add_datatype_prefix(dt, out):
    """Add the prefix of a datatype reference (`prefix:local`); `<iri>` forms add nothing."""
    if dt.startswith("<"):
        return
    i = dt.find(":")
    if i >= 0:
        out.add(dt[:i])


def _collect_value_prefixes(ev, out):
    if isinstance(ev, ExPname):
        out.add(ev.prefix)
    elif isinstance(ev, ExString):
        if ev.datatype:
            _add_datatype_prefix(ev.datatype, out)
    elif isinstance(ev, ExList):
        for it in ev.items:
            _collect_value_prefixes(it, out)
    elif isinstance(ev, ExRecord):
        for f in ev.fields.values():
            _collect_value_prefixes(f, out)
    # ExIri, ExNumber, ExBoolean → no prefix


def _collect_type_prefixes(type_, out):
    base = type_.base
    if base["kind"] == "record":
        for ft in base["fields"].values():
            _collect_type_prefixes(ft, out)
    elif base["kind"] == "literal":
        _add_datatype_prefix(base["datatype"], out)


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

    def context_from_strings(self, inputs):
        """Build a render context from raw string inputs, coercing each value to
        its declared scalar type and validating the result against the schema.

        An absent or blank input is omitted (optional params stay absent); an
        array param is split on commas (items trimmed, blanks dropped) and coerced
        element-wise; a record param cannot be expressed as a string and is
        skipped — supply records via an example block instead. Raises
        ``TriplateTypeError`` for uncoercible values and the usual binding /
        cardinality errors for structural problems.
        """
        context = {}
        for param in self._data.schema.params:
            base, array = param.type.base, param.type.array
            if base["kind"] == "record":
                continue
            raw = inputs.get(param.name)
            if raw is None or raw.strip() == "":
                continue
            if array:
                context[param.name] = [
                    _coerce_scalar(base["kind"], item)
                    for item in (s.strip() for s in raw.split(","))
                    if item
                ]
            else:
                context[param.name] = _coerce_scalar(base["kind"], raw)
        _validate_context(self._data.schema, context)
        return context

    def frontmatter_prefixes(self):
        """The namespace prefixes referenced in the frontmatter — in ``example``
        binding values (``prefix:local``), in literal datatypes on those values
        (``"x"^^p:t``), and in ``literal(p:t)`` parameter types.

        These usages are invisible to a token stream over the body (tools blank
        the frontmatter before tokenizing), so a linter can use this to avoid
        flagging a body ``PREFIX`` declaration as unused. Full ``<iri>`` values
        contribute nothing; the empty string denotes the default prefix.
        """
        out = set()
        for e in self._data.examples:
            for ev in e.bindings.values():
                _collect_value_prefixes(ev, out)
        for param in self._data.schema.params:
            _collect_type_prefixes(param.type, out)
        return out


def compile(template):
    """Parses a template once; the result can be rendered many times."""
    return CompiledTemplate(_parse(template), template)


def render(template, context=None, **kwargs):
    """One-shot convenience: compile and render in a single call."""
    return compile(template).render(context, **kwargs)
