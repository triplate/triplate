package dev.triplate;

import java.util.Map;

/**
 * Entry point for Triplate — a templating engine for RDF query &amp; data
 * languages (SPARQL, Turtle, TriG, N-Triples). Compile a template once and
 * render it many times; values are validated and escaped per their declared RDF
 * type, so rendered output is injection-safe by construction.
 *
 * <pre>{@code
 * String template = """
 *     ---
 *     params { service: iri, classes: iri[] min 1, limit: int optional }
 *     ---
 *     SELECT * WHERE {
 *       SERVICE ${service} {
 *     {% for c in classes join "UNION" %}
 *         { ?s a ${c} }
 *     {% endfor %}
 *       }
 *     }
 *     {% if limit %}LIMIT ${limit}{% endif %}
 *     """;
 *
 * String sparql = Triplate.render(template, Map.of(
 *     "service", "http://dbpedia.org/sparql",
 *     "classes", List.of("http://xmlns.com/foaf/0.1/Person"),
 *     "limit", 10));
 * }</pre>
 *
 * <p>Context values are plain Java objects: {@code String} (iri/pname/string/raw/literal),
 * {@code Long}/{@code Integer}/{@code BigInteger} (int), {@code Double}/{@code BigDecimal}
 * (decimal/double), {@code Boolean} (bool), {@code java.time} types or ISO strings
 * (date/dateTime/time), a {@link Term} (term), {@code List} (arrays) and {@code Map}
 * (records).
 */
public final class Triplate {
  private Triplate() {}

  /** Parse a template once; the result can be rendered many times. */
  public static CompiledTemplate compile(String template) {
    return new CompiledTemplate(Parser.parse(template), template);
  }

  /** One-shot convenience: compile and render in a single call. */
  public static String render(String template, Map<String, Object> context) {
    return compile(template).render(context);
  }

  /** One-shot render with no context (valid only when every parameter is optional). */
  public static String render(String template) {
    return render(template, Map.of());
  }

  private static final java.util.regex.Pattern HEADER =
      java.util.regex.Pattern.compile("^---[ \\t]*(\\r?\\n|$)");

  /** Returns true if {@code text} opens with a {@code ---} frontmatter header. */
  public static boolean isTemplate(String text) {
    return HEADER.matcher(text).lookingAt();
  }
}
