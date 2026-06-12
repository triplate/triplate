"""Example-set preview support. Mirrors examples.ts."""

import re

from ._ast import ExBoolean, ExIri, ExList, ExNumber, ExPname, ExRecord, ExString
from .errors import TriplateError

_PREFIX_RE = re.compile(r"(?:PREFIX|@prefix)\s+([A-Za-z_][\w.-]*)?\s*:\s*<([^>]*)>", re.IGNORECASE)


def extract_prefixes(template):
    out = {}
    for m in _PREFIX_RE.finditer(template):
        out[m.group(1) or ""] = m.group(2)
    return out


def example_set_to_context(example_set, schema, prefixes):
    ctx = {}
    for name, ev in example_set.bindings.items():
        type_ = schema.by_name.get(name)
        if type_ is None:
            raise TriplateError(f'example "{example_set.id}" binds unknown parameter: {name}')
        ctx[name] = _convert(ev, type_, prefixes, example_set.id)
    return ctx


def _convert(ev, type_, prefixes, eid):
    from ._ast import TypeExpr

    if type_.array:
        if not isinstance(ev, ExList):
            raise TriplateError(f'example "{eid}": expected a list')
        elem = TypeExpr(type_.base, False, False, None, None)
        return [_convert(it, elem, prefixes, eid) for it in ev.items]
    if type_.base["kind"] == "record":
        if not isinstance(ev, ExRecord):
            raise TriplateError(f'example "{eid}": expected a record')
        out = {}
        for f, ft in type_.base["fields"].items():
            if f in ev.fields:
                out[f] = _convert(ev.fields[f], ft, prefixes, eid)
        return out
    scalar = type_.base["kind"]
    if isinstance(ev, ExIri):
        return ev.value
    if isinstance(ev, ExPname):
        if scalar == "pname":
            return f"{ev.prefix}:{ev.local}"
        ns = prefixes.get(ev.prefix)
        if ns is None:
            raise TriplateError(f'example "{eid}": unknown prefix \'{ev.prefix}:\'')
        return ns + ev.local
    if isinstance(ev, ExString):
        return ev.value
    if isinstance(ev, ExNumber):
        return ev.value
    if isinstance(ev, ExBoolean):
        return ev.value
    raise TriplateError(f'example "{eid}": value does not match declared type')
