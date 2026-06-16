package dev.triplate;

import dev.triplate.Ast.BindingKeySym;
import dev.triplate.Ast.CommentSym;
import dev.triplate.Ast.IriSym;
import dev.triplate.Ast.LiteralSym;
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
    // Body comments stay text, not symbols.
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
  void moduleSymbolsIsLenientOnMalformedTemplate() {
    String bad = "---\nparams { a: int }\nexample x {\n  who: schema:Person\n";
    assertThrows(TriplateSyntaxError.class, () -> Triplate.compile(bad));
    List<TemplateSymbol> syms = Triplate.symbols(bad);
    assertEquals(3, syms.size());
    assertTrue(syms.get(0) instanceof ParamDeclSym);
    assertTrue(syms.get(1) instanceof BindingKeySym);
    assertTrue(syms.get(2) instanceof PnameSym);
  }
}
