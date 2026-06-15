import datetime

import pytest

from triplate import (
    TriplateBindingError,
    TriplateSyntaxError,
    TriplateTypeError,
    compile,
    register_type,
    render,
    symbols,
)


def H(decls, body):
    return f"---\nparams {{ {decls} }}\n---\n{body}"


def test_compile_once_render_many():
    tmpl = compile(H("c: iri", "?s a ${c}"))
    assert "<http://example.org/A>" in tmpl.render(c="http://example.org/A")
    assert "<http://example.org/B>" in tmpl.render(c="http://example.org/B")


def test_kwargs_and_mapping():
    assert render(H("x: int", "${x}"), {"x": 1}) == "1"
    assert render(H("x: int", "${x}"), x=1) == "1"


def test_schema_exposure():
    tmpl = compile(H("s: iri, n: int optional", "${s}"))
    assert [p.name for p in tmpl.schema.params] == ["s", "n"]
    assert tmpl.schema.by_name["n"].optional is True


def test_frontmatter_is_stripped():
    tmpl = compile("---\nparams {\n  c: iri\n}\n# metadata\n---\nSELECT * WHERE { ?s a ${c} }")
    assert tmpl.render(c="http://example.org/A") == "SELECT * WHERE { ?s a <http://example.org/A> }"


def test_preview_example():
    tmpl = compile(
        "---\n"
        "params {\n  classes: iri[]\n}\n"
        'example demo "Demo" {\n  classes: [ ex:Person, <http://example.org/Org> ]\n}\n'
        "---\n"
        "PREFIX ex: <http://example.org/>\n"
        'SELECT * WHERE {\n{% for c in classes join "UNION" %}\n  { ?s a ${c} }\n{% endfor %}\n}'
    )
    assert [e.id for e in tmpl.examples] == ["demo"]
    out = tmpl.preview_example("demo")
    assert "{ ?s a <http://example.org/Person> } UNION { ?s a <http://example.org/Org> }" in out


def test_bool_rejected_for_int():
    with pytest.raises(TriplateTypeError):
        render(H("x: int", "${x}"), x=True)


def test_datetime_object():
    dt = datetime.datetime(2024, 3, 1, 12, 0, 0)
    assert render(H("x: dateTime", "${x}"), x=dt) == (
        '"2024-03-01T12:00:00"^^<http://www.w3.org/2001/XMLSchema#dateTime>'
    )


def test_rdflib_terms():
    rdflib = pytest.importorskip("rdflib")
    assert render(H("t: term", "${t}"), t=rdflib.URIRef("http://example.org/x")) == "<http://example.org/x>"
    assert render(H("t: term", "${t}"), t=rdflib.Literal("hi", lang="en")) == '"hi"@en'
    with pytest.raises(TriplateTypeError):
        render(H("t: term", "${t}"), t=rdflib.URIRef("http://x/> . } DROP ALL #"))


def test_custom_type():
    import re

    def uuidref(value, pos):
        if not isinstance(value, str) or not re.match(r"^[0-9a-f-]{36}$", value):
            raise TriplateTypeError("invalid uuid", pos.line, pos.column)
        return f"<urn:uuid:{value}>"

    register_type("uuidref", uuidref)
    assert render(H("id: uuidref", "${id}"), id="123e4567-e89b-12d3-a456-426614174000") == (
        "<urn:uuid:123e4567-e89b-12d3-a456-426614174000>"
    )


def test_iri_template():
    assert render(H("id: string", "$<http://ex.org/${id}>"), id="a/b é") == "<http://ex.org/a%2Fb%20%C3%A9>"
    with pytest.raises(TriplateTypeError):
        render(H("x: raw", "$<http://ex.org/${x}>"), x="a> <b")


def test_explicit_join():
    assert render(H("xs: string[]", '{% for c in xs join "," %}${c}{% endfor %}'), xs=["a", "b"]) == '"a" , "b"'
    assert render(H("xs: string[]", '{% for c in xs join "," explicit %}${c}{% endfor %}'), xs=["a", "b"]) == '"a","b"'


def test_frontmatter_prefixes():
    tmpl = compile(
        "---\n"
        "params {\n  type: iri\n  amount: literal(xsd:decimal)\n  note: string\n}\n"
        'example demo "Demo" {\n  type: schema:Person\n  amount: "5"\n  note: "n"^^my:dt\n}\n'
        "---\n${type}"
    )
    assert tmpl.frontmatter_prefixes() == {"my", "schema", "xsd"}


def test_frontmatter_prefixes_ignores_full_iri():
    tmpl = compile(
        '---\nparams {\n  type: iri\n}\nexample demo "D" {\n  type: <http://example.org/Person>\n}\n---\n${type}'
    )
    assert tmpl.frontmatter_prefixes() == set()


def test_undeclared_is_compile_error():
    with pytest.raises(TriplateSyntaxError):
        compile(H("s: iri", "${t}"))


def test_missing_required_is_render_error():
    with pytest.raises(TriplateBindingError):
        render(H("s: iri", "${s}"), {})


def test_no_frontmatter_is_rejected():
    with pytest.raises(TriplateSyntaxError):
        compile("SELECT * WHERE { ?s ?p ?o }")


_SYM_SRC = (
    "---\n"
    "params {\n  who: pname\n  amount: literal(xsd:decimal)\n}\n"
    'example demo "D" {\n  who: schema:Person\n  amount: "9.99"^^xsd:decimal\n  home: <http://ex.org/me>\n}\n'
    "---\n"
    "?s a ${who} . {% if amount %}${amount}{% endif %}"
)


def test_symbol_spans_slice_their_own_text():
    for s in compile(_SYM_SRC).symbols():
        slice_ = _SYM_SRC[s.start : s.end]
        if s.kind == "pname":
            expected = f"{s.prefix}:{s.local}"
        elif s.kind == "iri":
            expected = f"<{s.value}>"
        elif s.kind == "literal":
            expected = f'"{s.value}"'
        else:
            expected = s.name
        assert slice_ == expected


def test_symbols_capture_every_kind():
    syms = compile(_SYM_SRC).symbols()
    by_kind = lambda k: [s for s in syms if s.kind == k]  # noqa: E731
    assert [s.name for s in by_kind("paramDecl")] == ["who", "amount"]
    # Two `amount` refs: the {% if amount %} condition and the ${amount} hole.
    assert [s.name for s in by_kind("paramRef")] == ["who", "amount", "amount"]
    assert [s.name for s in by_kind("bindingKey")] == ["who", "amount", "home"]
    # pname: the literal(xsd:decimal) type, the schema:Person value, the ^^xsd:decimal datatype.
    assert len(by_kind("pname")) == 3
    assert [s.value for s in by_kind("iri")] == ["http://ex.org/me"]
    lits = by_kind("literal")
    assert len(lits) == 1
    assert (lits[0].value, lits[0].datatype) == ("9.99", "xsd:decimal")


def test_symbols_are_in_ascending_source_order():
    offsets = [s.start for s in compile(_SYM_SRC).symbols()]
    assert offsets == sorted(offsets)


def test_symbol_rename_sites_group_by_name():
    sites = [
        s
        for s in compile(_SYM_SRC).symbols()
        if s.kind in ("paramDecl", "paramRef", "bindingKey") and s.name == "amount"
    ]
    assert [s.kind for s in sites] == ["paramDecl", "bindingKey", "paramRef", "paramRef"]


def test_module_symbols_is_lenient_on_malformed_template():
    bad = '---\nparams { a: int }\nexample x {\n  who: schema:Person\n'
    with pytest.raises(TriplateSyntaxError):
        compile(bad)
    assert [s.kind for s in symbols(bad)] == ["paramDecl", "bindingKey", "pname"]


def test_double_specials():
    assert render(H("x: double", "${x}"), x=float("nan")) == '"NaN"^^<http://www.w3.org/2001/XMLSchema#double>'
