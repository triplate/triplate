package dev.triplate;

import dev.triplate.Ast.BindingKeySym;
import dev.triplate.Ast.CommentSym;
import dev.triplate.Ast.IriSym;
import dev.triplate.Ast.LiteralSym;
import dev.triplate.Ast.LoopDeclSym;
import dev.triplate.Ast.LoopRefSym;
import dev.triplate.Ast.ParamDeclSym;
import dev.triplate.Ast.ParamRefSym;
import dev.triplate.Ast.PnameSym;
import dev.triplate.Ast.TemplateSymbol;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiTest {

  /** Build a minimal template: a frontmatter header with the given declarations plus a body. */
  private static String h(String decls, String body) {
    return "---\nparams { " + decls + " }\n---\n" + body;
  }

  @Test
  void frontmatterIsStripped() {
    String out = Triplate.render(h("s: iri", "?x a ${s}"), Map.of("s", "http://example.org/C"));
    assertEquals("?x a <http://example.org/C>", out);
  }

  @Test
  void missingHeaderThrowsSyntaxError() {
    assertThrows(TriplateSyntaxError.class, () -> Triplate.render("SELECT * WHERE { ?s ?p ?o }", Map.of()));
  }

  @Test
  void undeclaredVariableThrowsAtCompileTime() {
    assertThrows(TriplateSyntaxError.class, () -> Triplate.compile(h("s: iri", "?x a ${t}")));
  }

  @Test
  void frontmatterPrefixesFromExamplesAndLiteralTypes() {
    CompiledTemplate tmpl = Triplate.compile(
        "---\n"
            + "params {\n  type: iri\n  amount: literal(xsd:decimal)\n  note: string\n}\n"
            + "example demo \"Demo\" {\n  type: schema:Person\n  amount: \"5\"\n  note: \"n\"^^my:dt\n}\n"
            + "---\n${type}");
    assertEquals(Set.of("my", "schema", "xsd"), tmpl.frontmatterPrefixes());
  }

  @Test
  void frontmatterPrefixesIgnoresFullIri() {
    CompiledTemplate tmpl = Triplate.compile(
        "---\nparams {\n  type: iri\n}\nexample demo \"D\" {\n  type: <http://example.org/Person>\n}\n---\n${type}");
    assertTrue(tmpl.frontmatterPrefixes().isEmpty());
  }

  @Test
  void unknownParameterThrowsBindingError() {
    assertThrows(TriplateBindingError.class,
        () -> Triplate.render(h("s: iri", "?x a ${s}"),
            Map.of("s", "http://example.org/C", "extra", "nope")));
  }

  @Test
  void iriTemplatePercentEncodesHoles() {
    String out = Triplate.render(
        h("id: string", "?s ex:p $<http://example.org/person/${id}>"),
        Map.of("id", "a b/c"));
    assertEquals("?s ex:p <http://example.org/person/a%20b%2Fc>", out);
  }

  @Test
  void rawHoleBreakingIriIsRejected() {
    assertThrows(TriplateTypeError.class,
        () -> Triplate.render(h("x: raw", "?s a $<http://example.org/${x}>"),
            Map.of("x", "a> . ?s ?p ?o . <b")));
  }

  @Test
  void forLoopWithExplicitJoinIsVerbatim() {
    String out = Triplate.render(
        h("xs: int[]", "{% for x in xs join \",\" explicit %}${x}{% endfor %}"),
        Map.of("xs", List.of(1, 2, 3)));
    assertEquals("1,2,3", out);
  }

  @Test
  void cardinalityBelowMinThrows() {
    assertThrows(TriplateCardinalityError.class,
        () -> Triplate.render(h("xs: iri[] min 1", "${xs}"), Map.of("xs", List.of())));
  }

  @Test
  void termTypeSerializesNamedNode() {
    Term namedNode = new Term() {
      @Override public String termType() { return "NamedNode"; }
      @Override public String value() { return "http://example.org/x"; }
    };
    String out = Triplate.render(h("t: term", "?s a ${t}"), Map.of("t", namedNode));
    assertEquals("?s a <http://example.org/x>", out);
  }

  @Test
  void previewExampleRendersExampleSet() {
    String template = """
        ---
        params { service: iri, limit: int }
        example demo "DBpedia" {
          service: <http://dbpedia.org/sparql>
          limit: 10
        }
        ---
        SERVICE ${service} { ?s ?p ?o } LIMIT ${limit}""";
    CompiledTemplate t = Triplate.compile(template);
    assertEquals(1, t.examples().size());
    String out = t.previewExample("demo");
    assertEquals("SERVICE <http://dbpedia.org/sparql> { ?s ?p ?o } LIMIT 10", out);
  }

  @Test
  void doubleUsesCanonicalScientificNotation() {
    assertEquals("FILTER(?x > 1.5E6)",
        Triplate.render(h("d: double", "FILTER(?x > ${d})"), Map.of("d", 1500000)));
    assertTrue(Triplate.render(h("d: double", "${d}"), Map.of("d", 0)).equals("0.0E0"));
  }

  private static final String SYM_SRC =
      "---\n"
          + "params {\n  who: pname\n  amount: literal(xsd:decimal)\n}\n"
          + "example demo \"D\" {\n  who: schema:Person\n  amount: \"9.99\"^^xsd:decimal\n  home: <http://ex.org/me>\n}\n"
          + "---\n"
          + "?s a ${who} . {% if amount %}${amount}{% endif %}";

  private static List<String> names(List<TemplateSymbol> syms, Class<? extends TemplateSymbol> kind) {
    List<String> out = new ArrayList<>();
    for (TemplateSymbol s : syms) {
      if (kind.isInstance(s)) {
        if (s instanceof ParamDeclSym d) out.add(d.name());
        else if (s instanceof ParamRefSym r) out.add(r.name());
        else if (s instanceof BindingKeySym b) out.add(b.name());
      }
    }
    return out;
  }

  @Test
  void symbolSpansSliceTheirOwnText() {
    for (TemplateSymbol s : Triplate.compile(SYM_SRC).symbols()) {
      String slice = SYM_SRC.substring(s.start(), s.end());
      String expected;
      if (s instanceof PnameSym p) expected = p.prefix() + ":" + p.local();
      else if (s instanceof IriSym i) expected = "<" + i.value() + ">";
      else if (s instanceof LiteralSym l) expected = "\"" + l.value() + "\"";
      else if (s instanceof ParamDeclSym d) expected = d.name();
      else if (s instanceof ParamRefSym r) expected = r.name();
      else if (s instanceof BindingKeySym b) expected = b.name();
      else throw new AssertionError("unexpected symbol: " + s);
      assertEquals(expected, slice);
    }
  }

  @Test
  void symbolsCaptureEveryKind() {
    List<TemplateSymbol> syms = Triplate.compile(SYM_SRC).symbols();
    assertEquals(List.of("who", "amount"), names(syms, ParamDeclSym.class));
    // Two `amount` refs: the {% if amount %} condition and the ${amount} hole.
    assertEquals(List.of("who", "amount", "amount"), names(syms, ParamRefSym.class));
    assertEquals(List.of("who", "amount", "home"), names(syms, BindingKeySym.class));
    // pname: the literal(xsd:decimal) type, the schema:Person value, the ^^xsd:decimal datatype.
    assertEquals(3, syms.stream().filter(s -> s instanceof PnameSym).count());
    List<TemplateSymbol> iris = syms.stream().filter(s -> s instanceof IriSym).toList();
    assertEquals(1, iris.size());
    assertEquals("http://ex.org/me", ((IriSym) iris.get(0)).value());
    List<TemplateSymbol> lits = syms.stream().filter(s -> s instanceof LiteralSym).toList();
    assertEquals(1, lits.size());
    assertEquals("9.99", ((LiteralSym) lits.get(0)).value());
    assertEquals("xsd:decimal", ((LiteralSym) lits.get(0)).datatype());
  }

  @Test
  void symbolsAreInAscendingSourceOrder() {
    int prev = -1;
    for (TemplateSymbol s : Triplate.compile(SYM_SRC).symbols()) {
      assertTrue(s.start() >= prev, "symbols must be in ascending source order");
      prev = s.start();
    }
  }

  @Test
  void hashIsPlainTextInBodyNotAComment() {
    CompiledTemplate tmpl = Triplate.compile("---\nparams { title: raw }\n---\n# ${title}");
    assertEquals("# My Title", tmpl.render(Map.of("title", "My Title")));
  }

  @Test
  void frontmatterCommentsArePositionedSymbols() {
    String src =
        "---\n"
            + "# a standalone comment\n"
            + "params {\n  who: pname  # the subject\n}\n"
            + "---\n"
            + "?s a ${who}";
    List<CommentSym> comments =
        Triplate.compile(src).symbols().stream()
            .filter(s -> s instanceof CommentSym)
            .map(s -> (CommentSym) s)
            .toList();
    assertEquals(
        List.of("# a standalone comment", "# the subject"),
        comments.stream().map(CommentSym::value).toList());
    for (CommentSym c : comments) {
      assertEquals(c.value(), src.substring(c.start(), c.end()));
    }
    // Body comments stay text, not symbols — comments are frontmatter-only.
    assertTrue(
        Triplate.compile("---\nparams { x: int }\n---\n# body ${x}").symbols().stream()
            .noneMatch(s -> s instanceof CommentSym));
    // Comments do not disturb ascending source order.
    int prev = -1;
    for (TemplateSymbol s : Triplate.compile(src).symbols()) {
      assertTrue(s.start() >= prev, "symbols must be in ascending source order");
      prev = s.start();
    }
  }

  @Test
  void nestedFrontmatterCommentsArePositionedSymbols() {
    String src =
        "---\n"
            + "# a header note\n"
            + "params {\n"
            + "  u: {\n"
            + "    id:   iri     # in a record type\n"
            + "    name: string\n"
            + "  }\n"
            + "  tags: string[]  # in params\n"
            + "}\n"
            + "example demo {\n"
            + "  u: { id: <http://example.org/1>,   # in an example record\n"
            + "       name: \"Alice\" }\n"
            + "  tags: [\n"
            + "    \"a\",          # in an example list\n"
            + "    \"b\"\n"
            + "  ]\n"
            + "}\n"
            + "---\n"
            + "${u.id} ${...tags}";
    List<TemplateSymbol> symbols = Triplate.compile(src).symbols();
    List<CommentSym> comments =
        symbols.stream().filter(s -> s instanceof CommentSym).map(s -> (CommentSym) s).toList();
    // A comment is legal wherever an item may start, at every nesting depth.
    assertEquals(
        List.of(
            "# a header note",
            "# in a record type",
            "# in params",
            "# in an example record",
            "# in an example list"),
        comments.stream().map(CommentSym::value).toList());
    for (CommentSym c : comments) {
      assertEquals(c.value(), src.substring(c.start(), c.end()));
    }
    int prev = -1;
    for (TemplateSymbol s : symbols) {
      assertTrue(s.start() >= prev, "symbols must be in ascending source order");
      prev = s.start();
    }
  }

  @Test
  void moduleSymbolsIsLenientOnMalformedTemplate() {
    String bad = "---\nparams { a: int }\nexample x {\n  who: schema:Person\n";
    assertThrows(TriplateSyntaxError.class, () -> Triplate.compile(bad));
    List<TemplateSymbol> syms = Triplate.symbols(bad);
    assertEquals(3, syms.size());
    assertTrue(syms.get(0) instanceof ParamDeclSym);
    assertTrue(syms.get(1) instanceof BindingKeySym);
    assertTrue(syms.get(2) instanceof PnameSym);
  }

  private static List<LoopDeclSym> loopDecls(List<TemplateSymbol> syms) {
    return syms.stream().filter(s -> s instanceof LoopDeclSym).map(s -> (LoopDeclSym) s).toList();
  }

  private static List<LoopRefSym> loopRefs(List<TemplateSymbol> syms) {
    return syms.stream().filter(s -> s instanceof LoopRefSym).map(s -> (LoopRefSym) s).toList();
  }

  @Test
  void forLoopEmitsLoopDeclAndLoopRefsInOneScope() {
    String src = "---\nparams { graphIris: pname[] }\n---\n{% for g in graphIris %} FROM ${g} ${g} {% endfor %}";
    List<TemplateSymbol> syms = Triplate.symbols(src);
    // The for source is an ordinary parameter reference, not a loop ref.
    assertEquals(
        List.of("graphIris"),
        syms.stream().filter(s -> s instanceof ParamRefSym).map(s -> ((ParamRefSym) s).name()).toList());
    List<LoopDeclSym> decls = loopDecls(syms);
    List<LoopRefSym> refs = loopRefs(syms);
    assertEquals(List.of("g"), decls.stream().map(LoopDeclSym::name).toList());
    assertEquals(List.of("g", "g"), refs.stream().map(LoopRefSym::name).toList());
    // Declaration and both references share the one scope id.
    Set<Integer> ids = new java.util.HashSet<>();
    ids.add(decls.get(0).scope());
    refs.forEach(r -> ids.add(r.scope()));
    assertEquals(1, ids.size());
    // Spans still ascend (the item precedes the source in the header).
    int prev = -1;
    for (TemplateSymbol s : syms) {
      assertTrue(s.start() >= prev, "symbols must be in ascending source order");
      prev = s.start();
    }
  }

  @Test
  void loopSymbolSpansSliceTheirOwnText() {
    String src = "---\nparams { xs: pname[] }\n---\n{% for g in xs %}${g}{% endfor %}";
    for (TemplateSymbol s : Triplate.symbols(src)) {
      if (s instanceof LoopDeclSym d) assertEquals(d.name(), src.substring(d.start(), d.end()));
      else if (s instanceof LoopRefSym r) assertEquals(r.name(), src.substring(r.start(), r.end()));
    }
  }

  @Test
  void loopShadowingBindsRefsToTheirOwnScope() {
    String src =
        "---\nparams { a: pname[]\n  b: pname[] }\n---\n"
            + "{% for g in a %}${g}{% for g in b %}${g}{% endfor %}${g}{% endfor %}";
    List<TemplateSymbol> syms = Triplate.symbols(src);
    List<LoopDeclSym> decls = loopDecls(syms);
    assertEquals(2, decls.size());
    int outer = decls.get(0).scope();
    int inner = decls.get(1).scope();
    assertTrue(outer != inner);
    // Refs in source order: outer ${g}, inner ${g}, outer ${g}.
    assertEquals(
        List.of(outer, inner, outer), loopRefs(syms).stream().map(LoopRefSym::scope).toList());
  }

  @Test
  void loopVariableShadowsSameNamedParameter() {
    String src = "---\nparams { g: pname\n  xs: pname[] }\n---\n${g} {% for g in xs %}${g}{% endfor %} ${g}";
    List<TemplateSymbol> syms = Triplate.symbols(src);
    // Two ${g} outside the loop stay paramRefs; the in-loop ${g} is a loopRef.
    assertEquals(
        List.of("g", "xs", "g"),
        syms.stream().filter(s -> s instanceof ParamRefSym).map(s -> ((ParamRefSym) s).name()).toList());
    List<LoopRefSym> refs = loopRefs(syms);
    assertEquals(List.of("g"), refs.stream().map(LoopRefSym::name).toList());
    assertEquals(loopDecls(syms).get(0).scope(), refs.get(0).scope());
  }

  @Test
  void dottedLoopReferenceMarksOnlyTheRoot() {
    String src = "---\nparams { xs: pname[] }\n---\n{% for g in xs %}${g.field}{% endfor %}";
    assertEquals(List.of("g"), loopRefs(Triplate.symbols(src)).stream().map(LoopRefSym::name).toList());
  }

  @Test
  void moduleSymbolsCollectsLoopSymbolsOnMalformedTemplate() {
    String bad = "---\nparams { xs: pname[] }\n---\n{% for g in xs %}${g} {% if";
    List<TemplateSymbol> syms = Triplate.symbols(bad);
    assertTrue(syms.stream().anyMatch(s -> s instanceof LoopDeclSym));
    assertTrue(syms.stream().anyMatch(s -> s instanceof LoopRefSym));
  }
}
