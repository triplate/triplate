"""Type validators and serializers. Mirrors types/serializers.ts byte for byte."""

import datetime as _dt
import re

from .errors import TriplateTypeError

XSD = "http://www.w3.org/2001/XMLSchema#"

ABSOLUTE_IRI = re.compile(r'^[A-Za-z][A-Za-z0-9+.-]*:[^\u0000-\u0020<>"{}|^`\\]*$')
PNAME = re.compile(
    r"^(?:[A-Za-z_][A-Za-z0-9_-]*)?:(?:[A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_-])?)?$"
)
LANG_TAG = re.compile(r"^[A-Za-z]{1,8}(-[A-Za-z0-9]{1,8})*$")
DATE = re.compile(r"^-?\d{4,}-\d{2}-\d{2}(Z|[+-]\d{2}:\d{2})?$")
DATE_TIME = re.compile(
    r"^-?\d{4,}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?$"
)
TIME = re.compile(r"^\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?$")
BLANK_LABEL = re.compile(r"^[A-Za-z0-9_]+$")

# JavaScript's Number.MAX_SAFE_INT; identical bound in every implementation.
MAX_SAFE_INT = 2**53 - 1


def _fail(message, pos):
    raise TriplateTypeError(message, pos.line, pos.column)


def _describe(value):
    if isinstance(value, bool):
        return "bool"
    if isinstance(value, (list, tuple)):
        return "list"
    if value is None:
        return "null"
    if isinstance(value, str):
        return "string"
    if isinstance(value, (int, float)):
        return "number"
    return type(value).__name__


def require_string(value, what, pos):
    if not isinstance(value, str):
        _fail(f"{what} requires a string value, got {_describe(value)}", pos)
    return value


def escape_string(value):
    return (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    )


def validate_iri(value, pos):
    s = require_string(value, "iri", pos)
    if not ABSOLUTE_IRI.match(s):
        _fail(f"invalid absolute IRI: {s!r}", pos)
    return s


def serialize_iri(value, pos):
    return f"<{validate_iri(value, pos)}>"


def serialize_pname(value, pos):
    s = require_string(value, "pname", pos)
    if not PNAME.match(s):
        _fail(f"invalid prefixed name: {s!r}", pos)
    return s


def validate_lang_tag(value, pos):
    s = require_string(value, "language tag", pos)
    if not LANG_TAG.match(s):
        _fail(f"invalid language tag: {s!r}", pos)
    return s


def serialize_string(value, pos, lang=None):
    s = require_string(value, "string", pos)
    quoted = f'"{escape_string(s)}"'
    return f"{quoted}@{lang}" if lang else quoted


def serialize_int(value, pos):
    if isinstance(value, bool) or not isinstance(value, int):
        _fail(f"int requires an integral number, got {_describe(value)}", pos)
    if abs(value) > MAX_SAFE_INT:
        # Still serializable exactly in Python; mirrors the TS bigint path.
        return str(value)
    return str(value)


def serialize_decimal(value, pos):
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        _fail(f"decimal requires a finite number, got {_describe(value)}", pos)
    if isinstance(value, float):
        if value != value or value in (float("inf"), float("-inf")):
            _fail("decimal requires a finite number, got non-finite", pos)
        s = repr(value)
    else:
        s = str(value)
    if "e" in s or "E" in s:
        _fail(f"value out of range for decimal: {s}", pos)
    return s if "." in s else f"{s}.0"


def serialize_double(value, pos):
    """Canonical xsd:double scientific notation; same algorithm as the TS impl."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        _fail(f"double requires a number, got {_describe(value)}", pos)
    v = float(value)
    if v != v:
        return f'"NaN"^^<{XSD}double>'
    if v == float("inf"):
        return f'"INF"^^<{XSD}double>'
    if v == float("-inf"):
        return f'"-INF"^^<{XSD}double>'
    if v == 0:
        return "0.0E0"

    sign = "-" if v < 0 else ""
    a = abs(value)
    repr_s = str(a) if isinstance(value, int) else repr(abs(v))
    mantissa, exp = repr_s, 0
    m = re.search(r"[eE]", repr_s)
    if m:
        mantissa = repr_s[: m.start()]
        exp = int(repr_s[m.start() + 1 :])
    dot = mantissa.find(".")
    if dot >= 0:
        digits = mantissa[:dot] + mantissa[dot + 1 :]
        point_pos = dot
    else:
        digits = mantissa
        point_pos = len(mantissa)
    stripped = digits.lstrip("0")
    leading = len(digits) - len(stripped)
    digits = stripped.rstrip("0")
    exponent = point_pos - leading - 1 + exp
    fraction = digits[1:] or "0"
    return f"{sign}{digits[0]}.{fraction}E{exponent}"


def serialize_bool(value, pos):
    if not isinstance(value, bool):
        _fail(f"bool requires a bool, got {_describe(value)}", pos)
    return "true" if value else "false"


def _serialize_temporal(value, pos, name, pattern, types, to_lexical):
    if isinstance(value, types) and not isinstance(value, str):
        lexical = to_lexical(value)
    elif isinstance(value, str):
        if not pattern.match(value):
            _fail(f"invalid {name} value: {value!r}", pos)
        lexical = value
    else:
        _fail(f"{name} requires a date/time object or ISO string, got {_describe(value)}", pos)
    return f'"{lexical}"^^<{XSD}{name}>'


def serialize_date(value, pos):
    # datetime is a subclass of date; both are fine for xsd:date.
    return _serialize_temporal(
        value,
        pos,
        "date",
        DATE,
        (_dt.date,),
        lambda v: (v.date() if isinstance(v, _dt.datetime) else v).isoformat(),
    )


def serialize_date_time(value, pos):
    return _serialize_temporal(
        value, pos, "dateTime", DATE_TIME, (_dt.datetime,), lambda v: v.isoformat()
    )


def serialize_time(value, pos):
    return _serialize_temporal(
        value, pos, "time", TIME, (_dt.time,), lambda v: v.isoformat()
    )


def serialize_typed_literal(value, datatype, pos):
    s = require_string(value, f"literal({datatype})", pos)
    return f'"{escape_string(s)}"^^{datatype}'


def serialize_term(value, pos):
    """An rdflib term (URIRef, Literal or BNode)."""
    try:
        from rdflib import BNode, Literal, URIRef
    except ImportError:  # pragma: no cover
        _fail("the term type requires rdflib (pip install triplate[rdflib])", pos)
    if isinstance(value, URIRef):
        return serialize_iri(str(value), pos)
    if isinstance(value, BNode):
        label = str(value)
        if not BLANK_LABEL.match(label):
            _fail(f"invalid blank node label: {label!r}", pos)
        return f"_:{label}"
    if isinstance(value, Literal):
        quoted = f'"{escape_string(str(value))}"'
        if value.language:
            return f"{quoted}@{validate_lang_tag(value.language, pos)}"
        if value.datatype and str(value.datatype) != f"{XSD}string":
            return f"{quoted}^^{serialize_iri(str(value.datatype), pos)}"
        return quoted
    _fail(f"term requires an rdflib term, got {_describe(value)}", pos)


def serialize_raw(value, pos):
    return require_string(value, "raw", pos)


def percent_encode(s):
    """Percent-encode to the IRI unreserved set `A-Za-z0-9-._~`; every other
    character becomes its UTF-8 bytes as uppercase %XX. Matches the TS impl."""
    from urllib.parse import quote

    return quote(s, safe="")


def validate_assembled_iri(s, pos):
    """Validate an assembled IRI-template result as an absolute IRI."""
    if not ABSOLUTE_IRI.match(s):
        _fail(f"IRI template did not produce a valid absolute IRI: {s!r}", pos)
    return s


def interpolation_lexical(value, pos):
    """Canonical lexical form for typeless interpolations inside %"…" strings."""
    if isinstance(value, str):
        return value
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    _fail(
        "interpolation requires a string, int or bool "
        f"(use an explicit type for other values), got {_describe(value)}",
        pos,
    )
