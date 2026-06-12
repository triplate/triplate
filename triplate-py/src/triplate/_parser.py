"""Token stream to AST. Mirrors the TypeScript implementation's parser.ts."""

from ._ast import (
    Branch,
    CompiledTemplateData,
    ExampleSet,
    ForNode,
    IfNode,
    InterpNode,
    IriNode,
    PartHole,
    Schema,
    TextNode,
    ValueNode,
)
from ._lexer import tokenize
from .errors import TriplateSyntaxError


def parse(template):
    tokens = tokenize(template)
    schema = None
    examples = []
    body = []
    for t in tokens:
        if t["kind"] == "params":
            if schema is not None:
                raise TriplateSyntaxError("duplicate params section in frontmatter", t["line"], t["column"])
            by_name = {}
            for d in t["decls"]:
                if d.name in by_name:
                    raise TriplateSyntaxError(f"duplicate parameter: {d.name}", t["line"], t["column"])
                by_name[d.name] = d.type
            schema = Schema(t["decls"], by_name)
        elif t["kind"] == "examples":
            if any(e.id == t["id"] for e in examples):
                raise TriplateSyntaxError(f"duplicate example id: {t['id']}", t["line"], t["column"])
            examples.append(ExampleSet(t["id"], t["description"], t["bindings"]))
        elif t["kind"] == "text" and schema is None:
            if t["value"].strip() != "":
                raise TriplateSyntaxError("content before the --- frontmatter header")
        else:
            body.append(t)
    if schema is None:
        raise TriplateSyntaxError("missing --- frontmatter header (--- params { … } ---)")

    tree = _build_tree(body)
    _validate_scopes(tree, set(schema.by_name.keys()))
    return CompiledTemplateData(schema, examples, tree)


def _build_tree(tokens):
    root = []
    # frames: ("root", list) | ("for", ForNode) | ("if", IfNode, [in_else])
    stack = [["root", root]]

    def current():
        f = stack[-1]
        if f[0] == "root":
            return f[1]
        if f[0] == "for":
            return f[1].body
        node = f[1]
        return node.else_body if f[2] else node.branches[-1].body

    for t in tokens:
        k = t["kind"]
        if k == "text":
            current().append(TextNode(t["value"]))
        elif k == "value":
            current().append(ValueNode(t["path"], t["line"], t["column"]))
        elif k == "interp":
            current().append(InterpNode(t["parts"], t["lang"], t["datatype"], t["line"], t["column"]))
        elif k == "iri":
            current().append(IriNode(t["parts"], t["line"], t["column"]))
        elif k == "for":
            node = ForNode(t["item"], t["source"], t["join"], t["join_exact"], [], t["line"], t["column"])
            current().append(node)
            stack.append(["for", node])
        elif k == "endfor":
            if stack[-1][0] != "for":
                raise TriplateSyntaxError("%endfor without a matching %for", t["line"], t["column"])
            stack.pop()
        elif k == "if":
            node = IfNode([Branch(t["cond"], [])], None, t["line"], t["column"])
            current().append(node)
            stack.append(["if", node, False])
        elif k == "elif":
            f = stack[-1]
            if f[0] != "if" or f[2]:
                raise TriplateSyntaxError("%elif without a matching %if", t["line"], t["column"])
            f[1].branches.append(Branch(t["cond"], []))
        elif k == "else":
            f = stack[-1]
            if f[0] != "if" or f[2]:
                raise TriplateSyntaxError("%else without a matching %if", t["line"], t["column"])
            f[2] = True
            f[1].else_body = []
        elif k == "endif":
            if stack[-1][0] != "if":
                raise TriplateSyntaxError("%endif without a matching %if", t["line"], t["column"])
            stack.pop()

    if len(stack) > 1:
        node = stack[-1][1]
        raise TriplateSyntaxError("unclosed block directive", node.line, node.column)
    return root


def _validate_scopes(nodes, bound):
    def check(path, line, column):
        if path[0] not in bound:
            raise TriplateSyntaxError(f"undeclared variable: {'.'.join(path)}", line, column)

    for node in nodes:
        if isinstance(node, ValueNode):
            check(node.path, node.line, node.column)
        elif isinstance(node, InterpNode):
            if node.lang is not None and hasattr(node.lang, "path") and node.lang.path is not None:
                check(node.lang.path, node.line, node.column)
            for p in node.parts:
                if isinstance(p, PartHole):
                    check(p.path, p.line, p.column)
        elif isinstance(node, IriNode):
            for p in node.parts:
                if isinstance(p, PartHole):
                    check(p.path, p.line, p.column)
        elif isinstance(node, ForNode):
            check(node.source, node.line, node.column)
            inner = set(bound)
            inner.add(node.item)
            _validate_scopes(node.body, inner)
        elif isinstance(node, IfNode):
            for b in node.branches:
                check(b.cond.path, b.cond.line, b.cond.column)
                _validate_scopes(b.body, bound)
            if node.else_body is not None:
                _validate_scopes(node.else_body, bound)
