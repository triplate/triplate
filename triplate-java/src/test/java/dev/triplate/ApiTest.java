package dev.triplate;

import org.junit.jupiter.api.Test;

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
}
