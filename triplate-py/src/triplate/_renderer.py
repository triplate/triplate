"""AST renderer + context validation. Mirrors renderer.ts."""

import re

from ._ast import (
    ForNode,
    IfNode,
    InterpNode,
    IriNode,
    LangStatic,
    PartHole,
    TextNode,
    TypeExpr,
    ValueNode,
)
from ._registry import get_custom_type
from ._serializers import (
    escape_string,
    percent_encode,
    require_string,
    serialize_bool,
    serialize_date,
    serialize_date_time,
    serialize_decimal,
    serialize_double,
    serialize_int,
    serialize_iri,
    serialize_pname,
    serialize_raw,
    serialize_string,
    serialize_term,
    serialize_time,
    serialize_typed_literal,
    validate_assembled_iri,
    validate_iri,
    validate_lang_tag,
)
from .errors import TriplateBindingError, TriplateCardinalityError, TriplateTypeError

_TRAILING_WS = re.compile(r"\s+$")
_LEADING_WS = re.compile(r"^\s+")


def render(data, context):
    _validate_context(data.schema, context)
    env = {"schema": data.schema, "context": context, "loop": []}
    return _render(data.body, env)


# ---- context validation --------------------------------------------------

def _validate_context(schema, context):
    for key in context:
        if key not in schema.by_name:
            raise TriplateBindingError(f"unknown parameter: {key}")
    for decl in schema.params:
        present = decl.name in context
        if not present:
            if not decl.type.optional:
                raise TriplateBindingError(f"missing required parameter: {decl.name}")
            continue
        _validate_value(decl.type, context[decl.name], decl.name)


def _validate_value(t, value, label):
    if t.array:
        if not isinstance(value, (list, tuple)):
            raise TriplateTypeError(f"{label} must be a list")
        if t.min is not None and len(value) < t.min:
            raise TriplateCardinalityError(f"{label} requires at least {t.min} item(s), got {len(value)}")
        if t.max is not None and len(value) > t.max:
            raise TriplateCardinalityError(f"{label} allows at most {t.max} item(s), got {len(value)}")
        elem = TypeExpr(t.base, False, False, None, None)
        for i, el in enumerate(value):
            _validate_value(elem, el, f"{label}[{i}]")
    elif t.base["kind"] == "record":
        if not isinstance(value, dict):
            raise TriplateTypeError(f"{label} must be an object")
        for key in value:
            if key not in t.base["fields"]:
                raise TriplateBindingError(f"unknown field: {label}.{key}")
        for fname, ftype in t.base["fields"].items():
            if fname not in value:
                if not ftype.optional:
                    raise TriplateBindingError(f"missing field: {label}.{fname}")
                continue
            _validate_value(ftype, value[fname], f"{label}.{fname}")
    elif isinstance(value, (list, tuple)):
        raise TriplateTypeError(f"{label} must be a scalar, got a list")


# ---- resolution ----------------------------------------------------------

def _resolve(env, path, pos, must_exist):
    head = path[0]
    value = None
    type_ = None
    present = True
    found = False
    for scope in reversed(env["loop"]):
        if head in scope:
            value, type_ = scope[head]
            found = True
            break
    if not found:
        type_ = env["schema"].by_name.get(head)
        if type_ is None:
            raise TriplateBindingError(f"no binding for variable: {head}", pos.line, pos.column)
        present = head in env["context"]
        value = env["context"].get(head)
    for i in range(1, len(path)):
        field = path[i]
        if type_.base["kind"] != "record":
            raise TriplateTypeError(f"{'.'.join(path[:i])} is not an object", pos.line, pos.column)
        ft = type_.base["fields"].get(field)
        if ft is None:
            raise TriplateBindingError(f"unknown field: {'.'.join(path[:i + 1])}", pos.line, pos.column)
        if present and isinstance(value, dict) and field in value:
            value = value[field]
        else:
            present = False
            value = None
        type_ = ft
    if must_exist and not present:
        raise TriplateBindingError(
            f"no value for {'.'.join(path)} (an optional value? guard it with {{% if %}})",
            pos.line,
            pos.column,
        )
    return value, type_, present


def _scalar_of(t, path, pos):
    if t.array:
        raise TriplateTypeError(f"{'.'.join(path)} is a list; loop over it with {{% for %}}", pos.line, pos.column)
    if t.base["kind"] == "record":
        raise TriplateTypeError(f"{'.'.join(path)} is an object; reference a field", pos.line, pos.column)
    return t.base


# ---- rendering -----------------------------------------------------------

def _render(nodes, env):
    out = []
    for node in nodes:
        if isinstance(node, TextNode):
            out.append(node.value)
        elif isinstance(node, ValueNode):
            value, type_, _ = _resolve(env, node.path, node, True)
            out.append(_serialize_scalar(_scalar_of(type_, node.path, node), value, node))
        elif isinstance(node, InterpNode):
            out.append(_render_interp(env, node))
        elif isinstance(node, IriNode):
            out.append(_render_iri(env, node))
        elif isinstance(node, ForNode):
            out.append(_render_for(env, node))
        elif isinstance(node, IfNode):
            out.append(_render_if(env, node))
    return "".join(out)


def _serialize_scalar(t, value, pos):
    k = t["kind"]
    if k == "iri":
        return serialize_iri(value, pos)
    if k == "pname":
        return serialize_pname(value, pos)
    if k == "string":
        return serialize_string(value, pos)
    if k == "int":
        return serialize_int(value, pos)
    if k == "decimal":
        return serialize_decimal(value, pos)
    if k == "double":
        return serialize_double(value, pos)
    if k == "bool":
        return serialize_bool(value, pos)
    if k == "date":
        return serialize_date(value, pos)
    if k == "dateTime":
        return serialize_date_time(value, pos)
    if k == "time":
        return serialize_time(value, pos)
    if k == "literal":
        return serialize_typed_literal(value, t["datatype"], pos)
    if k == "term":
        return serialize_term(value, pos)
    if k == "raw":
        return serialize_raw(value, pos)
    if k == "custom":
        fn = get_custom_type(t["name"])
        if fn is None:
            raise TriplateTypeError(f"unknown custom type: {t['name']}", pos.line, pos.column)
        return fn(value, pos)
    raise TriplateTypeError(f"unknown type kind: {k}", pos.line, pos.column)


def _hole_lexical(env, part):
    value, type_, _ = _resolve(env, part.path, part, True)
    t = _scalar_of(type_, part.path, part)
    k = t["kind"]
    if k == "raw":
        return True, serialize_raw(value, part)
    if k == "string":
        return False, require_string(value, "string", part)
    if k == "iri":
        return False, validate_iri(value, part)
    if k == "pname":
        return False, serialize_pname(value, part)
    if k == "int":
        return False, serialize_int(value, part)
    if k == "decimal":
        return False, serialize_decimal(value, part)
    if k == "double":
        return False, serialize_double(value, part)
    if k == "bool":
        return False, serialize_bool(value, part)
    raise TriplateTypeError(
        f"type {k} cannot be interpolated; use it as a standalone ${{ … }}", part.line, part.column
    )


def _resolve_lang(env, lang, pos):
    if isinstance(lang, LangStatic):
        return validate_lang_tag(lang.static, pos)
    value, _, _ = _resolve(env, lang.path, pos, True)
    return validate_lang_tag(value, pos)


def _render_interp(env, node):
    content = []
    for part in node.parts:
        if isinstance(part, PartHole):
            raw, text = _hole_lexical(env, part)
            content.append(text if raw else escape_string(text))
        else:
            content.append(escape_string(part.text))
    out = '"' + "".join(content) + '"'
    if node.lang is not None:
        out += "@" + _resolve_lang(env, node.lang, node)
    elif node.datatype is not None:
        out += "^^" + node.datatype
    return out


def _render_iri(env, node):
    body = []
    for part in node.parts:
        if isinstance(part, PartHole):
            raw, text = _hole_lexical(env, part)
            body.append(text if raw else percent_encode(text))
        else:
            body.append(part.text)
    return "<" + validate_assembled_iri("".join(body), node) + ">"


def _render_for(env, node):
    value, type_, _ = _resolve(env, node.source, node, True)
    if not type_.array or not isinstance(value, (list, tuple)):
        raise TriplateTypeError(f"{'.'.join(node.source)} is not a list", node.line, node.column)
    elem = TypeExpr(type_.base, False, False, None, None)
    chunks = []
    for item in value:
        child = {"schema": env["schema"], "context": env["context"], "loop": env["loop"] + [{node.item: (item, elem)}]}
        chunks.append(_render(node.body, child))
    if node.join is None:
        return "".join(chunks)
    if node.join_exact:
        separator = node.join
    else:
        trimmed = node.join.strip()
        separator = f" {trimmed} " if trimmed else " "
    out = chunks[0] if chunks else ""
    for chunk in chunks[1:]:
        out = _TRAILING_WS.sub("", out) + separator + _LEADING_WS.sub("", chunk)
    return out


def _eval_cond(env, cond):
    value, type_, present = _resolve(env, cond.path, cond, False)
    if type_.array:
        result = present and isinstance(value, (list, tuple)) and len(value) > 0
    elif type_.base["kind"] == "bool":
        result = present and value is True
    elif type_.optional:
        result = present
    else:
        what = "object" if type_.base["kind"] == "record" else type_.base["kind"]
        raise TriplateTypeError(
            f"condition on required {what} '{'.'.join(cond.path)}' is always true",
            cond.line,
            cond.column,
        )
    return (not result) if cond.negated else result


def _render_if(env, node):
    for branch in node.branches:
        if _eval_cond(env, branch.cond):
            return _render(branch.body, env)
    if node.else_body is not None:
        return _render(node.else_body, env)
    return ""
